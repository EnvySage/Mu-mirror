package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.TarotGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.TarotProto;
import org.xianshen.mumirrorb.service.*;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@Service
public class TarotAnalysisServiceImpl implements TarotAnalysisService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TarotService tarot;
    private final TarotContextService context;private final TarotCatalog catalog;private final TarotGrpcClient model;
    private final TransactionTemplate tx;private final Executor executor;
    @Autowired public TarotAnalysisServiceImpl(JdbcTemplate db,ObjectMapper json,TarotService tarot,TarotContextService context,
            TarotCatalog catalog,TarotGrpcClient model,PlatformTransactionManager manager,@Qualifier("tarotAnalysisExecutor") Executor executor){
        this(db,json,tarot,context,catalog,model,new TransactionTemplate(manager),executor);
    }
    public TarotAnalysisServiceImpl(JdbcTemplate db,ObjectMapper json,TarotService tarot,TarotContextService context,
            TarotCatalog catalog,TarotGrpcClient model,TransactionTemplate tx,Executor executor){
        this.db=db;this.json=json;this.tarot=tarot;this.context=context;this.catalog=catalog;this.model=model;this.tx=tx;this.executor=executor;
    }
    private record Job(UUID id,UUID user,UUID reading,String payloadHash,String question,ReferenceOptions options,String status,
            String text,List<Source> refs,List<String> notices,String error) {}
    private record Accepted(UUID id,boolean created) {}
    public AnalysisView submit(UUID user,UUID reading,AnalyzeInput input){
        if(input==null||input.clientRequestId()==null||input.options()==null||!input.options().valid())throw new BusinessException(400,"请检查问题与参考设置");
        String question=input.question()==null?"":input.question().trim();
        if(question.codePointCount(0,question.length())>500)throw new BusinessException(400,"问题最多 500 字");
        tarot.getReading(user,reading);
        String payloadHash=TarotContextServiceImpl.hash(reading+":"+question+":"+write(input.options()));
        // Configuration is checked only for a new request, never before replaying a saved result.
        Accepted accepted=tx.execute(status->{
            lock(user,"analysis-request:"+input.clientRequestId());
            var prior=db.queryForList("SELECT id,payload_hash FROM tarot_analysis WHERE user_id=? AND client_request_id=?",user,input.clientRequestId());
            if(!prior.isEmpty()){
                if(!payloadHash.equals(prior.get(0).get("payload_hash")))throw new BusinessException(409,"同一请求标识不能修改问题或参考设置");
                return new Accepted((UUID)prior.get(0).get("id"),false);
            }
            lock(user,"analysis-reading:"+reading);
            expire(user,reading);
            if(db.queryForObject("SELECT count(*) FROM tarot_analysis WHERE user_id=? AND reading_id=? AND status IN('pending','running')",Integer.class,user,reading)>0)
                throw new BusinessException(409,"这张牌的解读正在生成，请等待当前结果");
            if(!question.isBlank())model.checkConfigured(user);
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO tarot_analysis(id,user_id,reading_id,client_request_id,payload_hash,question,options,status,expires_at) VALUES(?,?,?,?,?,?,?::jsonb,'pending',now()+interval '30 seconds')",id,user,reading,input.clientRequestId(),payloadHash,question,write(input.options()));
            db.update("UPDATE tarot_reading SET latest_analysis_id=? WHERE id=? AND user_id=?",id,reading,user);
            return new Accepted(id,true);
        });
        if(accepted.created())try{executor.execute(()->run(user,accepted.id()));}catch(RuntimeException e){fail(user,accepted.id(),"QUEUE_FULL");}
        return get(user,accepted.id());
    }
    public AnalysisView get(UUID user,UUID id){
        db.update("UPDATE tarot_analysis SET status='failed',error_code='TIMEOUT',text=NULL,completed_at=now() WHERE user_id=? AND id=? AND status IN('pending','running') AND expires_at<=now()",user,id);
        var job=load(user,id);List<Source> hydrated=List.of();
        if(job.status().equals("completed")){
            hydrated=context.hydrate(user,job.refs());
            if(hydrated.size()!=job.refs().size()){
                db.update("UPDATE tarot_analysis SET status='stale',text=NULL,error_code='CONTEXT_CHANGED' WHERE id=? AND user_id=? AND status='completed'",id,user);
                job=load(user,id);hydrated=List.of();
            }
        }
        return new AnalysisView(id,job.reading(),job.status(),job.status().equals("completed")?job.text():null,hydrated,job.notices(),job.options(),job.question(),job.error(),2000);
    }
    private void run(UUID user,UUID id){
        if(db.update("UPDATE tarot_analysis SET status='running',started_at=now(),expires_at=now()+interval '90 seconds' WHERE id=? AND user_id=? AND status='pending' AND expires_at>now()",id,user)==0)return;
        try{
            var job=load(user,id);var reading=tarot.getReading(user,job.reading());var card=catalog.byId(reading.cardId());
            var bundle=context.build(user,job.question(),job.options(),Instant.now());
            String text;List<String> used;var notices=new ArrayList<>(bundle.notices());
            if(job.question().isBlank()){text=card.meaning()+"\n"+card.action();used=List.of();notices.add("通用牌义，未调用 AI。");}
            else{
                var request=TarotProto.TarotAnalyzeRequest.newBuilder().setReadingId(reading.readingId().toString()).setAnalysisId(id.toString())
                    .setQuestion(job.question()).setReferenceDate(LocalDate.now(TarotServiceImpl.ZONE).toString()).setTimezone(TarotServiceImpl.ZONE.getId())
                    .setCard(TarotProto.TarotCard.newBuilder().setId(card.id()).setName(card.name()).setMeaning(card.meaning()).setAction(card.action()));
                for(var s:bundle.materials())request.addMaterials(TarotProto.TarotMaterial.newBuilder().setRef(s.ref()).setKind(s.kind()).setSourceId(s.sourceId()).setTitle(s.title()).setDate(s.date()).setExcerpt(s.excerpt()).setVersion(s.version()));
                var answer=model.analyze(user,request.build());text=answer.getText();used=answer.getUsedRefsList();
                validateAnswer(text,used,bundle.materials());
            }
            if(!context.validate(user,bundle.materials())){stale(user,id);return;}
            var refs=bundle.materials().stream().filter(s->used.contains(s.ref())).map(s->new Source(s.ref(),s.kind(),s.sourceId(),"","","",s.version())).toList();
            db.update("UPDATE tarot_analysis SET status='completed',text=?,source_refs=?::jsonb,notices=?::jsonb,completed_at=now() WHERE id=? AND user_id=? AND status='running' AND expires_at>now()",text,write(refs),write(notices),id,user);
        }catch(Exception e){fail(user,id,e instanceof io.grpc.StatusRuntimeException rpc&&rpc.getStatus().getCode()==io.grpc.Status.Code.DEADLINE_EXCEEDED?"TIMEOUT":"MODEL_ERROR");}
    }
    private static void validateAnswer(String text,List<String> used,List<Source> sources){
        if(text==null||text.isBlank()||text.codePointCount(0,text.length())>600)throw new IllegalArgumentException("Invalid analysis");
        Set<String> actual=new HashSet<>();var matcher=Pattern.compile("\\[([DTM]\\d+)\\]").matcher(text);while(matcher.find())actual.add(matcher.group(1));
        Set<String> allowed=new HashSet<>();sources.forEach(s->allowed.add(s.ref()));
        if(!actual.equals(new HashSet<>(used))||!allowed.containsAll(actual))throw new IllegalArgumentException("Invalid source reference");
    }
    private Job load(UUID user,UUID id){
        var rows=db.query("SELECT * FROM tarot_analysis WHERE user_id=? AND id=?",(r,n)->new Job(id,user,r.getObject("reading_id",UUID.class),r.getString("payload_hash"),r.getString("question"),read(r.getString("options"),ReferenceOptions.class),r.getString("status"),r.getString("text"),readSources(r.getString("source_refs")),readStrings(r.getString("notices")),r.getString("error_code")),user,id);
        if(rows.isEmpty())throw new BusinessException(404,"没有找到这次解读");return rows.get(0);
    }
    private void lock(UUID user,String key){db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->0,user+":"+key);}
    private void expire(UUID user,UUID reading){db.update("UPDATE tarot_analysis SET status='failed',error_code='TIMEOUT',text=NULL,completed_at=now() WHERE user_id=? AND reading_id=? AND status IN('pending','running') AND expires_at<=now()",user,reading);}
    private void fail(UUID user,UUID id,String reason){db.update("UPDATE tarot_analysis SET status='failed',text=NULL,error_code=?,completed_at=now() WHERE user_id=? AND id=? AND status IN('pending','running')",reason,user,id);}
    private void stale(UUID user,UUID id){db.update("UPDATE tarot_analysis SET status='stale',text=NULL,error_code='CONTEXT_CHANGED',completed_at=now() WHERE user_id=? AND id=? AND status='running'",user,id);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<Source> readSources(String value){try{return json.readValue(value,new TypeReference<List<Source>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> readStrings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}
