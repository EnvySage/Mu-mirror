package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class AchievementService {
    public record Notification(String code,String kind) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AchievementCatalog catalog;
    private final AchievementMetrics metrics;

    public AchievementService(JdbcTemplate db, ObjectMapper json, AchievementCatalog catalog, AchievementMetrics metrics) {
        this.db = db; this.json = json; this.catalog = catalog; this.metrics = metrics;
    }

    @Transactional
    public void installCatalog() {
        Set<String> codes = new HashSet<>();
        for (JsonNode entry : catalog.definitions()) {
            String code = entry.get("code").asText();
            codes.add(code);
            db.update("""
                INSERT INTO achievement_definition(code,definition,rule_version,enabled) VALUES(?,?::jsonb,?,?)
                ON CONFLICT(code) DO UPDATE SET definition=excluded.definition,rule_version=excluded.rule_version,
                enabled=excluded.enabled,updated_at=now()
                """, code, entry.toString(), entry.get("ruleVersion").asInt(), entry.path("enabled").asBoolean(true));
        }
        for (String code : db.queryForList("SELECT code FROM achievement_definition WHERE enabled", String.class)) {
            if (!codes.contains(code)) db.update("UPDATE achievement_definition SET enabled=false WHERE code=?", code);
        }
        db.update("""
            INSERT INTO achievement_event(user_id,event_key,kind)
            SELECT id,?||id::text,'catalog.reconcile' FROM users ON CONFLICT DO NOTHING
            """, "catalog:" + catalog.revision() + ":");
    }

    private void lockUser(UUID user) {
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user).isEmpty())
            throw new BusinessException(401,"请重新登录");
    }

    @Transactional
    public Map<String, Long> reconcile(UUID user) {
        lockUser(user);
        Map<String,Long> values = metrics.values(user, catalog.metrics());
        for (JsonNode definition : catalog.definitions()) {
            if (definition.path("enabled").asBoolean(true) && AchievementRules.matches(definition.get("rule"), values)) {
                db.update("""
                    INSERT INTO user_achievement(user_id,code,rule_version) VALUES(?,?,?) ON CONFLICT DO NOTHING
                    """,user,definition.get("code").asText(),definition.get("ruleVersion").asInt());
            }
            String code=definition.path("code").asText();
            JsonNode plating=catalog.platingRule(code);
            if (definition.path("enabled").asBoolean(true) && plating!=null
                    && AchievementRules.matches(plating.get("rule"),values)) {
                // The higher challenge also grants the original medal, atomically.
                db.update("""
                    INSERT INTO user_achievement(user_id,code,rule_version,plated_at,plating_rule_version)
                    VALUES(?,?,?,now(),?) ON CONFLICT(user_id,code) DO UPDATE
                    SET plated_at=coalesce(user_achievement.plated_at,excluded.plated_at),
                      plating_rule_version=coalesce(user_achievement.plating_rule_version,excluded.plating_rule_version)
                    """,user,code,definition.path("ruleVersion").asInt(),plating.path("ruleVersion").asInt());
            }
        }
        return values;
    }

    @Transactional
    public Map<String,Object> collection(UUID user) {
        Map<String,Long> values = reconcile(user);
        Map<String,String> unlocked = new HashMap<>();
        Map<String,String> plated = new HashMap<>();
        db.query("SELECT code,unlocked_at,plated_at FROM user_achievement WHERE user_id=?", row -> {
            unlocked.put(row.getString("code"), row.getObject("unlocked_at", OffsetDateTime.class).toString());
            var at=row.getObject("plated_at",OffsetDateTime.class);
            if(at!=null) plated.put(row.getString("code"),at.toString());
        }, user);
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode definition : catalog.definitions()) {
            if (!definition.path("enabled").asBoolean(true)) continue;
            String code = definition.get("code").asText();
            boolean earned = unlocked.containsKey(code);
            ObjectNode item = catalog.display(definition, earned);
            item.put("earned", earned);
            item.put("plated",plated.containsKey(code));
            if(plated.containsKey(code)) item.put("platedAt",plated.get(code)); else item.putNull("platedAt");
            if (earned) item.put("unlockedAt",unlocked.get(code)); else item.putNull("unlockedAt");
            if (!definition.path("hidden").asBoolean() || earned) item.set("progress", progress(definition.get("rule"), values));
            items.add(item);
        }
        return Map.of("revision",catalog.revision(),"items",items);
    }

    private JsonNode progress(JsonNode rule, Map<String,Long> values) {
        ObjectNode out = json.createObjectNode();
        if (rule.has("metric")) {
            return out.put("current",values.getOrDefault(rule.get("metric").asText(),0L)).put("target",rule.get("value").asLong());
        }
        String op = rule.has("all") ? "all" : "any";
        ArrayNode children = out.putArray(op);
        rule.get(op).forEach(child -> children.add(progress(child, values)));
        return out;
    }

    @Transactional
    public List<Map<String,Object>> pending(UUID user) {
        reconcile(user);
        return db.queryForList("""
            SELECT n.code,n.kind,n.unlocked_at FROM (
              SELECT a.code,'earned' AS kind,a.unlocked_at FROM user_achievement a JOIN achievement_definition d USING(code)
              WHERE a.user_id=? AND a.acknowledged_at IS NULL AND d.enabled
              UNION ALL
              SELECT a.code,'plated' AS kind,a.plated_at AS unlocked_at FROM user_achievement a JOIN achievement_definition d USING(code)
              WHERE a.user_id=? AND a.plated_at IS NOT NULL AND a.plating_acknowledged_at IS NULL AND d.enabled
            ) n ORDER BY n.unlocked_at,n.code,n.kind LIMIT 50
            """, user,user);
    }

    @Transactional
    public void acknowledge(UUID user, List<String> codes) {
        if (codes == null || codes.size() > 50) throw new BusinessException(400,"确认列表无效");
        for (String code : new HashSet<>(codes)) {
            db.update("UPDATE user_achievement SET acknowledged_at=coalesce(acknowledged_at,now()) WHERE user_id=? AND code=?",user,code);
        }
    }

    @Transactional
    public void acknowledgeNotifications(UUID user,List<Notification> notifications) {
        if(notifications==null || notifications.size()>50 || notifications.stream().anyMatch(item -> item==null
                || item.code()==null || item.kind()==null || !Set.of("earned","plated").contains(item.kind())))
            throw new BusinessException(400,"确认列表无效");
        for(Notification item:new HashSet<>(notifications)) {
            String column="plated".equals(item.kind())?"plating_acknowledged_at":"acknowledged_at";
            String condition="plated".equals(item.kind())?" AND plated_at IS NOT NULL":"";
            db.update("UPDATE user_achievement SET "+column+"=coalesce("+column+",now()) WHERE user_id=? AND code=?"+condition,user,item.code());
        }
    }

    public Map<String,Object> showcase(UUID user) {
        var rows = db.queryForList("SELECT capacity,codes::text AS codes,version FROM user_achievement_showcase WHERE user_id=?",user);
        if (rows.isEmpty()) return Map.of("capacity",8,"codes",List.of(),"version",0);
        var row = rows.get(0);
        try { return Map.of("capacity",row.get("capacity"),"codes",json.readTree((String)row.get("codes")),"version",row.get("version")); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted showcase",error); }
    }

    @Transactional
    public Map<String,Object> saveShowcase(UUID user, int capacity, List<String> codes, long expectedVersion) {
        if ((capacity != 8 && capacity != 10) || codes == null || codes.size() > capacity
                || codes.stream().anyMatch(Objects::isNull) || new HashSet<>(codes).size() != codes.size() || expectedVersion < 0)
            throw new BusinessException(400,"展架容量或选择无效");
        lockUser(user);
        Set<String> earned = new HashSet<>(db.queryForList("SELECT code FROM user_achievement WHERE user_id=?",String.class,user));
        for (String code : codes) if (!catalog.publicCode(code) || !earned.contains(code))
            throw new BusinessException(400,"只能展示本人已获得且当前可展示的成就");
        db.update("INSERT INTO user_achievement_showcase(user_id) VALUES(?) ON CONFLICT DO NOTHING",user);
        long version = db.queryForObject("SELECT version FROM user_achievement_showcase WHERE user_id=? FOR UPDATE",Long.class,user);
        if (version != expectedVersion) throw new BusinessException(409,"展架已在其他页面更新，请刷新后再保存");
        db.update("UPDATE user_achievement_showcase SET capacity=?,codes=?::jsonb,version=version+1,updated_at=now() WHERE user_id=?",
                capacity,json.valueToTree(codes).toString(),user);
        if(codes.size()>=8) {
            long groups=codes.stream().map(code -> code.substring(0,code.indexOf('-'))).distinct().count();
            new AchievementObservation(db).observe(user,"showcase.saved",String.valueOf(codes.size()),"","",String.valueOf(groups));
        }
        return showcase(user);
    }
}
