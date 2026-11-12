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
class AchievementAccumulationDatabaseTest extends AchievementDatabaseFixture {
    @Test void threeRecordsMustShareAShanghaiDayAndExcludeNonSavedOrForeignSources() {
        recordAt("2026-12-31T15:50:00Z"); recordAt("2026-12-31T15:59:00Z");
        recordAt("2026-12-31T16:00:00Z"); // Next year and next local day.
        UUID other=otherAccount();
        for(int i=0;i<4;i++) {
            recordAt(other,"2026-12-31T15:00:00Z","user","record_only",false);
            recordAt(user,"2026-12-31T15:00:00Z","user","failed",false);
        }
        recordAt(user,"2026-12-31T15:00:00Z","system","done",false);
        recordAt(user,"2026-12-31T15:00:00Z","user","record_only",true);
        recordAt(user,"2026-12-31T15:00:00Z","user","processing",false);
        recordAt(user,"2026-12-31T15:00:00Z","user","reviewing",false);
        progress("R-04",2,3); assertEquals(0,earned("R-04"));
        long third=recordAt("2026-12-31T15:58:00Z");
        progress("R-04",3,3); assertEquals(1,earned("R-04"));
        db.update("UPDATE records SET deleted_at=now() WHERE id=?",third);
        progress("R-04",2,3); assertEquals(1,earned("R-04"),"An awarded medal is permanent");
    }


    @Test void recordMonthsIncludeYearAndIgnoreDuplicateDatesWithinTheSameMonth() {
        recordAt("2025-01-10T03:00:00Z"); recordAt("2026-01-10T03:00:00Z");
        recordAt("2026-01-20T03:00:00Z");
        UUID other=otherAccount();
        for(int month=2;month<=4;month++) recordAt(other,"2026-0"+month+"-01T00:00:00Z","user","record_only",false);
        progress("R-07",2,3); assertEquals(0,earned("R-07"));
        recordAt("2026-02-01T00:00:00Z");
        progress("R-07",3,3); assertEquals(1,earned("R-07"));
    }


    @Test void fourQuartersMustBeInOneLocalYearAndRespectShanghaiBoundaries() {
        for(String instant:List.of("2025-01-01T00:00:00Z","2025-04-01T00:00:00Z","2026-07-01T00:00:00Z","2026-10-01T00:00:00Z")) recordAt(instant);
        progress("R-08",2,4); assertEquals(0,earned("R-08"));
        recordAt("2025-12-31T16:00:00Z"); // 2026 Q1, not 2025 Q4.
        recordAt("2026-03-31T15:59:59Z"); // Still Q1.
        progress("R-08",3,4); assertEquals(0,earned("R-08"));
        recordAt("2026-03-31T16:00:00Z"); // First instant of Shanghai Q2.
        progress("R-08",4,4); assertEquals(1,earned("R-08"));
    }


    @Test void libraryCountsUsableApprovedChunksRatherThanRecordsAndRejectsForeignOrIncompleteData() {
        UUID other=otherAccount();
        for(int i=0;i<100;i++) readyRecord(user,"user",false);
        progress("K-08",0,100); assertEquals(0,earned("K-08"));
        long record=readyRecord(user,"user",false);
        for(int i=0;i<99;i++) chunk(user,record,true,"ready",VECTOR,"approved memory "+i);
        chunk(user,record,false,"ready",VECTOR,"not approved");
        chunk(other,record,true,"ready",VECTOR,"owner mismatch");
        chunk(user,readyRecord(user,"user",false),true,"failed",VECTOR,"failed");
        chunk(user,readyRecord(user,"user",false),true,"ready",null,"missing vector");
        chunk(user,readyRecord(user,"user",true),true,"ready",VECTOR,"deleted");
        chunk(user,readyRecord(user,"system",false),true,"ready",VECTOR,"system source");
        chunk(other,readyRecord(other,"user",false),true,"ready",VECTOR,"other account");
        chunk(user,readyRecord(user,"user",false),true,"ready",VECTOR,"   ");
        long vaultRecord=readyRecord(user,"user",false);
        long vaultChunk=chunk(user,vaultRecord,true,"ready",VECTOR,"asset chunk");
        vault(user,"confirmed",false);
        db.update("UPDATE chunks SET vault_item_id=(SELECT min(id) FROM vault_items WHERE user_id=?) WHERE id=?",user,vaultChunk);
        progress("K-08",99,100); assertEquals(0,earned("K-08"));
        chunk(user,record,true,"ready",VECTOR,"hundredth approved memory");
        progress("K-08",100,100); assertEquals(1,earned("K-08"));
    }


