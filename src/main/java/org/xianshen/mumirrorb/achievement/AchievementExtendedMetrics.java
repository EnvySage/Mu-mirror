package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/** Shared projection for exploration and plating. No challenge metadata leaves the server. */
final class AchievementExtendedMetrics {
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;
    private final Map<String,String> queries=new LinkedHashMap<>();
    private static final Set<String> COMPUTED=Set.of("x.model.roundtrip","x.record.edited_streak","x.memory.mood_streak",
        "x.growth.axis_days_weeks","x.settings.section_sequence","x.settings.configured_use_days",
        "x.connection.kinds","x.chat.memory","x.chat.memory_configured","x.chat.model_days","x.calendar.month_turns",
        "x.calendar.dates","x.summary.nextday","x.snapshot.archives","x.export.formats","x.settings.record",
        "x.settings.memory","x.settings.lookback","x.settings.types","x.layout.modes","x.showcase.eight",
        "x.showcase.ten_groups","x.filter.mood","x.filter.type","x.filter.tag_created_before_use","x.tarot.meanings",
        "x.tarot.both_sides","x.vault.open_items","x.vault.open_days","x.vault.multiple_session",
        "x.chat.multisource_sessions","x.chat.multisource_days","x.chat.memory_opened_days","x.chat.opened_records",
        "x.chat.opened_models","x.summary.linked_days");
    private static final Set<String> ACTIVITY_KINDS=Set.of("record.edited","record.source_opened","glossary.aliased",
        "glossary.source_opened","todo.created","todo.edited","todo.source_opened","snapshot.opened","vault.previewed","growth.named");
    private static final String READY="""
         FROM chunks c JOIN records r ON r.id=c.record_id AND r.user_id=c.user_id
         WHERE c.user_id=:user AND c.ai_memory_approved AND c.embedding_status='ready' AND c.embedding IS NOT NULL
         AND c.vault_item_id IS NULL AND nullif(btrim(c.segment),'') IS NOT NULL AND record_memory_is_complete(r.id)
         AND r.source='user' AND r.deleted_at IS NULL AND r.memory_state='READY' AND r.status='done'
        """;
    private static final String MOOD="""
         AND CASE jsonb_typeof(c.metadata->'mood') WHEN 'array' THEN jsonb_array_length(c.metadata->'mood')>0
             WHEN 'string' THEN nullif(btrim(c.metadata->>'mood'),'') IS NOT NULL ELSE false END
        """;

