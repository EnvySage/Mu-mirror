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
        report(user,kind,subject,other,value,null);
    }

    @Transactional
    public void report(UUID user, String kind, String subject, String other, String value, String context) {
        new AchievementObservation(db).report(user,kind,subject,other,value,context);
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
        db.update("""
            INSERT INTO user_achievement_activity(user_id,kind,subject_id,context_id,day_key)
            SELECT s.user_id,'connection.verified','chat',achievement_connection_key(s.user_id,'chat'),
              (now() AT TIME ZONE 'Asia/Shanghai')::date
            FROM user_model_profiles p JOIN user_settings s ON s.user_id=p.user_id AND s.active_model_profile_id=p.id
            WHERE p.id=? AND p.user_id=? AND p.updated_at=?
              AND p.ai_provider IS NOT DISTINCT FROM s.ai_provider AND p.ai_protocol IS NOT DISTINCT FROM s.ai_protocol
              AND p.ai_model IS NOT DISTINCT FROM s.ai_model AND p.ai_api_key IS NOT DISTINCT FROM s.ai_api_key
              AND coalesce(p.ai_base_url,'')=coalesce(s.ai_base_url,'') ON CONFLICT DO NOTHING
            """,profile,user,testedRevision);
    }

    @Transactional
    public void glossaryEdited(UUID user,Long termId,boolean aliasesChanged,boolean descriptionChanged) {
        var rows=db.queryForList("SELECT aliases::text AS aliases,description FROM user_terms WHERE id=? AND user_id=?",termId,user);
        if(rows.isEmpty()) return;
        var observations=new AchievementObservation(db);
        if(aliasesChanged && db.queryForObject("SELECT jsonb_typeof(aliases)='array' AND jsonb_array_length(aliases)>0 FROM user_terms WHERE id=? AND user_id=?",Boolean.class,termId,user))
            observations.observe(user,"glossary.aliased",termId.toString(),"","","");
        if(descriptionChanged && !String.valueOf(rows.get(0).get("description")).isBlank())
            observations.observe(user,"glossary.described",termId.toString(),"","","");
    }

    @Transactional
    public void bindDraftEdit(UUID user,UUID actionId,UUID draftId,Long chunkId) {
        if(actionId==null || draftId==null || chunkId==null) return;
        db.update("""
            INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,context_id,day_key)
            SELECT c.user_id,'memory.edited',c.id::text,c.record_id::text,a.id::text,(now() AT TIME ZONE 'Asia/Shanghai')::date
            FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id
            JOIN assistant_actions a ON a.id=? AND a.user_id=c.user_id AND a.state='EXECUTING'
            WHERE c.id=? AND c.user_id=? AND r.source='user' AND r.deleted_at IS NULL AND c.vault_item_id IS NULL
            AND EXISTS(SELECT 1 FROM user_achievement_activity f WHERE f.user_id=c.user_id
              AND f.kind='memory.draft_edited' AND f.subject_id=a.id::text AND f.related_id=?) ON CONFLICT DO NOTHING
            """,actionId,chunkId,user,draftId.toString());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void exportGenerated(UUID user) { insert(user,"export.generated","first"); }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void exportGenerated(UUID user,String format) {
        if (!java.util.Set.of("json","markdown").contains(format)) throw new BusinessException(400,"导出格式无效");
        insert(user,"export.generated","first");
        new AchievementObservation(db).observe(user,"export.generated",format,"","","");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void connectionVerified(UUID user,String kind,OffsetDateTime testedRevision) {
        if (!java.util.Set.of("chat","embedding").contains(kind) || testedRevision==null)
            throw new BusinessException(400,"连接测试类型无效");
        db.update("""
            INSERT INTO user_achievement_activity(user_id,kind,subject_id,context_id,day_key)
            SELECT user_id,'connection.verified',?,achievement_connection_key(user_id,?),
              (now() AT TIME ZONE 'Asia/Shanghai')::date FROM user_settings
            WHERE user_id=? AND updated_at=? ON CONFLICT DO NOTHING
            """,kind,kind,user,testedRevision);
    }

    @Transactional
    public void preferenceUsed(UUID user,String kind,String value) {
        if (!"lookback".equals(kind) || !java.util.Set.of("0","1","2","3").contains(value))
            throw new BusinessException(400,"配置使用类型无效");
        var observations=new AchievementObservation(db);
        if (observations.latestPreference(user,kind,value)) observations.observe(user,"settings.used",kind,"","",value);
    }

    private void insert(UUID user,String kind,String key) {
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(?,?,?) ON CONFLICT DO NOTHING",user,kind,key);
    }
}