    @Test void onePendingApprovedChunkKeepsItsWholeRecordOutsideTheUsableMemoryLibrary() {
        long record=readyRecord(user,"user",false);
        for(int i=0;i<99;i++) chunk(user,record,true,"ready",VECTOR,"ready "+i);
        long pending=chunk(user,record,true,"pending",null,"still embedding");
        progress("K-08",0,100); assertEquals(0,earned("K-08"));
        db.update("UPDATE chunks SET embedding_status='ready',embedding=?::vector WHERE id=?",VECTOR,pending);
        progress("K-08",100,100); assertEquals(1,earned("K-08"));
    }


    @Test void completedTasksCountDistinctReceiptsAndShanghaiMondayWeeksAcrossNewYear() {
        LocalDate monday=LocalDate.of(2025,12,29);
        long first=completedTodoAt(user,shanghai(monday));
        for(int week=1;week<11;week++) completedTodoAt(user,shanghai(monday.plusWeeks(week)));
        for(int i=0;i<18;i++) completedTodoAt(user,shanghai(monday.plusDays(6)));
        progress("P-07",29,30); progress("P-08",11,12);
        assertEquals(0,earned("P-07")); assertEquals(0,earned("P-08"));
        completedTodoAt(user,shanghai(monday.plusDays(6))); // Jan 4 is still the Dec 29 week.
        progress("P-07",30,30); assertEquals(1,earned("P-07"));
        db.update("UPDATE todo_registry SET current_status='not_started' WHERE id=?",first);
        db.update("UPDATE todo_registry SET current_status='completed',closed_at=? WHERE id=?",shanghai(monday.plusWeeks(11)),first);
        progress("P-07",30,30); progress("P-08",11,12);
        UUID other=otherAccount();
        for(int week=0;week<12;week++) completedTodoAt(other,shanghai(monday.plusWeeks(week)));
        progress("P-08",11,12); assertEquals(0,earned("P-08"));
        completedTodoAt(user,OffsetDateTime.parse("2026-03-15T15:59:59Z")); // Sunday in existing week 11.
        progress("P-08",11,12);
        completedTodoAt(user,OffsetDateTime.parse("2026-03-15T16:00:00Z")); // Monday starts week 12.
        progress("P-08",12,12); assertEquals(1,earned("P-08"));
    }


    @Test void tarotRepeatAndCollectionRequireRealCatalogMembershipAndEveryCard() {
        var cards=tarot.activeCards();
        assertEquals(78,cards.size());
        UUID other=otherAccount();
        for(var card:cards) tarotCard(other,card.id(),3);
        for(int i=0;i<39;i++) tarotCard(user,cards.get(i).id(),1);
        for(int i=0;i<50;i++) tarotCard(user,"invalid-card-"+i,20);
        tarotCard(user,cards.get(0).id(),1); // A duplicate insert is not another card.
        progress("T-05",1,2); progress("T-07",39,40); progress("T-08",39,78);
        assertEquals(0,earned("T-05")); assertEquals(0,earned("T-07"));
        db.update("UPDATE tarot_collection SET daily_draw_count=2 WHERE user_id=? AND card_id=?",user,cards.get(0).id());
        progress("T-05",2,2); assertEquals(1,earned("T-05"));
        tarotCard(user,cards.get(39).id(),1);
        progress("T-07",40,40); assertEquals(1,earned("T-07"));
        for(int i=40;i<77;i++) tarotCard(user,cards.get(i).id(),1);
        progress("T-08",77,78); assertEquals(0,earned("T-08"));
        tarotCard(user,cards.get(77).id(),1);
        progress("T-08",78,78); assertEquals(1,earned("T-08"));
    }


