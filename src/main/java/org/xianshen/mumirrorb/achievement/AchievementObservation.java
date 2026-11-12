package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Only observations with a currently accessible, matching business source are accepted. */
final class AchievementObservation {
    private static final Set<String> TAROT_CARDS=tarotCards();
    private final JdbcTemplate db;
    AchievementObservation(JdbcTemplate db) { this.db=db; }

    void report(UUID user,String kind,String subject,String other,String value,String context) {
        if (kind==null || subject==null || subject.isBlank() || subject.length()>200
                || (other!=null && other.length()>200) || (context!=null && context.length()>100)
                || (value!=null && value.length()>200)) throw invalid();
        String related=other==null?"":other, source=context==null?"":context, detail=value==null?"":value;
        switch (kind) {
            case "summary.opened" -> { summary(user,subject); legacy(user,kind,subject); }
            case "session.opened" -> {
                if (!exists("""
                    SELECT count(*) FROM chat_sessions s WHERE s.id=? AND s.user_id=?
                    AND (s.created_at AT TIME ZONE 'Asia/Shanghai')::date<(now() AT TIME ZONE 'Asia/Shanghai')::date
                    AND EXISTS(SELECT 1 FROM conversation_history h WHERE h.session_id=s.id AND h.user_id=s.user_id
                      AND h.role='assistant' AND NOT h.is_fallback AND btrim(h.content)<>'')
                    """,uuid(subject),user)) throw invalid();
                legacy(user,kind,subject);
            }
            case "calendar.opened" -> {
                record(user,subject);
                if (!exists("SELECT count(*) FROM records WHERE id=? AND created_at<=now()-interval '30 days'",number(subject))) throw invalid();
                legacy(user,kind,subject);
            }
            case "calendar.viewed" -> {
                record(user,subject);
                if (!Set.of("month","date").contains(detail)) throw invalid();
                try { if ("month".equals(detail)) YearMonth.parse(related); else LocalDate.parse(related); }
                catch(Exception error) { throw invalid(); }
                String format="month".equals(detail)?"YYYY-MM":"YYYY-MM-DD";
                if (!exists("SELECT count(*) FROM records WHERE id=? AND to_char(created_at AT TIME ZONE 'Asia/Shanghai',?)=?",number(subject),format,related)) throw invalid();
                if (exists("SELECT count(*) FROM records WHERE id=? AND created_at<=now()-interval '30 days'",number(subject))) legacy(user,"calendar.opened",subject);
            }
            case "record.source_opened", "vault.search_opened" -> {
                boolean vault="vault.search_opened".equals(kind);
                if (vault) vault(user,subject,"confirmed"); else record(user,subject);
                var rows=db.queryForList("""
                    SELECT h.session_id::text AS session_id FROM conversation_history h
                    WHERE h.id=? AND h.user_id=? AND h.role='assistant' AND NOT h.is_fallback AND btrim(h.content)<>''
                    AND (EXISTS(SELECT 1 FROM agent_runs a WHERE a.assistant_message_id=h.id AND a.user_id=h.user_id
                      AND a.session_id=h.session_id AND a.mode='live' AND a.status='completed')
                      OR EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=h.user_id
                        AND a.kind='chat.reply_completed' AND a.subject_id=h.id::text AND a.related_id=h.session_id::text))
                    AND EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(%s)='array' THEN %s ELSE '[]'::jsonb END) ref
                      WHERE coalesce(ref->>'%s',ref->>'%s')=?)
                    """.formatted(vault?"h.vault_refs":"h.sources",vault?"h.vault_refs":"h.sources",
                        vault?"vault_item_id":"record_id",vault?"vaultItemId":"recordId"),number(source),user,subject);
                if (rows.isEmpty()) throw invalid();
                related=(String)rows.get(0).get("session_id");
                if (vault) legacy(user,kind,subject);
            }
            case "summary.source_opened" -> {
                record(user,subject); summary(user,source);
                if (!exists("""
                    SELECT count(*) FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id
                    WHERE r.user_id=? AND r.source='system' AND r.deleted_at IS NULL AND c.metadata->>'summaryDate'=?
                    AND coalesce(c.metadata->'sourceRecordIds','[]'::jsonb) @> to_jsonb(ARRAY[?]::bigint[])
                    """,user,source,number(subject))) throw invalid();
            }
            case "glossary.source_opened" -> {
                record(user,related);
                if (!exists("SELECT count(*) FROM user_terms WHERE id=? AND user_id=? AND source_record_id=? AND status='confirmed'",number(subject),user,number(related))) throw invalid();
            }
            case "todo.source_opened" -> {
                record(user,related);
                if (!exists("""
                    SELECT count(*) FROM todo_registry t JOIN todo_registry_links l ON l.todo_id=t.id
                    JOIN chunks c ON c.id=l.chunk_id AND c.user_id=t.user_id
                    WHERE t.id=? AND t.user_id=? AND t.deleted_at IS NULL AND c.record_id=?
                    """,number(subject),user,number(related))) throw invalid();
            }
            case "snapshot.opened" -> {
                if (!Set.of("current","archive").contains(detail)) throw invalid();
                if (!exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=? AND (nullif(btrim(overall_summary),'') IS NOT NULL OR nullif(btrim(mood_analysis),'') IS NOT NULL OR nullif(btrim(learning_analysis),'') IS NOT NULL)",number(subject),user)) throw invalid();
                if ("archive".equals(detail) && !exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=? AND (NOT is_current OR snapshot_type='monthly' OR id<>(SELECT max(id) FROM profile_snapshots WHERE user_id=?))",number(subject),user,user)) throw invalid();
            }
            case "snapshot.compared" -> {
                long first=number(subject),second=number(related);
                if(first==second || !exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=?",first,user)
                        || !exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=?",second,user)) throw invalid();
                subject=String.valueOf(Math.min(first,second)); related=String.valueOf(Math.max(first,second));
                legacy(user,kind,subject+":"+related);
            }
            case "tarot.meaning_opened" -> {
                if (!TAROT_CARDS.contains(subject) || !Set.of("upright","reversed").contains(detail)
                        || !exists("SELECT count(*) FROM tarot_collection WHERE user_id=? AND card_id=?",user,subject)) throw invalid();
            }
            case "vault.previewed" -> vault(user,subject,"confirmed");
            case "vault.reviewed" -> {
                vault(user,subject,"extracted");
                if (!exists("SELECT count(*) FROM chunks WHERE vault_item_id=? AND user_id=? AND nullif(btrim(segment),'') IS NOT NULL",number(subject),user)) throw invalid();
            }
            case "record.filtered" -> {
                record(user,subject);
                if (!Set.of("mood","type","tag").contains(detail) || related.isBlank()) throw invalid();
                String expression=switch(detail) {
                    case "type" -> "c.metadata->>'contentType'=?";
                    case "mood" -> "(c.metadata->>'mood'=? OR (jsonb_typeof(c.metadata->'mood')='array' AND c.metadata->'mood' @> to_jsonb(ARRAY[?]::text[])))";
                    default -> "(coalesce(c.metadata->'userTags',c.metadata->'user_tags',c.metadata->'tags','[]'::jsonb) @> to_jsonb(ARRAY[?]::text[]))";
                };
                List<Object> args=new ArrayList<>(List.of(user,number(subject),related));
                if ("mood".equals(detail)) args.add(related);
                if (!exists("SELECT count(*) FROM chunks c WHERE c.user_id=? AND c.record_id=? AND "+expression,args.toArray())) throw invalid();
            }
            case "layout.viewed" -> { record(user,subject); if(!Set.of("desktop","mobile").contains(detail)) throw invalid(); }
            case "settings.section_viewed" -> {
                if (!Set.of("presentation","authorization","memory","data").contains(subject)) throw invalid();
                // Preserve navigation order, including a second attempt after visiting out of order.
                source=UUID.randomUUID().toString();
            }
            case "home.changed" -> {
                if (!Set.of("chat","records").contains(subject) || subject.equals(related)
                        || !Set.of("auto","chat","records").contains(related)) throw invalid();
                legacy(user,kind,subject);
                observe(user,"settings.saved","home","","",subject);
            }
            case "home.opened" -> {
                if (!Set.of("chat","records").contains(subject) || !latestPreference(user,"home",subject)) throw invalid();
                observe(user,"settings.used","home","","",subject);
            }
            case "preference.saved" -> {
                // The business-table trigger is the proof of an actual persisted change.
                if (!Set.of("record","memory","lookback").contains(subject) || !latestPreference(user,subject,detail)) throw invalid();
                return;
            }
            default -> throw new BusinessException(400,"不支持的行为类型");
        }
        observe(user,kind,subject,related,source,detail);
    }
    boolean latestPreference(UUID user,String kind,String value) {
        var rows=db.queryForList("SELECT value FROM user_achievement_activity WHERE user_id=? AND kind='settings.saved' AND subject_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1",String.class,user,kind);
        return !rows.isEmpty() && rows.get(0).equals(value);
    }
    void observe(UUID user,String kind,String subject,String related,String context,String value) {
        db.queryForObject("SELECT achievement_observe(?,?,?,?,?,?)",Object.class,user,kind,subject,related,context,value);
    }
    private void summary(UUID user,String day) {
        try { LocalDate.parse(day); } catch(Exception error) { throw invalid(); }
        if (!exists("SELECT count(*) FROM records r JOIN chunks c ON c.record_id=r.id AND c.user_id=r.user_id WHERE r.user_id=? AND r.source='system' AND r.deleted_at IS NULL AND c.metadata->>'summaryDate'=? AND nullif(btrim(c.segment),'') IS NOT NULL",user,day)) throw invalid();
    }
    private void record(UUID user,String id) {
        if (!exists("SELECT count(*) FROM records WHERE id=? AND user_id=? AND source='user' AND deleted_at IS NULL AND status IN('record_only','done')",number(id),user)) throw invalid();
    }
    private void vault(UUID user,String id,String status) {
        if (!exists("SELECT count(*) FROM vault_items WHERE id=? AND user_id=? AND deleted_at IS NULL AND digest_status=?",number(id),user,status)) throw invalid();
    }
    private void legacy(UUID user,String kind,String key) {
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(?,?,?) ON CONFLICT DO NOTHING",user,kind,key);
    }
    private boolean exists(String sql,Object... args) { return db.queryForObject(sql,Long.class,args)>0; }
    private static long number(String value) { try {long id=Long.parseLong(value);if(id>0)return id;}catch(Exception ignored){} throw invalid(); }
    private static UUID uuid(String value) { try{return UUID.fromString(value);}catch(Exception error){throw invalid();} }
    private static BusinessException invalid() {return new BusinessException(400,"该行为的来源不可用");}
    private static Set<String> tarotCards() {
        try {return new org.xianshen.mumirrorb.tarot.TarotCatalog(new com.fasterxml.jackson.databind.ObjectMapper())
                .activeCards().stream().map(card -> card.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        catch(java.io.IOException error) {throw new IllegalStateException("Tarot catalog unavailable",error);}
    }
}
