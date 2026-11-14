package org.xianshen.mumirrorb.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** All sums and filters execute on the server over persistent receipts, independently of pagination. */
@Service
public class UsageQueries {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    public UsageQueries(JdbcTemplate db) { this.db=db; }
    private record Selection(String cte,List<Object> args) { }
    private Selection select(UUID user,String period,String from,String to,String scene,String provider,String model,UUID task) {
        List<Object> args=new ArrayList<>();args.add(user);
        StringBuilder where=new StringBuilder(" WHERE i.user_id=?");
        try {
            if(from!=null||to!=null) {
                if(from==null||to==null) throw new IllegalArgumentException();
                LocalDate start=LocalDate.parse(from),end=LocalDate.parse(to);
                if(end.isBefore(start)) throw new IllegalArgumentException();
                where.append(" AND i.started_at>=? AND i.started_at<?");
                args.add(start.atStartOfDay(ZONE).toOffsetDateTime());args.add(end.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
            } else if("today".equals(period)||"month".equals(period)) {
                LocalDate today=LocalDate.now(ZONE),start="month".equals(period)?today.withDayOfMonth(1):today;
                where.append(" AND i.started_at>=? AND i.started_at<?");
                args.add(start.atStartOfDay(ZONE).toOffsetDateTime());
                args.add(("month".equals(period)?start.plusMonths(1):start.plusDays(1)).atStartOfDay(ZONE).toOffsetDateTime());
            } else if(period!=null&&!"all".equals(period)) throw new IllegalArgumentException();
        } catch(Exception invalid) { throw new BusinessException(400,"日期范围或周期无效"); }
        if(scene!=null&&!scene.isBlank()) {where.append(" AND t.scene=?");args.add(scene);}
        if(provider!=null&&!provider.isBlank()) {
            where.append(" AND (i.provider=? OR EXISTS(SELECT 1 FROM ai_usage_attempts p WHERE p.invocation_id=i.id AND p.provider=?))");
            args.add(provider);args.add(provider);
        }
        if(model!=null&&!model.isBlank()) {
            where.append(" AND (i.model=? OR EXISTS(SELECT 1 FROM ai_usage_attempts m WHERE m.invocation_id=i.id AND m.model=?))");
            args.add(model);args.add(model);
        }
        if(task!=null) {where.append(" AND i.task_id=?");args.add(task);}
        return new Selection("""
            WITH selected AS (SELECT i.* FROM ai_usage_invocations i JOIN ai_usage_tasks t ON t.id=i.task_id
            """+where+"""
            ), counted AS (SELECT i.*,a.* FROM selected i LEFT JOIN LATERAL (
              SELECT count(*) AS requests,count(*) FILTER(WHERE usage_state<>'complete') AS unknowns,
              COALESCE(sum(input_tokens),0) AS inputs,COALESCE(sum(output_tokens),0) AS outputs,
              COALESCE(sum(total_tokens),0) AS tokens FROM ai_usage_attempts a WHERE a.invocation_id=i.id
            ) a ON true)
            """,args);
    }
    private static final String SUMS="""
        COALESCE(sum(inputs),0) AS "inputTokens",COALESCE(sum(outputs),0) AS "outputTokens",
        COALESCE(sum(tokens),0) AS "totalTokens",COALESCE(sum(requests),0) AS "requestCount",
        COALESCE(sum(unknowns),0) AS "unknownCount",count(*) FILTER(WHERE coverage='incomplete') AS "incompleteCount",
        count(*) FILTER(WHERE status='pending') AS "activeCount"
        """;
    public Map<String,Object> summary(UUID user,String period,String from,String to,String scene,String provider,String model) {
        Selection s=select(user,period,from,to,scene,provider,model,null);
        Map<String,Object> result=new LinkedHashMap<>(db.queryForMap(s.cte()+"SELECT "+SUMS+",count(DISTINCT task_id) AS \"taskCount\" FROM counted",s.args().toArray()));
        result.put("credits",credits(s,null));
        result.put("since",time(db.queryForObject("SELECT min(started_at) FROM ai_usage_invocations WHERE user_id=?",Timestamp.class,user)));
        result.put("timezone",ZONE.getId());return result;
    }
    private String taskSql(Selection s) {
        return s.cte()+"""
            , grouped AS (SELECT task_id,
            """+SUMS+"""
            ,bool_and(status='cancelled') AS cancelled,bool_and(status='error') AS failed,
            bool_or(status<>'success') AS has_errors FROM counted GROUP BY task_id)
            SELECT t.id,t.scene,t.started_at AS "startedAt",t.completed_at AS "completedAt",t.updated_at AS "updatedAt",
            g."inputTokens",g."outputTokens",g."totalTokens",g."requestCount",g."unknownCount",g."incompleteCount",g."activeCount",
            CASE WHEN NOT t.sealed OR g."activeCount">0 THEN 'running'
                 WHEN g.cancelled THEN 'cancelled' WHEN g.failed THEN 'error'
                 WHEN g."unknownCount">0 OR g."incompleteCount">0 OR g.has_errors THEN 'partial' ELSE 'complete' END AS status,
            latest.provider,latest.model
            FROM grouped g JOIN ai_usage_tasks t ON t.id=g.task_id LEFT JOIN LATERAL (
              SELECT COALESCE(NULLIF(a.provider,''),i.provider) AS provider,COALESCE(NULLIF(a.model,''),i.model) AS model
              FROM selected i LEFT JOIN ai_usage_attempts a ON a.invocation_id=i.id
              WHERE i.task_id=t.id ORDER BY i.started_at DESC,i.id DESC,a.ordinal DESC LIMIT 1
            ) latest ON true
            """;
    }
    public Map<String,Object> tasks(UUID user,int limit,String before,String scene,String provider,String model,String from,String to) {
        return tasks(user,limit,before,scene,provider,model,from,to,"all");
    }
    public Map<String,Object> tasks(UUID user,int limit,String before,String scene,String provider,String model,String from,String to,String period) {
        Selection s=select(user,period,from,to,scene,provider,model,null);
        List<Object> args=new ArrayList<>(s.args());String suffix="";
        if(before!=null&&!before.isBlank()) {
            try {
                String[] cursor=new String(Base64.getUrlDecoder().decode(before),StandardCharsets.UTF_8).split("\\|",-1);
                if(cursor.length!=2) throw new IllegalArgumentException();
                OffsetDateTime updated=OffsetDateTime.parse(cursor[0]);UUID id=UUID.fromString(cursor[1]);
                suffix=" WHERE (t.updated_at,t.id)<(?,?)";args.add(updated);args.add(id);
            } catch(Exception invalid) {throw new BusinessException(400,"分页游标无效");}
        }
        int size=Math.max(1,Math.min(100,limit));args.add(size+1);
        List<Map<String,Object>> rows=db.queryForList(taskSql(s)+suffix+" ORDER BY t.updated_at DESC,t.id DESC LIMIT ?",args.toArray());
        boolean more=rows.size()>size;if(more) rows=new ArrayList<>(rows.subList(0,size));
        for(var row:rows) enrich(row,s);
        String next=null;
        if(more&&!rows.isEmpty()) {
            var last=rows.get(rows.size()-1);
            next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("updatedAt")+"|"+last.get("id")).getBytes(StandardCharsets.UTF_8));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("nextCursor",next);return result;
    }
    public Map<String,Object> detail(UUID user,UUID task) {
        Selection s=select(user,"all",null,null,null,null,null,task);
        var rows=db.queryForList(taskSql(s),s.args().toArray());if(rows.isEmpty()) return null;
        Map<String,Object> row=rows.get(0);enrich(row,s);
        var attempts=db.queryForList("""
            SELECT a.id,a.invocation_id AS "invocationId",i.scene,a.status,a.provider,a.model,
              a.input_tokens AS "inputTokens",a.output_tokens AS "outputTokens",a.total_tokens AS "totalTokens",
              a.credits,a.credit_unit AS "creditUnit",a.usage_state AS "usageState",a.latency_ms AS "latencyMs",
              a.http_status AS "httpStatus",a.cache_read_tokens AS "cacheReadTokens",
              a.cache_creation_tokens AS "cacheCreationTokens",a.reasoning_tokens AS "reasoningTokens",
              i.channel_key AS "channelKey",i.channel_name AS "channelName",i.started_at AS "startedAt"
            FROM ai_usage_invocations i JOIN ai_usage_attempts a ON a.invocation_id=i.id
            WHERE i.user_id=? AND a.user_id=? AND i.task_id=? ORDER BY i.started_at,i.id,a.ordinal
            """,user,user,task);
        for(var a:attempts) {if(a.get("credits") instanceof BigDecimal credit)a.put("credits",decimal(credit));a.put("startedAt",time(a.get("startedAt")));}
        row.put("attempts",attempts);
        row.put("invocations",db.queryForList("""
            SELECT id,scene,status,coverage,provider,model,channel_key AS "channelKey",channel_name AS "channelName"
            FROM ai_usage_invocations WHERE user_id=? AND task_id=? ORDER BY started_at,id
            """,user,task));
        return row;
    }
    private void enrich(Map<String,Object> row,Selection selection) {
        row.put("label",label((String)row.get("scene")));
        for(String key:List.of("startedAt","completedAt","updatedAt")) row.put(key,time(row.get(key)));
        row.put("credits",credits(selection,(UUID)row.get("id")));
    }
    private List<Map<String,Object>> credits(Selection s,UUID task) {
        List<Object> args=new ArrayList<>(s.args());String condition="";
        if(task!=null) {condition=" AND i.task_id=?";args.add(task);}
        var credits=db.queryForList(s.cte()+"""
            SELECT i.channel_key AS "channelKey",min(i.channel_name) AS "channelName",a.credit_unit AS unit,sum(a.credits) AS amount
            FROM selected i JOIN ai_usage_attempts a ON a.invocation_id=i.id WHERE a.credits IS NOT NULL
            """+condition+" GROUP BY i.channel_key,a.credit_unit ORDER BY i.channel_key,a.credit_unit",args.toArray());
        for(var row:credits) row.put("amount",decimal((BigDecimal)row.get("amount")));return credits;
    }
    private static String decimal(BigDecimal value) {return value.stripTrailingZeros().toPlainString();}
    private static String time(Object value) {
        if(value==null)return null;
        if(value instanceof Timestamp t)return t.toInstant().atOffset(ZoneOffset.UTC).toString();
        return value.toString();
    }
    static String label(String scene) {
        return switch(scene) {
            case "record_analysis"->"随记整理";case "chat"->"对话";case "planning"->"对话规划";case "intent"->"意图识别";
            case "glossary"->"词典提取";case "profile"->"镜子画像";case "summary"->"总结";case "chat_memory"->"聊天记忆";
            case "tarot"->"塔罗解读";case "receipt"->"凭证识别";case "model_test"->"模型连接测试";case "shadow"->"影子验证";
            default->"AI 调用";
        };
    }
}