    @Test void dailyDrawAccumulationUsesDistinctOwnedDrawDates() {
        LocalDate start=LocalDate.of(2026,1,1);
        String card=tarot.activeCards().get(0).id();
        UUID other=otherAccount();
        for(int i=0;i<30;i++) {
            UUID owner=i<29?user:other;
            db.update("""
                INSERT INTO tarot_daily_draw(id,user_id,draw_date,card_id,catalog_version,client_request_id,created_at)
                VALUES(?,?,?,?,'v2',?,?)
                """,UUID.randomUUID(),owner,start.plusDays(i),card,UUID.randomUUID(),shanghai(start.plusDays(i)));
        }
        progress("T-06",29,30); assertEquals(0,earned("T-06"));
        db.update("""
            INSERT INTO tarot_daily_draw(id,user_id,draw_date,card_id,catalog_version,client_request_id,created_at)
            VALUES(?,?,?,?,'v2',?,?)
            """,UUID.randomUUID(),user,start.plusDays(29),card,UUID.randomUUID(),shanghai(start.plusDays(29)));
        progress("T-06",30,30); assertEquals(1,earned("T-06"));
    }


    @Test void growthNeedsPositiveEntriesOnOneAxisAcrossDaysAndFiveDifferentAxes() {
        growth(user,"health",1,"2025-12-31T15:59:00Z");
        growth(user,"health",1,"2025-12-31T16:00:00Z");
        growth(user,"health",1,"2025-12-31T16:30:00Z"); // Same local day.
        growth(user,"learning",1,"2026-01-02T00:00:00Z");
        growth(user,"health",0,"2026-01-02T00:00:00Z");
        growth(otherAccount(),"health",1,"2026-01-02T00:00:00Z");
        progress("G-05",2,3); assertEquals(0,earned("G-05"));
        growth(user,"health",1,"2026-01-02T01:00:00Z");
        progress("G-05",3,3); assertEquals(1,earned("G-05"));
        growth(user,"creative",1,"2026-01-02T01:00:00Z");
        growth(user,"social",1,"2026-01-02T01:00:00Z");
        growth(user,"rest",0,"2026-01-02T01:00:00Z");
        progress("G-06",4,5); assertEquals(0,earned("G-06"));
        growth(user,"rest",1,"2026-01-02T01:00:00Z");
        progress("G-06",5,5); assertEquals(1,earned("G-06"));
        long count=db.queryForObject("SELECT count(*) FROM growth_entries WHERE user_id=? AND points>0",Long.class,user);
        for(long i=count;i<29;i++) growth(user,"health",1,"2026-01-03T00:00:00Z");
        progress("G-07",29,30); assertEquals(0,earned("G-07"));
        growth(user,"health",1,"2026-01-03T00:00:00Z");
        progress("G-07",30,30); assertEquals(1,earned("G-07"));
    }


    @Test void growthMonthAccumulationIncludesYearAndExcludesZeroPointsAndOtherAccounts() {
        for(String instant:List.of("2024-01-15T00:00:00Z","2025-01-15T00:00:00Z","2026-01-15T00:00:00Z","2026-01-29T00:00:00Z")) growth(user,"learning",1,instant);
        growth(user,"learning",0,"2026-02-01T00:00:00Z");
        UUID other=otherAccount();
        for(int month=1;month<=6;month++) growth(other,"learning",1,"2026-0"+month+"-01T00:00:00Z");
        progress("G-08",3,6); assertEquals(0,earned("G-08"));
        growth(user,"learning",1,"2026-03-01T00:00:00Z"); growth(user,"learning",1,"2026-04-01T00:00:00Z");
        progress("G-08",5,6); assertEquals(0,earned("G-08"));
        growth(user,"learning",1,"2026-05-01T00:00:00Z");
        progress("G-08",6,6); assertEquals(1,earned("G-08"));
    }


    @Test void recordAndDialogueThirtyDayBadgesDoNotCountSameDayRepetitions() {
        LocalDate first=LocalDate.of(2025,12,20);
        for(int i=0;i<29;i++) {
            recordAt(shanghai(first.plusDays(i)).toString());
            UUID run=run("live","completed",false);
            db.update("UPDATE user_activity_fact SET occurred_at=? WHERE user_id=? AND kind='chat.completed' AND subject_key=?",shanghai(first.plusDays(i)),user,run.toString());
        }
        recordAt(shanghai(first).toString());
        UUID duplicate=run("live","completed",false);
        db.update("UPDATE user_activity_fact SET occurred_at=? WHERE user_id=? AND kind='chat.completed' AND subject_key=?",shanghai(first),user,duplicate.toString());
        progress("R-06",29,30); progress("D-07",29,30);
        assertEquals(0,earned("R-06")); assertEquals(0,earned("D-07"));
        recordAt(shanghai(first.plusDays(29)).toString());
        UUID last=run("live","completed",false);
        db.update("UPDATE user_activity_fact SET occurred_at=? WHERE user_id=? AND kind='chat.completed' AND subject_key=?",shanghai(first.plusDays(29)),user,last.toString());
        progress("R-06",30,30); progress("D-07",30,30);
        assertEquals(1,earned("R-06")); assertEquals(1,earned("D-07"));
    }


