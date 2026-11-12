package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementDatabaseTest extends AchievementDatabaseFixture {
    @Test void catalogContainsAll96IncludingTheFirstBatchAndRejectsUnsupportedRules() throws Exception {
        assertEquals(96,catalog.definitions().size());
        Set<String> enabled=new HashSet<>();
        for(JsonNode definition:catalog.definitions()) {
            if(definition.path("enabled").asBoolean(true)) enabled.add(definition.path("code").asText());
            assertFalse(definition.has("earned"),"Sample account state must not enter the server catalog");
            assertFalse(definition.has("plated"),"Sample plated state must not enter the server catalog");
        }
        assertTrue(enabled.containsAll(FIRST_BATCH_CODES));
        assertThrows(IllegalArgumentException.class,()->AchievementRules.metrics(new ObjectMapper().readTree("{\"all\":[]}")));
        assertTrue(AchievementRules.matches(new ObjectMapper().readTree("""
            {"all":[{"metric":"turns","operator":"gte","value":20},{"metric":"days","operator":"gte","value":5}]}
            """),Map.of("turns",20L,"days",5L)));
        assertFalse(AchievementRules.matches(new ObjectMapper().readTree("""
            {"all":[{"metric":"turns","operator":"gte","value":20},{"metric":"days","operator":"gte","value":5}]}
            """),Map.of("turns",200L,"days",4L)));
    }


    @Test void rollbackDoesNotLeakEventsOrRecords() {
        long before=db.queryForObject("SELECT count(*) FROM achievement_event WHERE user_id=?",Long.class,user);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{ record(0); throw new IllegalStateException("rollback"); }));
        assertEquals(before,db.queryForObject("SELECT count(*) FROM achievement_event WHERE user_id=?",Long.class,user));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM records WHERE user_id=?",Long.class,user));
    }


    @Test void v15UpgradeBackfillsExistingDataAndCanBeRepeated() {
        tx.executeWithoutResult(status->service.reconcile(legacyUser));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='todo.completed'",Long.class,legacyUser));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM user_achievement WHERE user_id=? AND code IN('R-01','P-02')",Long.class,legacyUser));
    }


    @Test void datesAreDistinctAndUnlockIsPermanentAndAcknowledgedOnce() {
        for(int i=0;i<7;i++) record(0);
        reconcile(); assertEquals(0,earned("R-02"));
        for(int i=1;i<7;i++) record(i);
        reconcile(); reconcile();
        assertEquals(1,earned("R-02"));
        tx.executeWithoutResult(status->service.acknowledge(user,List.of("R-02")));
        assertEquals(0,tx.execute(status->service.pending(user)).stream().filter(row->"R-02".equals(row.get("code"))).count());
        db.update("UPDATE records SET deleted_at=now() WHERE user_id=?",user);
        reconcile(); assertEquals(1,earned("R-02"));
    }


    @Test void onlyRealSuccessfulChatCountsAndRunPruningRetainsMilestone() {
        run("shadow","completed",false); run("live","failed",false); run("live","completed",true);
        reconcile(); assertEquals(0,earned("D-01"));
        UUID run=run("live","completed",false);
        reconcile(); assertEquals(1,earned("D-01"));
        db.update("UPDATE agent_runs SET last_event_seq=last_event_seq+1 WHERE run_id=?",run);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='chat.completed'",Long.class,user));
        db.update("DELETE FROM agent_runs WHERE run_id=?",run);
        reconcile(); assertEquals(1,earned("D-01"));
    }


    @Test void todoRecompletionDoesNotFarmTheThreeTaskBadge() {
        long todo=db.queryForObject("INSERT INTO todo_registry(user_id,title,origin_kind) VALUES(?,'task','direct') RETURNING id",Long.class,user);
        for(int i=0;i<4;i++){
            db.update("UPDATE todo_registry SET current_status='completed' WHERE id=?",todo);
            db.update("UPDATE todo_registry SET current_status='not_started' WHERE id=?",todo);
        }
        reconcile(); assertEquals(0,earned("P-02"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='todo.completed'",Long.class,user));
    }


    @Test void glossaryRequiresASuccessfulReplyInTheSameRun() {
        db.update("INSERT INTO user_terms(user_id,term,description,status,query_hit_count) VALUES(?,'Mirror','test','confirmed',1)",user);
        run("live","failed",false);
        reconcile(); assertEquals(0,earned("K-03"));
        UUID successful=run("live","completed",false);
        reconcile(); assertEquals(0,earned("K-03"));
        // A match is not enough until this exact run completes successfully.
        db.update("UPDATE agent_runs SET status='running' WHERE run_id=?",successful);
        db.update("INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(?,'glossary.matched',?)",user,successful.toString());
        reconcile(); assertEquals(0,earned("K-03"));
        db.update("UPDATE agent_runs SET status='completed' WHERE run_id=?",successful);
        reconcile(); assertEquals(1,earned("K-03"));
    }


    @Test void modelTestMustMatchTheActivatedRevision() {
        UUID profile=UUID.randomUUID();
        db.update("""
            INSERT INTO user_model_profiles(id,user_id,name,ai_provider,ai_protocol,ai_model,ai_api_key)
            VALUES(?,?,'test','custom','openai','test','opaque')
            """,profile,user);
        db.update("INSERT INTO user_settings(user_id,active_model_profile_id) VALUES(?,?)",user,profile);
        reconcile(); assertEquals(0,earned("M-01"));
        activities.verifiedModel(user,profile,OffsetDateTime.now().minusDays(1));
        reconcile(); assertEquals(0,earned("M-01"));
        OffsetDateTime revision=db.queryForObject("SELECT updated_at FROM user_model_profiles WHERE id=?",OffsetDateTime.class,profile);
        activities.verifiedModel(user,profile,revision);
        reconcile(); assertEquals(0,earned("M-01"));
        // A pointer alone is not activation: the effective configuration must match.
        db.update("UPDATE user_settings SET ai_provider='custom',ai_protocol='openai',ai_model='test',ai_api_key='opaque' WHERE user_id=?",user);
        reconcile(); assertEquals(1,earned("M-01"));
    }


    @Test void activityRejectsOtherOwnersAndServerOnlyEventNames() {
        UUID other=UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,'test')",other,"other-"+other);
        long id=record(40);
        assertThrows(BusinessException.class,()->activities.report(other,"calendar.opened",""+id,null,null));
        assertThrows(BusinessException.class,()->activities.report(user,"chat.completed","forged",null,null));
        activities.report(user,"calendar.opened",""+id,null,null);
        activities.report(user,"calendar.opened",""+id,null,null);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM user_activity_fact WHERE user_id=? AND kind='calendar.opened'",Long.class,user));
        reconcile(); assertEquals(1,earned("C-03"));
    }


    @Test void collectionUsesActualMajorCardMembership() {
        var cards=tarot.activeCards().stream().filter(card->"major".equals(card.category())).toList();
        for(var card:cards.subList(0,21)) db.update("INSERT INTO tarot_collection(user_id,card_id,first_obtained_at,daily_draw_count) VALUES(?,?,now(),1)",user,card.id());
        reconcile(); assertEquals(0,earned("T-03"));
        db.update("INSERT INTO tarot_collection(user_id,card_id,first_obtained_at,daily_draw_count) VALUES(?,?,now(),1)",user,cards.get(21).id());
        reconcile(); assertEquals(1,earned("T-03"));
    }


    @Test void hiddenConditionsAreNotReturnedBeforeUnlock() {
        var result=tx.execute(status->service.collection(user));
        var items=(List<?>)result.get("items");
        var hidden=items.stream().map(JsonNode.class::cast).filter(item->"K-03".equals(item.path("code").asText())).findFirst().orElseThrow();
        assertEquals("隐藏成就",hidden.path("name").asText());
        assertTrue(hidden.path("motif").isNull());
        assertFalse(hidden.has("rule")); assertFalse(hidden.has("progress"));
    }


    @Test void showcaseChecksOwnershipAndOptimisticVersion() {
        record(0); reconcile();
        assertThrows(BusinessException.class,()->tx.execute(status->service.saveShowcase(user,8,List.of("T-03"),0)));
        assertThrows(BusinessException.class,()->tx.execute(status->service.saveShowcase(user,8,List.of("R-01","R-01"),0)));
        var saved=tx.execute(status->service.saveShowcase(user,8,List.of("R-01"),0));
        assertEquals(1L,saved.get("version"));
        assertEquals("[\"R-01\"]",saved.get("codes").toString());
        var conflict=assertThrows(BusinessException.class,()->tx.execute(status->service.saveShowcase(user,10,List.of(),0)));
        assertEquals(409,conflict.getCode());
        assertEquals("[\"R-01\"]",service.showcase(user).get("codes").toString());
    }


    @Test void concurrentReconciliationGrantsExactlyOnce() throws Exception {
        record(0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{reconcile();return true;});
            var second=pool.submit(()->{reconcile();return true;});
            assertTrue(first.get(10,TimeUnit.SECONDS)); assertTrue(second.get(10,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1,earned("R-01"));
    }


    @Test void queueCanBeDrainedAndReplayDoesNotDuplicateAwards() {
        record(0);
        // Other tests deliberately leave large historical fixtures in this schema.
        for(int i=0;i<300 && db.queryForObject("SELECT count(*) FROM achievement_event WHERE user_id=? AND processed_at IS NULL",Long.class,user)>0;i++) worker.drain();
        assertEquals(1,earned("R-01"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM achievement_event WHERE user_id=? AND processed_at IS NULL",Long.class,user));
    }

}
