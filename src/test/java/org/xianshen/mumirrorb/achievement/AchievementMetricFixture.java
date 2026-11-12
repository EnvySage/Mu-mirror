package org.xianshen.mumirrorb.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.time.*;
import java.util.*;

/** Synthetic trusted-ledger inputs exercise the metric boundary; activity acceptance is tested separately. */
final class AchievementMetricFixture {
    final JdbcTemplate db;
    final UUID user;
    final TarotCatalog tarot;
    final LocalDate start=LocalDate.of(2025,1,1);
    final List<Long> records=new ArrayList<>(),chunks=new ArrayList<>(),snapshots=new ArrayList<>();
    final UUID modelA=UUID.randomUUID(),modelB=UUID.randomUUID();

    AchievementMetricFixture(JdbcTemplate db,UUID user,TarotCatalog tarot) {
        this.db=db;this.user=user;this.tarot=tarot;
    }
    static OffsetDateTime at(LocalDate day) { return day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(); }
    void event(String kind,String subject,String related,String context,String value,LocalDate day) {
        db.update("""
            INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,context_id,value,occurred_at,day_key)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """,user,kind,subject,related,context,value,at(day).plusHours(12),day);
    }
    void event(String kind,String subject,LocalDate day) { event(kind,subject,"","","",day); }
    void fact(String kind,String subject,LocalDate day) {
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key,occurred_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",user,kind,subject,at(day));
    }
    void seed() {
        String vector="["+String.join(",",Collections.nCopies(1024,"0.001"))+"]";
        for(int month=0;month<12;month++) {
            LocalDate date=start.plusMonths(month);
            for(int i=0;i<10;i++) {
                long record=db.queryForObject("""
                    INSERT INTO records(user_id,content,status,memory_state,user_reviewed,created_at)
                    VALUES(?,'memory','done','READY',true,?) RETURNING id
                    """,Long.class,user,at(date.plusDays(i)));
                long chunk=db.queryForObject("""
                    INSERT INTO chunks(user_id,record_id,content,segment,classified_segment,metadata,ai_memory_approved,embedding_status,embedding)
                    VALUES(?,?,'memory','memory','memory',jsonb_build_object('contentType',?::text,'mood',jsonb_build_array(?::text)),true,'ready',?::vector) RETURNING id
                    """,Long.class,user,record,"type"+(i%4),"mood"+(i%5),vector);
                records.add(record);chunks.add(chunk);
                event("memory.reviewed",Long.toString(chunk),Long.toString(record),"","",date);
                db.update("INSERT INTO growth_axes(user_id,dimension_id,name) VALUES(?,?,?) ON CONFLICT DO NOTHING",user,"axis"+(i%5),"axis"+(i%5));
                db.update("""
                    INSERT INTO growth_entries(user_id,dimension_id,points,reason,definition_version,source_type,created_at)
                    VALUES(?,?,1,'fixture',1,'manual',?)
                    """,user,"axis"+(i%5),at(date.plusDays(i)));
            }
            long representative=records.get(month*10);
            event("calendar.viewed",Long.toString(representative),date.toString(),"","date",date);
            event("calendar.viewed",Long.toString(representative),date.toString().substring(0,7),"","month",date);
            event("summary.opened",date.toString(),date.plusDays(1));
            event("summary.source_opened",Long.toString(representative),"",date.toString(),"",date.plusDays(1));
            fact("summary.opened",date.toString(),date.plusDays(1));
            long snapshot=db.queryForObject("""
                INSERT INTO profile_snapshots(user_id,snapshot_type,period_month,created_at)
                VALUES(?,'monthly',?,?) RETURNING id
                """,Long.class,user,date.toString().substring(0,7),at(date));
            snapshots.add(snapshot);
            event("snapshot.opened",Long.toString(snapshot),"","","archive",date.plusDays(1));
            event("record.filtered",Long.toString(representative),"mood0","","mood",date);
        }
        for(int i=0;i<10;i++) db.update("INSERT INTO records(user_id,content,status,created_at) VALUES(?,'daily max','record_only',?)",user,at(start));
        for(int i=10;i<28;i++) fact("summary.opened",start.plusDays(i).toString(),start.plusDays(i+1));
        for(int i=0;i<3;i++) event("record.edited",records.get(i).toString(),start.plusDays(i));
        for(int i=0;i<4;i++) {
            event("record.filtered",records.get(i).toString(),"type"+i,"","type",start);
            event("snapshot.compared",snapshots.get(i).toString(),snapshots.get(i+6).toString(),"","",start.plusMonths(7));
            fact("snapshot.compared",snapshots.get(i)+":"+snapshots.get(i+6),start.plusMonths(7));
        }
        event("record.tagged",records.get(0).toString(),"tag","","",start);
        event("record.filtered",records.get(0).toString(),"tag","","tag",start.plusDays(1));
        for(int i=0;i<10;i++) {
            long term=db.queryForObject("""
                INSERT INTO user_terms(user_id,term,aliases,description,status,source_record_id)
                VALUES(?,?,jsonb_build_array(?::text),'my explanation','confirmed',?) RETURNING id
                """,Long.class,user,"word"+i,"alias"+i,records.get(i));
            event("glossary.aliased",Long.toString(term),start);
            event("glossary.described",Long.toString(term),start);
            event("glossary.source_opened",Long.toString(term),records.get(i).toString(),"","",start);
        }
        for(int i=0;i<30;i++) {
            LocalDate day=start.plusWeeks(i);
            long todo=db.queryForObject("""
                INSERT INTO todo_registry(user_id,title,origin_kind,notes,current_status,created_at,closed_at)
                VALUES(?,'task','direct','concrete details','completed',?,?) RETURNING id
                """,Long.class,user,at(start.minusDays(30)),at(day));
            db.update("UPDATE user_activity_fact SET occurred_at=? WHERE user_id=? AND kind='todo.completed' AND subject_key=?",at(day),user,Long.toString(todo));
            db.update("INSERT INTO todo_registry_links(todo_id,chunk_id,relation) VALUES(?,?,'evidence')",todo,chunks.get(i));
            event("todo.created",Long.toString(todo),day);
            event("todo.edited",Long.toString(todo),day);
            event("todo.source_opened",Long.toString(todo),records.get(i).toString(),"","",day);
        }
        List<String> cards=tarot.activeCards().stream().map(c->c.id()).toList();
        for(String card:cards) {
            db.update("INSERT INTO tarot_collection(user_id,card_id,first_obtained_at,daily_draw_count) VALUES(?,?,?,2)",user,card,at(start));
            event("tarot.meaning_opened",card,"","","upright",start);
            event("tarot.meaning_opened",card,"","","reversed",start);
        }
        for(int i=0;i<30;i++) db.update("""
            INSERT INTO tarot_daily_draw(id,user_id,draw_date,card_id,catalog_version,client_request_id,created_at)
            VALUES(?,?,?,?,?,?,?)
            """,UUID.randomUUID(),user,start.plusDays(i),cards.get(i),"fixture",UUID.randomUUID(),at(start.plusDays(i)));
        seedModelsAndChats();
        seedSettings();
        for(String layout:List.of("desktop","mobile")) event("layout.viewed",records.get(0).toString(),"","",layout,start);
        event("showcase.saved","8","","","5",start);
        event("showcase.saved","10","","","5",start);
        event("growth.named","axis0",start);
        fact("growth.customized","axis0",start.minusYears(1));
        fact("calendar.opened",records.get(0).toString(),start);
        fact("session.opened","old-session",start);
        fact("glossary.used","run",start);
        fact("export.generated","first",start);
        fact("home.changed","records",start);
        db.update("""
            INSERT INTO assistant_actions(id,user_id,kind,prepare_key,state,payload,preview,target_versions,execution_version,request_id,result)
            VALUES(?,?,'fixture',?,'SUCCEEDED','{}','{}','{}',1,?,'{"outcome":"APPLIED"}')
            """,UUID.randomUUID(),user,UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    private void seedModelsAndChats() {
        for(UUID model:List.of(modelA,modelB)) {
            db.update("INSERT INTO user_model_profiles(id,user_id,name,ai_provider,ai_protocol,ai_model,ai_api_key) VALUES(?,?,'model','openai','openai','fixture','fixture')",model,user);
            db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) SELECT user_id,'model.verified',achievement_profile_key(id) FROM user_model_profiles WHERE id=? ON CONFLICT DO NOTHING",model);
            fact("chat.model_used",model.toString(),start);
        }
        db.update("INSERT INTO user_settings(user_id,active_model_profile_id,ai_provider,ai_protocol,ai_model,ai_api_key) VALUES(?,?,'openai','openai','fixture','fixture')",user,modelA);
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) SELECT user_id,'model.activated',achievement_profile_key(id) FROM user_model_profiles WHERE id=? ON CONFLICT DO NOTHING",modelA);
        for(int i=0;i<30;i++) {
            long vault=db.queryForObject("""
                INSERT INTO vault_items(user_id,original_name,storage_key,size_bytes,mime,digest_status)
                VALUES(?,'fixture.txt',?,1,'text/plain','confirmed') RETURNING id
                """,Long.class,user,UUID.randomUUID().toString());
            event("vault.previewed",Long.toString(vault),start);
            event("vault.reviewed",Long.toString(vault),start);
            for(int d=0;d<30;d++) event("vault.search_opened",Long.toString(vault),"session"+d,"message"+d,"",start.plusDays(d));
        }
        for(int i=0;i<90;i++) {
            LocalDate day=start.plusDays(i);
            String model=i%2==0?modelA.toString():modelB.toString();
            fact("chat.completed","run"+i,day);
            event("chat.model_completed","run"+i,model,"","",day);
            event("chat.memory_completed","message"+i,"session"+i,model,"run"+i,day);
            event("record.source_opened",records.get(i%records.size()).toString(),"session"+i,"message"+i,"",day);
        }
        event("chat.memory_configured","message0","session0",modelA.toString(),"run0",start);
        event("connection.verified","chat",start);
        event("connection.verified","embedding",start);
    }
    private void seedSettings() {
        for(String option:List.of("home","record","memory","lookback")) event("settings.saved",option,"","",option+"value",start);
        for(int i=1;i<=7;i++) event("settings.used","home","","","homevalue",start.plusDays(i));
        for(String section:List.of("presentation","authorization","memory","data")) {
            // Distinct minutes preserve visit order even when the same date is deduplicated.
            event("settings.section_viewed",section,start);
        }
        event("export.generated","json",start);
        event("export.generated","markdown",start);
    }
}