    @Test void glossaryAndVaultThresholdsRequireConfirmedOwnedItems() {
        for(int i=0;i<4;i++) db.update("INSERT INTO user_terms(user_id,term,description,status) VALUES(?,?,'fixture','confirmed')",user,"word-"+i);
        db.update("INSERT INTO user_terms(user_id,term,description,status) VALUES(?,'pending-word','fixture','pending')",user);
        UUID other=otherAccount();
        for(int i=0;i<5;i++) db.update("INSERT INTO user_terms(user_id,term,description,status) VALUES(?,?,'fixture','confirmed')",other,"other-word-"+i);
        for(int i=0;i<29;i++) vault(user,"confirmed",false);
        vault(user,"pending",false); vault(user,"extracted",false); vault(user,"confirmed",true);
        for(int i=0;i<30;i++) vault(other,"confirmed",false);
        progress("K-04",4,5); progress("V-07",29,30);
        assertEquals(0,earned("K-04")); assertEquals(0,earned("V-07"));
        db.update("UPDATE user_terms SET status='confirmed' WHERE user_id=? AND term='pending-word'",user);
        vault(user,"confirmed",false);
        progress("K-04",5,5); progress("V-07",30,30);
        assertEquals(1,earned("K-04")); assertEquals(1,earned("V-07"));
    }


    @Test void thirtySummaryDatesAreValidatedAndReopeningOneSummaryDoesNotAdvance() {
        LocalDate first=LocalDate.of(2026,1,1);
        for(int i=0;i<29;i++) summaryOpened(user,first.plusDays(i));
        activities.report(user,"summary.opened",first.toString(),null,null);
        UUID other=otherAccount(); summaryOpened(other,first.plusDays(29));
        progress("C-07",29,30); assertEquals(0,earned("C-07"));
        summaryOpened(user,first.plusDays(29));
        progress("C-07",30,30); assertEquals(1,earned("C-07"));
    }


    @Test void monthlyPortraitMilestonesCountYearMonthsNotHistoricalVersionsOrManualSnapshots() {
        for(String month:List.of("2025-01","2026-01","2026-02","2026-03","2026-04")) {
            monthlySnapshot(user,month,true); monthlySnapshot(user,month,false);
        }
        db.update("INSERT INTO profile_snapshots(user_id,snapshot_type) VALUES(?,'manual')",user);
        UUID other=otherAccount(); for(int month=1;month<=12;month++) monthlySnapshot(other,String.format(Locale.ROOT,"2025-%02d",month),true);
        progress("REF-07",5,6); assertEquals(0,earned("REF-07"));
        monthlySnapshot(user,"2026-05",true);
        progress("REF-07",6,6); assertEquals(1,earned("REF-07")); assertEquals(0,earned("REF-08"));
        for(int month=6;month<=10;month++) monthlySnapshot(user,String.format(Locale.ROOT,"2026-%02d",month),true);
        progress("REF-08",11,12); assertEquals(0,earned("REF-08"));
        monthlySnapshot(user,"2026-11",true);
        progress("REF-08",12,12); assertEquals(1,earned("REF-08"));
    }


    @Test void dialogueActionMedalRequiresAppliedBusinessSuccessInsteadOfFallbackOrFailure() {
        action(user,"SUCCEEDED","SAVED_ONLY_FALLBACK"); action(user,"FAILED","FAILED");
        action(otherAccount(),"SUCCEEDED","APPLIED");
        progress("D-06",0,1); assertEquals(0,earned("D-06"));
        action(user,"SUCCEEDED","APPLIED");
        progress("D-06",1,1); assertEquals(1,earned("D-06"));
        tx.executeWithoutResult(status->service.installCatalog());
        reconcile(); reconcile();
        assertEquals(1,earned("D-06"));
        assertEquals(1,tx.execute(status->service.pending(user)).stream().filter(row->"D-06".equals(row.get("code"))).count());
    }

}
