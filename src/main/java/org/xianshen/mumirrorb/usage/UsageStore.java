package org.xianshen.mumirrorb.usage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent transactions: a business rollback cannot erase an observed model charge. */
@Service
@Slf4j
public class UsageStore {
    private final JdbcTemplate db;
    private final TransactionTemplate receipts;
    public UsageStore(JdbcTemplate db,PlatformTransactionManager manager) {
        this.db=db; receipts=new TransactionTemplate(manager);
        receipts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        receipts.setTimeout(10);
    }
    public UsageEnvelope.Snapshot snapshot(UUID user,CommonProto.LlmConfig config) {
        return snapshot(user,config,null);
    }
    public UsageEnvelope.Snapshot snapshot(UUID user,CommonProto.LlmConfig config,UUID profile) {
        String name=null;
        // The profile id is captured in the same read as the config, never re-read from active settings.
        try {
            if(profile!=null) {
                var names=db.queryForList("SELECT name FROM user_model_profiles WHERE id=? AND user_id=?",String.class,profile,user);
                if(!names.isEmpty())name=names.get(0);
            }
        } catch(Exception ignored) { /* Snapshot failure must not interrupt inference. */ }
        String endpoint="custom";String normalized="custom";
        try {
            URI uri=URI.create(config.getBaseUrl()).normalize();
            String host=uri.getHost();
            if(host!=null) {
                endpoint=host.toLowerCase(Locale.ROOT)+(uri.getPort()<0?"":":"+uri.getPort());
                normalized=uri.getScheme()+"://"+endpoint+Objects.toString(uri.getPath(),"");
            }
        } catch(Exception ignored) { }
        String key;
        try { key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((Objects.toString(profile,"active")+":"+normalized).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
        return new UsageEnvelope.Snapshot(clean(config.getProvider(),128),clean(config.getModel(),256),key,
                clean(name==null?endpoint:name+" · "+endpoint,256),profile);
    }
    static String clean(String value,int max) {
        if(value==null) return "";
        String result=value.replaceAll("[\\p{Cntrl}]","");return result.substring(0,Math.min(max,result.length()));
    }
    public void begin(UsageEnvelope envelope) { safely(envelope.id(),()->insert(envelope)); }
    private void insert(UsageEnvelope e) {
        db.update("""
            INSERT INTO ai_usage_tasks(id,user_id,scene,sealed,started_at,updated_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
            """,e.taskId(),e.userId(),e.scene(),e.standalone(),e.startedAt(),e.startedAt());
        var s=e.snapshot();
        int added=db.update("""
            INSERT INTO ai_usage_invocations(id,task_id,user_id,scene,provider,model,profile_id,channel_key,channel_name,started_at)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
            """,e.id(),e.taskId(),e.userId(),e.scene(),s.provider(),s.model(),s.profileId(),s.channelKey(),s.channelName(),e.startedAt());
        if(added==1) db.update("UPDATE ai_usage_tasks SET updated_at=now(),completed_at=NULL WHERE id=? AND user_id=?",e.taskId(),e.userId());
    }
    public void complete(UsageEnvelope e,UsageReport report,String status) {
        safely(e.id(),()->{
            insert(e); // One bounded local recovery if prerecording failed; never retry the RPC.
            var rows=db.queryForList("SELECT status FROM ai_usage_invocations WHERE id=? AND user_id=? FOR UPDATE",e.id(),e.userId());
            if(rows.isEmpty()||!"pending".equals(rows.get(0).get("status"))) return;
            int ordinal=0;
            for(var a:report.attempts()) {
                db.update("""
                    INSERT INTO ai_usage_attempts(invocation_id,id,user_id,ordinal,status,provider,model,input_tokens,output_tokens,
                    total_tokens,credits,credit_unit,usage_state,latency_ms,http_status,cache_read_tokens,cache_creation_tokens,reasoning_tokens)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(invocation_id,id) DO NOTHING
                    """,e.id(),a.id(),e.userId(),ordinal++,a.status(),a.provider(),a.model(),a.inputTokens(),a.outputTokens(),
                        a.totalTokens(),a.credits(),a.creditUnit(),a.usageState(),a.latencyMs(),a.httpStatus(),
                        a.cacheReadTokens(),a.cacheCreationTokens(),a.reasoningTokens());
            }
            db.update("UPDATE ai_usage_invocations SET status=?,coverage=?,completed_at=now() WHERE id=? AND user_id=?",
                    status,report.complete()?"complete":"incomplete",e.id(),e.userId());
            refresh(e.userId(),e.taskId());
        });
    }
    public void finishTask(UUID user,UUID task) {
        safely(task,()->{
            db.update("UPDATE ai_usage_tasks SET sealed=true WHERE id=? AND user_id=?",task,user);
            refresh(user,task);
        });
    }
    private void refresh(UUID user,UUID task) {
        db.update("""
            UPDATE ai_usage_tasks t SET updated_at=now(),completed_at=CASE WHEN sealed AND NOT EXISTS(
            SELECT 1 FROM ai_usage_invocations i WHERE i.task_id=t.id AND i.status='pending')
            THEN COALESCE(t.completed_at,now()) ELSE NULL END WHERE t.id=? AND t.user_id=?
            """,task,user);
    }
    @Scheduled(fixedDelay=60000)
    public void expirePending() {
        safely(null,()->{
            var expired=db.queryForList("""
                UPDATE ai_usage_invocations SET status='error',coverage='incomplete',completed_at=now()
                WHERE status='pending' AND started_at < now()-interval '20 minutes' RETURNING task_id,user_id
                """);
            for(var row:expired) {
                UUID task=(UUID)row.get("task_id"),user=(UUID)row.get("user_id");
                db.update("UPDATE ai_usage_tasks SET sealed=true WHERE id=? AND user_id=?",task,user);refresh(user,task);
            }
        });
    }
    private void safely(UUID id,Runnable write) {
        try { receipts.executeWithoutResult(tx->write.run()); }
        catch(Exception error) {
            // SQL/SDK exception messages can contain credentials or content: log only the class.
            log.warn("AI usage persistence incomplete: invocation={}, failure={}",id,error.getClass().getSimpleName());
        }
    }
}
