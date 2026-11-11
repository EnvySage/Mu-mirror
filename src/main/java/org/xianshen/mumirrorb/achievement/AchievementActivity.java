package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AchievementActivity {
    private final JdbcTemplate db;
    public AchievementActivity(JdbcTemplate db) { this.db=db; }

    @Transactional
    public void report(UUID user, String kind, String subject, String other, String value) {
        if (kind==null || subject==null || subject.length()>100 || (other!=null && other.length()>100))
            throw new BusinessException(400,"行为参数无效");
        String canonical;
        switch (kind) {
            case "summary.opened" -> {
                try { java.time.LocalDate.parse(subject); } catch (Exception error) { throw inaccessible(); }
                if (!exists("""
                    SELECT count(*) FROM records r JOIN chunks c ON c.record_id=r.id AND c.user_id=r.user_id
                    WHERE r.user_id=? AND r.source='system' AND r.deleted_at IS NULL
                    AND c.metadata->>'summaryDate'=?
                    """,user,subject)) throw inaccessible();
                canonical=subject;
            }
            case "session.opened" -> {
                UUID id=uuid(subject);
                if (!exists("""
                    SELECT count(*) FROM chat_sessions s WHERE s.id=? AND s.user_id=?
                    AND (s.created_at AT TIME ZONE 'Asia/Shanghai')::date < (now() AT TIME ZONE 'Asia/Shanghai')::date
                    AND EXISTS(SELECT 1 FROM conversation_history h WHERE h.session_id=s.id AND h.role='assistant' AND NOT h.is_fallback)
                    """,id,user)) throw inaccessible();
                canonical=id.toString();
            }
            case "calendar.opened" -> {
                Long id=number(subject);
                if (!exists("SELECT count(*) FROM records WHERE id=? AND user_id=? AND deleted_at IS NULL AND source='user' AND created_at<=now()-interval '30 days'",id,user)) throw inaccessible();
                canonical=id.toString();
            }
            case "snapshot.compared" -> {
                Long first=number(subject), second=number(other);
                if (first.equals(second) || !exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=?",first,user)
                        || !exists("SELECT count(*) FROM profile_snapshots WHERE id=? AND user_id=?",second,user)) throw inaccessible();
                canonical=Math.min(first,second)+":"+Math.max(first,second);
            }
            case "vault.search_opened" -> {
                Long id=number(subject);
                if (value==null || value.isBlank() || value.length()>200
                        || !exists("SELECT count(*) FROM vault_items WHERE id=? AND user_id=? AND deleted_at IS NULL AND digest_status='confirmed'",id,user)) throw inaccessible();
                canonical=id.toString();
            }
            case "home.changed" -> {
                if (!"chat".equals(subject) && !"records".equals(subject)) throw inaccessible();
                canonical=subject;
            }
            default -> throw new BusinessException(400,"不支持的行为类型");
        }
        insert(user,kind,canonical);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void glossaryMatched(UUID user, String question) {
        // Legacy lookup occurs before reply completion. Associate it with the accepted run;
        // only a later successful, non-fallback reply can turn this into glossary.used.
        db.update("""
            INSERT INTO user_activity_fact(user_id,kind,subject_key)
            SELECT a.user_id,'glossary.matched',a.run_id::text FROM agent_runs a
            JOIN conversation_history h ON h.id=a.user_message_id AND h.user_id=a.user_id
            WHERE a.user_id=? AND a.mode='live' AND a.status='running' AND h.role='user' AND btrim(h.content)=?
            ON CONFLICT DO NOTHING
            """, user, question.trim());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void verifiedModel(UUID user, UUID profile, OffsetDateTime testedRevision) {
        db.update("""
            INSERT INTO user_activity_fact(user_id,kind,subject_key)
            SELECT user_id,'model.verified',achievement_profile_key(id) FROM user_model_profiles
            WHERE id=? AND user_id=? AND updated_at=? ON CONFLICT DO NOTHING
            """,profile,user,testedRevision);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void exportGenerated(UUID user) {
        insert(user,"export.generated","first");
    }
    private void insert(UUID user,String kind,String key) {
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(?,?,?) ON CONFLICT DO NOTHING",user,kind,key);
    }
    private boolean exists(String query,Object... args) { return db.queryForObject(query,Long.class,args)>0; }
    private static Long number(String value) {
        try { long id=Long.parseLong(value); if(id>0)return id; } catch (Exception ignored) {}
        throw inaccessible();
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch(Exception error) { throw inaccessible(); }
    }
    private static BusinessException inaccessible() { return new BusinessException(400,"该行为的来源不可用"); }
}
