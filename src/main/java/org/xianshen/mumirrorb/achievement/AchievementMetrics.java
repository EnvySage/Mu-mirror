package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.util.*;

@Component
public class AchievementMetrics {
    private final JdbcTemplate db;
    private final TarotCatalog tarot;
    private final Map<String, String> queries = new LinkedHashMap<>();
    private static final String CHAT = """
        WITH completed AS (
          SELECT subject_key,occurred_at FROM user_activity_fact WHERE user_id=? AND kind='chat.completed'
          UNION
          SELECT a.run_id::text,a.finished_at FROM agent_runs a JOIN conversation_history h ON h.id=a.assistant_message_id
          WHERE a.user_id=? AND a.mode='live' AND a.status='completed' AND NOT h.is_fallback AND btrim(h.content)<>''
          AND NOT EXISTS(SELECT 1 FROM user_activity_fact f WHERE f.user_id=a.user_id AND f.kind='chat.completed' AND f.subject_key=a.run_id::text)
        )
        """;

    public AchievementMetrics(JdbcTemplate db, TarotCatalog tarot) {
        this.db = db;
        this.tarot = tarot;
        String records = " FROM records WHERE user_id=? AND source='user' AND deleted_at IS NULL AND status IN('record_only','done')";
        queries.put("record.count", "SELECT count(*)" + records);
        queries.put("record.days", "SELECT count(DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date)" + records);
        queries.put("record.daily_max", "SELECT coalesce(max(total),0) FROM (SELECT count(*) AS total" + records
                + " GROUP BY (created_at AT TIME ZONE 'Asia/Shanghai')::date) days");
        queries.put("record.months", "SELECT count(DISTINCT date_trunc('month',created_at AT TIME ZONE 'Asia/Shanghai'))" + records);
        queries.put("record.year_quarters", "SELECT coalesce(max(total),0) FROM (SELECT count(DISTINCT extract(quarter FROM created_at AT TIME ZONE 'Asia/Shanghai')) AS total"
                + records + " GROUP BY extract(year FROM created_at AT TIME ZONE 'Asia/Shanghai')) years");
        queries.put("memory.ready", "SELECT count(*) FROM records WHERE user_id=? AND source='user' AND deleted_at IS NULL AND memory_state='READY'");
        queries.put("memory.approved_chunks", """
            SELECT count(*) FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id
            WHERE c.user_id=? AND c.ai_memory_approved AND c.embedding_status='ready' AND c.embedding IS NOT NULL
            AND c.vault_item_id IS NULL AND nullif(btrim(c.segment),'') IS NOT NULL AND record_memory_is_complete(r.id)
            AND r.source='user' AND r.deleted_at IS NULL AND r.memory_state='READY' AND r.status='done'
            """);
        queries.put("chat.models", "SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='chat.model_used'");
        queries.put("glossary.count", "SELECT count(*) FROM user_terms WHERE user_id=? AND status='confirmed'");
        queries.put("glossary.hits", "SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='glossary.used'");
        queries.put("todo.count", "SELECT count(*) FROM todo_registry WHERE user_id=? AND deleted_at IS NULL AND (origin_kind='direct' OR source_chunk_id IS NOT NULL)");
        queries.put("todo.completed_weeks", """
            SELECT count(DISTINCT date_trunc('week',occurred_at AT TIME ZONE 'Asia/Shanghai'))
            FROM user_activity_fact WHERE user_id=? AND kind='todo.completed'
            """);
        queries.put("action.succeeded", "SELECT count(*) FROM assistant_actions WHERE user_id=? AND state='SUCCEEDED' AND result->>'outcome'='APPLIED'");
        queries.put("profile.count", "SELECT count(*) FROM profile_snapshots WHERE user_id=?");
        queries.put("profile.months", "SELECT count(DISTINCT period_month) FROM profile_snapshots WHERE user_id=? AND snapshot_type='monthly'");
        queries.put("tarot.days", "SELECT count(DISTINCT draw_date) FROM tarot_daily_draw WHERE user_id=?");
        queries.put("vault.count", "SELECT count(*) FROM vault_items WHERE user_id=? AND deleted_at IS NULL AND digest_status='confirmed'");
        queries.put("growth.count", "SELECT count(*) FROM growth_entries WHERE user_id=? AND points>0");
        queries.put("growth.axes", "SELECT count(DISTINCT dimension_id) FROM growth_entries WHERE user_id=? AND points>0");
        queries.put("growth.axis_days", """
            SELECT coalesce(max(total),0) FROM (
              SELECT count(DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date) AS total
              FROM growth_entries WHERE user_id=? AND points>0 GROUP BY dimension_id
            ) axes
            """);
        queries.put("growth.months", """
            SELECT count(DISTINCT date_trunc('month',created_at AT TIME ZONE 'Asia/Shanghai'))
            FROM growth_entries WHERE user_id=? AND points>0
            """);
        queries.put("model.verified_active", """
            SELECT count(*) FROM user_activity_fact a
            JOIN user_activity_fact v ON v.user_id=a.user_id AND v.kind='model.verified' AND v.subject_key=a.subject_key
            WHERE a.user_id=? AND a.kind='model.activated'
            """);
        String chunks = " FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id WHERE c.user_id=? AND c.ai_memory_approved AND r.deleted_at IS NULL AND r.memory_state='READY' AND r.status='done'";
        queries.put("record.types", "SELECT count(DISTINCT nullif(c.metadata->>'contentType',''))" + chunks);
        queries.put("record.moods", """
            SELECT count(DISTINCT mood.value) FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id
            CROSS JOIN LATERAL jsonb_array_elements_text(
              CASE jsonb_typeof(c.metadata->'mood') WHEN 'array' THEN c.metadata->'mood'
              WHEN 'string' THEN jsonb_build_array(c.metadata->'mood') ELSE '[]'::jsonb END) mood(value)
            WHERE c.user_id=? AND c.ai_memory_approved AND r.deleted_at IS NULL AND r.memory_state='READY'
            AND r.status='done' AND nullif(mood.value,'') IS NOT NULL
            """);
    }