    AchievementExtendedMetrics(JdbcTemplate db) {
        this.db=db; named=new NamedParameterJdbcTemplate(db);
        queries.put("x.model.backup","""
            SELECT count(*) FROM user_model_profiles p JOIN user_settings s ON s.user_id=p.user_id
            WHERE p.user_id=:user AND s.active_model_profile_id IS NOT NULL AND p.id<>s.active_model_profile_id
            AND EXISTS(SELECT 1 FROM user_activity_fact f WHERE f.user_id=p.user_id AND f.kind='model.verified'
              AND f.subject_key=achievement_profile_key(p.id))
            """);
        queries.put("x.memory.reviewed_ready","SELECT count(DISTINCT c.id)"+READY+"""
             AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=c.user_id AND a.kind='memory.reviewed'
                 AND a.subject_id=c.id::text AND a.related_id=r.id::text)
            """);
        queries.put("x.memory.ready_months","SELECT count(DISTINCT date_trunc('month',r.created_at AT TIME ZONE 'Asia/Shanghai'))"+READY);
        queries.put("x.memory.mood_months","SELECT count(DISTINCT date_trunc('month',r.created_at AT TIME ZONE 'Asia/Shanghai'))"+READY+MOOD);
        queries.put("x.vault.reviewed_confirmed","""
            SELECT count(DISTINCT v.id) FROM vault_items v WHERE v.user_id=:user AND v.deleted_at IS NULL AND v.digest_status='confirmed'
            AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=v.user_id AND a.kind='vault.reviewed'
                AND a.subject_id=v.id::text)
            """);
        queries.put("x.growth.customized_before_score","""
            SELECT count(*) FROM user_activity_fact f WHERE f.user_id=:user AND f.kind='growth.customized'
            AND NOT EXISTS(SELECT 1 FROM growth_entries e WHERE e.user_id=f.user_id AND e.points>0 AND e.created_at<=f.occurred_at)
            AND NOT EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=f.user_id AND a.kind='growth.scored'
              AND a.occurred_at<=f.occurred_at)
            """);
        queries.put("x.todo.evidenced","""
            SELECT count(DISTINCT t.id) FROM todo_registry t JOIN todo_registry_links l ON l.todo_id=t.id AND l.relation='evidence'
            JOIN chunks c ON c.id=l.chunk_id AND c.user_id=t.user_id JOIN records r ON r.id=c.record_id AND r.user_id=t.user_id
            WHERE t.user_id=:user AND t.deleted_at IS NULL AND r.deleted_at IS NULL AND r.source='user'
            AND r.created_at>t.created_at AND EXISTS(SELECT 1 FROM user_activity_fact f WHERE f.user_id=t.user_id
                AND f.kind='todo.completed' AND f.subject_key=t.id::text)
            """);
        queries.put("x.todo.source_weeks","""
            SELECT count(DISTINCT date_trunc('week',f.occurred_at AT TIME ZONE 'Asia/Shanghai'))
            FROM user_activity_fact f WHERE f.user_id=:user AND f.kind='todo.completed'
            AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=f.user_id AND a.kind='todo.source_opened'
                AND a.subject_id=f.subject_key AND date_trunc('week',a.occurred_at AT TIME ZONE 'Asia/Shanghai')=
                    date_trunc('week',f.occurred_at AT TIME ZONE 'Asia/Shanghai'))
            """);
        queries.put("x.glossary.enriched","""
            SELECT count(*) FROM user_terms t JOIN records r ON r.id=t.source_record_id AND r.user_id=t.user_id
            WHERE t.user_id=:user AND t.status='confirmed' AND r.source='user' AND r.deleted_at IS NULL AND btrim(t.description)<>''
            AND jsonb_typeof(t.aliases)='array' AND jsonb_array_length(t.aliases)>0
            AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=t.user_id AND a.kind='glossary.aliased' AND a.subject_id=t.id::text)
            AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=t.user_id AND a.kind='glossary.described' AND a.subject_id=t.id::text)
            """);
        queries.put("x.calendar.months","""
            SELECT count(DISTINCT date_trunc('month',r.created_at AT TIME ZONE 'Asia/Shanghai')) FROM records r
            JOIN user_achievement_activity a ON a.user_id=r.user_id AND a.subject_id=r.id::text AND a.kind='calendar.viewed'
            WHERE r.user_id=:user AND r.source='user' AND r.deleted_at IS NULL
            """);
        queries.put("x.calendar.linked_summaries","""
            SELECT count(DISTINCT a.subject_id) FROM user_achievement_activity a WHERE a.user_id=:user AND a.kind='summary.opened'
            AND EXISTS(SELECT 1 FROM user_achievement_activity v JOIN records r ON r.user_id=v.user_id AND r.id::text=v.subject_id
              WHERE v.user_id=a.user_id AND v.kind='calendar.viewed' AND r.source='user' AND r.deleted_at IS NULL
              AND (r.created_at AT TIME ZONE 'Asia/Shanghai')::date::text=a.subject_id)
            """);
        String comparisons="""
             FROM user_achievement_activity a JOIN profile_snapshots l ON l.id::text=a.subject_id AND l.user_id=a.user_id
             JOIN profile_snapshots r ON r.id::text=a.related_id AND r.user_id=a.user_id
             WHERE a.user_id=:user AND a.kind='snapshot.compared' AND l.id<>r.id
            """;
        queries.put("x.snapshot.gap30","SELECT count(*)"+comparisons+" AND abs(extract(epoch from(l.created_at-r.created_at)))>=2592000");
        queries.put("x.snapshot.archived_gap90","SELECT count(*)"+comparisons+"""
             AND abs(extract(epoch from(l.created_at-r.created_at)))>=7776000
             AND EXISTS(SELECT 1 FROM user_achievement_activity o WHERE o.user_id=a.user_id AND o.kind='snapshot.opened' AND o.value='archive' AND o.subject_id=a.subject_id)
             AND EXISTS(SELECT 1 FROM user_achievement_activity o WHERE o.user_id=a.user_id AND o.kind='snapshot.opened' AND o.value='archive' AND o.subject_id=a.related_id)
            """);
        queries.put("x.snapshot.monthly_read","""
            SELECT count(DISTINCT s.period_month) FROM profile_snapshots s WHERE s.user_id=:user AND s.snapshot_type='monthly'
            AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=s.user_id AND a.kind='snapshot.opened' AND a.subject_id=s.id::text)
            """);
        queries.put("x.snapshot.crossquarter_pairs","SELECT count(DISTINCT(a.subject_id,a.related_id))"+comparisons+"""
             AND date_trunc('quarter',coalesce(to_date(l.period_month,'YYYY-MM'),(l.created_at AT TIME ZONE 'Asia/Shanghai')::date))
                 <>date_trunc('quarter',coalesce(to_date(r.period_month,'YYYY-MM'),(r.created_at AT TIME ZONE 'Asia/Shanghai')::date))
            """);
        queries.put("x.memory.types_five_filtered","SELECT count(*) FROM (SELECT c.metadata->>'contentType' AS type"+READY+"""
             AND nullif(btrim(c.metadata->>'contentType'),'') IS NOT NULL GROUP BY c.metadata->>'contentType' HAVING count(*)>=5) types
             WHERE EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=:user AND a.kind='record.filtered' AND a.value='type' AND a.related_id=types.type)
            """);
    }

