package org.xianshen.mumirrorb.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.service.TarotContextService;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

/** Separate source queries intentionally never use general vault-capable chat retrieval. */
@Service
public class TarotContextServiceImpl implements TarotContextService {
    private final JdbcTemplate db;
    public TarotContextServiceImpl(JdbcTemplate db){this.db=db;}
    private static final String DIARY="""
        SELECT r.id::text AS source_id, COALESCE(c.metadata->>'title','日记') AS title,
               r.created_at AS source_date, c.segment AS content,
               c.id::text || ':' || COALESCE(c.metadata::text,'') || ':' || c.segment AS fingerprint
        FROM chunks c JOIN records r ON r.id=c.record_id
        WHERE r.user_id=? AND c.user_id=r.user_id AND r.source='user' AND r.status='done'
          AND r.deleted_at IS NULL AND c.ai_memory_approved=TRUE AND record_memory_is_complete(r.id)
          AND c.segment IS NOT NULL AND c.segment<>''
        """;
    private static final String TODOS="""
        SELECT t.id::text AS source_id,t.title, t.created_at AS source_date,
               t.title || '：' || COALESCE(t.notes,'') || '；当前状态：' || t.current_status AS content,
               t.version::text || ':' || t.title || ':' || COALESCE(t.notes,'') || ':' || t.current_status AS fingerprint
        FROM todo_registry t LEFT JOIN chunks c ON c.id=t.source_chunk_id LEFT JOIN records r ON r.id=c.record_id
        WHERE t.user_id=? AND t.deleted_at IS NULL AND t.current_status IN('not_started','in_progress')
          AND (t.origin_kind='direct' OR (t.origin_kind='record' AND c.todo_approved=TRUE
               AND c.user_id=t.user_id AND r.user_id=t.user_id AND r.deleted_at IS NULL AND r.status='done'))
        """;
    private static final String MIRROR="""
        SELECT id::text AS source_id,
               CASE WHEN snapshot_type='monthly' THEN COALESCE(period_month,'') || ' 月度镜子' ELSE '已有镜子画像' END AS title,
               created_at AS source_date,
               concat_ws(E'\n',overall_summary,mood_analysis,learning_analysis,todo_analysis,rhythm_analysis) AS content,
               version_no::text || ':' || concat_ws(E'\n',overall_summary,mood_analysis,learning_analysis,todo_analysis,rhythm_analysis) AS fingerprint
        FROM profile_snapshots WHERE user_id=? AND invalidated=FALSE AND is_current=TRUE
        """;
    private RowMapper<Source> sourceMapper(String kind,int limit) {
        return (r,n)->{
            var date=r.getObject("source_date",OffsetDateTime.class);
            String label=date==null?"":date.toString(),title=r.getString("title"),content=r.getString("content");
            return new Source("",kind,r.getString("source_id"),title,label,truncate(content,limit),hash(kind+":"+r.getString("fingerprint")+":"+label+":"+title));
        };
    }
    public Bundle build(UUID user,String question,ReferenceOptions options,Instant now) {
        if(options==null||!options.valid())throw new BusinessException(400,"无效的参考资料设置");
        if(question==null||question.isBlank())return new Bundle(List.of(),List.of("未输入问题，本次仅提供通用牌义，没有读取个人资料。"));
        var sources=new ArrayList<Source>();var notices=new ArrayList<String>();
        if(options.diaryEnabled()) {
            var start=now.atZone(TarotServiceImpl.ZONE).toLocalDate().minusDays(options.diaryDays()-1).atStartOfDay(TarotServiceImpl.ZONE).toOffsetDateTime();
            var candidates=db.query(DIARY+" AND r.created_at>=? AND r.created_at<=? ORDER BY r.created_at DESC,c.id DESC LIMIT 100",sourceMapper("diary",500),user,start,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));
            var found=related(candidates,question,4); addRefs(sources,found,"D");
            if(found.isEmpty())notices.add("所选时间内没有匹配问题的已授权日记。");
        }
        if(options.todosEnabled()) {
            var candidates=db.query(TODOS+" ORDER BY t.created_at DESC,t.id DESC LIMIT 50",sourceMapper("todo",300),user);
            var found=related(candidates,question,3);addRefs(sources,found,"T");
            if(found.isEmpty())notices.add("没有匹配问题的当前未完成待办。");
        }
        if(options.mirrorEnabled()) {
            var found=db.query(MIRROR+" ORDER BY CASE WHEN snapshot_type='manual' THEN 0 ELSE 1 END,created_at DESC,id DESC LIMIT 1",sourceMapper("mirror",1000),user);
            addRefs(sources,found,"M");if(found.isEmpty())notices.add("还没有可用于本次解读的镜子画像，本次已跳过。");
        }
        if(options.diaryEnabled()||options.todosEnabled())notices.add("日记与待办按问题关键词匹配，不代表完整回顾。");
        return new Bundle(List.copyOf(sources),List.copyOf(notices));
    }
    public List<Source> hydrate(UUID user,List<Source> refs) {
        var hydrated=new ArrayList<Source>();
        for(var ref:refs) {
            List<Source> found=switch(ref.kind()) {
                case "diary" -> db.query(DIARY+" AND r.id::text=?",sourceMapper("diary",500),user,ref.sourceId());
                case "todo" -> db.query(TODOS+" AND t.id::text=?",sourceMapper("todo",300),user,ref.sourceId());
                case "mirror" -> db.query(MIRROR+" AND id::text=?",sourceMapper("mirror",1000),user,ref.sourceId());
                default -> List.of();
            };
            found.stream().filter(s->s.version().equals(ref.version())).findFirst().ifPresent(s->hydrated.add(withRef(s,ref.ref())));
        }
        return List.copyOf(hydrated);
    }
    public boolean validate(UUID user,List<Source> refs){return hydrate(user,refs).size()==refs.size();}
    private static void addRefs(List<Source> target,List<Source> found,String prefix){for(int i=0;i<found.size();i++)target.add(withRef(found.get(i),prefix+(i+1)));}
    private static Source withRef(Source s,String ref){return new Source(ref,s.kind(),s.sourceId(),s.title(),s.date(),s.excerpt(),s.version());}
    private static List<Source> related(List<Source> candidates,String question,int limit){
        var terms=terms(question);
        var ranked=candidates.stream().filter(s->score(s,terms)>0).sorted(Comparator.<Source>comparingInt(s->score(s,terms)).reversed()).toList();
        var unique=new LinkedHashMap<String,Source>();for(var s:ranked)unique.putIfAbsent(s.sourceId(),s);
        return unique.values().stream().limit(limit).toList();
    }
    private static int score(Source source,Set<String> terms){String text=(source.title()+" "+source.excerpt()).toLowerCase(Locale.ROOT);return terms.stream().filter(text::contains).mapToInt(t->t.length()).sum();}
    private static Set<String> terms(String question){
        var result=new HashSet<String>();String text=question.toLowerCase(Locale.ROOT);
        for(String token:text.split("[^\\p{L}\\p{N}]+")){if(token.length()>=2){result.add(token);for(int i=0;i+1<token.length();i++)if(Character.UnicodeScript.of(token.charAt(i))==Character.UnicodeScript.HAN)result.add(token.substring(i,i+2));}}
        result.removeAll(Set.of("我想","可以","怎么","什么","最近","今天","应该","一个","是否"));return result;
    }
    public static String truncate(String text,int max){if(text==null)return "";int length=text.codePointCount(0,text.length());return length<=max?text:text.substring(0,text.offsetByCodePoints(0,max));}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
