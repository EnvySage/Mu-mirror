package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Calculates ordered and correlated observations from the validated activity ledger. */
final class AchievementActivityMetrics {
    record Activity(String kind,String subject,String related,String context,String value,OffsetDateTime time,LocalDate day) {}
    private final List<Activity> activities;

    AchievementActivityMetrics(JdbcTemplate db,UUID user) {
        activities=db.query("""
            SELECT kind,subject_id,related_id,context_id,value,occurred_at,day_key
            FROM user_achievement_activity WHERE user_id=? ORDER BY occurred_at,id
            """,(row,n)->new Activity(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),
                row.getObject(6,OffsetDateTime.class),row.getObject(7,LocalDate.class)),user);
    }

    List<Activity> kind(String kind) { return activities.stream().filter(a->a.kind.equals(kind)).toList(); }
    long subjects(String kind) { return kind(kind).stream().map(Activity::subject).distinct().count(); }
    static boolean has(Activity a,String value) { return value.equals(a.value); }
    private long selected(String kind,Predicate<Activity> filter) { return kind(kind).stream().filter(filter).map(Activity::subject).distinct().count(); }
    private Set<LocalDate> days(String kind) { return kind(kind).stream().map(Activity::day).collect(Collectors.toSet()); }

    Map<String,Long> values() {
        Map<String,Long> out=new HashMap<>();
        for (String kind : List.of("record.edited","record.source_opened","glossary.aliased","glossary.source_opened", "todo.created","todo.edited","todo.source_opened","snapshot.opened","vault.previewed","growth.named"))
            out.put("x.activity."+kind,subjects(kind));
        out.put("x.connection.kinds",subjects("connection.verified"));
        out.put("x.chat.memory",subjects("chat.memory_completed"));
        out.put("x.chat.memory_configured",subjects("chat.memory_configured"));
        out.put("x.chat.model_days",(long)days("chat.model_completed").size());
        out.put("x.model.roundtrip",AchievementSequences.roundTrips(kind("chat.model_completed").stream().map(Activity::related).toList()));
        out.put("x.calendar.month_turns",selected("calendar.viewed",a->has(a,"month")));
        out.put("x.calendar.dates",selected("calendar.viewed",a->has(a,"date")));
        out.put("x.summary.nextday",selected("summary.opened",a->{try{return LocalDate.parse(a.subject).plusDays(1).equals(a.day);}catch(Exception ignored){return false;}}));
        out.put("x.snapshot.archives",selected("snapshot.opened",a->has(a,"archive")));
        out.put("x.export.formats",subjects("export.generated"));
        for (String type : List.of("record","memory","lookback")) out.put("x.settings."+type,selected("settings.saved",a->a.subject.equals(type)));
        out.put("x.settings.types",subjects("settings.saved"));
        out.put("x.layout.modes",kind("layout.viewed").stream().map(Activity::value).distinct().count());
        out.put("x.settings.section_sequence",sectionSequence());
        out.put("x.showcase.eight",selected("showcase.saved",a->a.subject.equals("8")||a.subject.equals("10"))>0?1L:0L);
        out.put("x.showcase.ten_groups",selected("showcase.saved",a->a.subject.equals("10")&&number(a.value)>=5)>0?1L:0L);
        out.put("x.filter.mood",selected("record.filtered",a->has(a,"mood")));
        out.put("x.filter.type",selected("record.filtered",a->has(a,"type")));
        out.put("x.filter.tag_created_before_use",taggedThenFound());
        out.put("x.tarot.meanings",subjects("tarot.meaning_opened"));
        out.put("x.tarot.both_sides",bothSides());
        out.put("x.vault.open_items",subjects("vault.search_opened"));
        out.put("x.vault.open_days",(long)days("vault.search_opened").size());
        out.put("x.vault.multiple_session",kind("vault.search_opened").stream().filter(a->!a.related.isBlank())
            .collect(Collectors.groupingBy(Activity::related,Collectors.mapping(Activity::subject,Collectors.toSet())))
            .values().stream().filter(s->s.size()>=2).count());
        List<Activity> recordOpens=kind("record.source_opened"),vaultOpens=kind("vault.search_opened");
        Set<String> vaultSessions=vaultOpens.stream().map(Activity::related).collect(Collectors.toSet());
        out.put("x.chat.multisource_sessions",recordOpens.stream().filter(a->vaultSessions.contains(a.related)).map(Activity::related).distinct().count());
        Map<String,Activity> completed=kind("chat.memory_completed").stream().collect(Collectors.toMap(Activity::subject,a->a,(a,b)->a));
        Set<String> memoryMessages=completed.keySet();
        List<Activity> memoryOpens=recordOpens.stream().filter(a->memoryMessages.contains(a.context)).toList();
        out.put("x.chat.memory_opened_days",memoryOpens.stream().map(a->completed.get(a.context).day).distinct().count());
        Set<String> vaultMessages=vaultOpens.stream().map(Activity::context).collect(Collectors.toSet());
        out.put("x.chat.multisource_days",memoryOpens.stream().filter(a->vaultMessages.contains(a.context))
            .map(a->completed.get(a.context).day).distinct().count());
        out.put("x.chat.opened_records",memoryOpens.stream().map(Activity::subject).distinct().count());
        Map<String,String> models=kind("chat.memory_completed").stream().filter(a->!a.context.isBlank()).collect(Collectors.toMap(Activity::subject,Activity::context,(a,b)->a));
        out.put("x.chat.opened_models",memoryOpens.stream().map(a->models.get(a.context)).filter(Objects::nonNull).distinct().count());
        Set<String> openedSummaryDates=kind("summary.opened").stream().map(Activity::subject).collect(Collectors.toSet());
        out.put("x.summary.linked_days",kind("summary.source_opened").stream().filter(a->openedSummaryDates.contains(a.context)).map(Activity::context).distinct().count());
        return out;
    }

    private long sectionSequence() {
        List<String> order=List.of("presentation","authorization","memory","data");
        int next=0;
        for(Activity a:kind("settings.section_viewed")) if(a.subject.equals(order.get(next)) && ++next==order.size()) return 1;
        return 0;
    }
    private long taggedThenFound() {
        return kind("record.filtered").stream().filter(a->has(a,"tag") && kind("record.tagged").stream()
            .anyMatch(t->t.subject.equals(a.subject)&&t.related.equals(a.related)&&!t.time.isAfter(a.time))).map(Activity::subject).distinct().count();
    }
    private long bothSides() {
        return kind("tarot.meaning_opened").stream().collect(Collectors.groupingBy(Activity::subject,Collectors.mapping(Activity::value,Collectors.toSet())))
            .values().stream().filter(s->s.containsAll(Set.of("upright","reversed"))).count();
    }
    private static int number(String value) { try{return Integer.parseInt(value);}catch(Exception ignored){return 0;} }
}