    public boolean supports(String metric) {
        return queries.containsKey(metric) || Set.of("chat.count","chat.days","tarot.major","tarot.cards","tarot.repeat_max").contains(metric)
                || Set.of("session.opened","summary.opened","calendar.opened","snapshot.compared","vault.search_opened",
                    "todo.completed","growth.customized","export.generated","home.changed").stream().anyMatch(kind -> metric.equals("activity." + kind));
    }

    public Map<String, Long> values(UUID user, Set<String> required) {
        Map<String, Long> values = new HashMap<>();
        for (String metric : required) {
            if (!supports(metric)) throw new IllegalArgumentException("Unknown metric: " + metric);
            long value;
            if (metric.startsWith("activity.")) {
                value = db.queryForObject("SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind=?", Long.class, user, metric.substring(9));
            } else if (metric.equals("chat.count") || metric.equals("chat.days")) {
                String aggregate = metric.equals("chat.count") ? "count(*)" : "count(DISTINCT (occurred_at AT TIME ZONE 'Asia/Shanghai')::date)";
                value = db.queryForObject(CHAT + "SELECT " + aggregate + " FROM completed", Long.class, user, user);
            } else if (Set.of("tarot.major","tarot.cards","tarot.repeat_max").contains(metric)) {
                Set<String> eligible = new HashSet<>();
                tarot.activeCards().stream().filter(card -> !metric.equals("tarot.major") || "major".equals(card.category()))
                        .forEach(card -> eligible.add(card.id()));
                if (metric.equals("tarot.repeat_max")) {
                    value = db.queryForList("SELECT card_id,daily_draw_count FROM tarot_collection WHERE user_id=?", user).stream()
                            .filter(row -> eligible.contains(row.get("card_id")))
                            .mapToLong(row -> ((Number) row.get("daily_draw_count")).longValue()).max().orElse(0);
                } else {
                    value = db.queryForList("SELECT card_id FROM tarot_collection WHERE user_id=?", String.class, user).stream()
                            .filter(eligible::contains).distinct().count();
                }
            } else {
                value = db.queryForObject(queries.get(metric), Long.class, user);
            }
            values.put(metric, value);
        }
        return values;
    }
}