    boolean supports(String metric) {
        return queries.containsKey(metric)||COMPUTED.contains(metric)
            || ACTIVITY_KINDS.stream().anyMatch(k->metric.equals("x.activity."+k));
    }

    Map<String,Long> values(UUID user,Set<String> required) {
        Map<String,Long> out=new HashMap<>();
        for(String metric:required) if(queries.containsKey(metric))
            out.put(metric,named.queryForObject(queries.get(metric),Map.of("user",user),Long.class));
        if(required.stream().anyMatch(m->COMPUTED.contains(m)||m.startsWith("x.activity."))) {
            var facts=new AchievementActivityMetrics(db,user);
            out.putAll(facts.values());
            if(required.contains("x.record.edited_streak")) out.put("x.record.edited_streak",editedStreak(user,facts));
            if(required.contains("x.memory.mood_streak")) out.put("x.memory.mood_streak",moodStreak(user,facts));
            if(required.contains("x.growth.axis_days_weeks")) out.put("x.growth.axis_days_weeks",axisDays(user));
            if(required.contains("x.settings.configured_use_days")) out.put("x.settings.configured_use_days",configuredDays(facts));
        }
        return out;
    }

    private long editedStreak(UUID user,AchievementActivityMetrics facts) {
        List<LocalDate> days=db.queryForList("""
            SELECT DISTINCT (created_at AT TIME ZONE 'Asia/Shanghai')::date FROM records
            WHERE user_id=? AND source='user' AND deleted_at IS NULL AND status IN('record_only','done')
            """,LocalDate.class,user);
        return AchievementSequences.editedStreak(days,facts.kind("record.edited").stream().map(AchievementActivityMetrics.Activity::day).collect(Collectors.toSet()));
    }
    private long moodStreak(UUID user,AchievementActivityMetrics facts) {
        List<YearMonth> months=named.query("SELECT DISTINCT to_char(r.created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM')"+READY+MOOD,
            Map.of("user",user),(row,n)->YearMonth.parse(row.getString(1)));
        Set<YearMonth> filtered=facts.kind("record.filtered").stream().filter(a->a.value().equals("mood"))
            .map(a->YearMonth.from(a.day())).collect(Collectors.toSet());
        return AchievementSequences.filteredMonthStreak(months,filtered);
    }
    private long axisDays(UUID user) {
        Map<String,Set<LocalDate>> axes=new HashMap<>();
        db.query("SELECT dimension_id,(created_at AT TIME ZONE 'Asia/Shanghai')::date AS day FROM growth_entries WHERE user_id=? AND points>0",row->{
            axes.computeIfAbsent(row.getString(1),k->new HashSet<>()).add(row.getObject(2,LocalDate.class));
        },user);
        return axes.values().stream().filter(days->days.stream().map(d->d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).distinct().count()>=3)
            .mapToLong(Set::size).max().orElse(0);
    }
    private long configuredDays(AchievementActivityMetrics facts) {
        List<AchievementActivityMetrics.Activity> saved=facts.kind("settings.saved");
        Set<String> types=Set.of("home","record","memory","lookback");
        Map<String,OffsetDateTime> first=new HashMap<>();
        for(var a:saved) if(types.contains(a.subject())) first.putIfAbsent(a.subject(),a.time());
        if(!first.keySet().containsAll(types)) return 0;
        OffsetDateTime complete=first.values().stream().max(OffsetDateTime::compareTo).orElseThrow();
        return facts.kind("settings.used").stream().filter(a->!a.time().isBefore(complete)&&types.contains(a.subject()))
            .filter(a->saved.stream().filter(s->s.subject().equals(a.subject())&&!s.time().isAfter(a.time()))
                .max(Comparator.comparing(AchievementActivityMetrics.Activity::time)).map(s->s.value().equals(a.value())).orElse(false))
            .map(AchievementActivityMetrics.Activity::day).distinct().count();
    }
}
