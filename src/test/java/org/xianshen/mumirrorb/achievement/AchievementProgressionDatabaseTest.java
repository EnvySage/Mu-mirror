package org.xianshen.mumirrorb.achievement;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xianshen.mumirrorb.achievement.AchievementDatabaseTest.*;

/** Uses the same disposable PostgreSQL fixture, never a substitute for persisted facts. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementProgressionDatabaseTest {
    private final AchievementDatabaseTest fixture=new AchievementDatabaseTest();
    private UUID user;
    @BeforeAll static void open() throws Exception { AchievementDatabaseTest.initialize(); }
    @AfterAll static void close() { AchievementDatabaseTest.cleanup(); }
    @BeforeEach void account() {fixture.account();user=fixture.user;}
    private long record(String content) {
        return db.queryForObject("INSERT INTO records(user_id,content,status) VALUES(?,?,'record_only') RETURNING id",Long.class,user,content);
    }
    private void observed(String kind,String subject,String related,String context,String value,String instant) {
        db.queryForObject("SELECT achievement_observe(?,?,?,?,?,?,?::timestamptz)",Object.class,user,kind,subject,related,context,value,instant);
    }
    private long observations(String kind) {return db.queryForObject("SELECT count(*) FROM user_achievement_activity WHERE user_id=? AND kind=?",Long.class,user,kind);}
    private boolean plated(String code) {return db.queryForObject("SELECT count(*) FROM user_achievement WHERE user_id=? AND code=? AND plated_at IS NOT NULL",Long.class,user,code)>0;}
    private Map<String,Object> collectionItem(String code) {
        var items=(List<?>)service.collection(user).get("items");
        return (Map<String,Object>)new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
            items.stream().filter(item->((com.fasterxml.jackson.databind.JsonNode)item).path("code").asText().equals(code)).findFirst().orElseThrow(),Map.class);
    }
    private long chat(String sources,String vaultRefs,String status,boolean fallback) {
        UUID session=fixture.session(0),run=UUID.randomUUID();
        db.update("""
            INSERT INTO agent_runs(run_id,user_id,session_id,client_request_id,request_hash,question_hash,engine,status,lease_expires_at)
            VALUES(?,?,?,?,repeat('a',64),repeat('b',64),'legacy','running',now()+interval '1 hour')
            """,run,user,session,UUID.randomUUID());
        long message=db.queryForObject("""
            INSERT INTO conversation_history(session_id,user_id,role,content,sources,vault_refs,is_fallback)
            VALUES(?,?,'assistant','A real reply',?::jsonb,?::jsonb,?) RETURNING id
            """,Long.class,session,user,sources,vaultRefs,fallback);
        db.update("UPDATE agent_runs SET status=?,assistant_message_id=?,finished_at=now() WHERE run_id=?",status,message,run);
        return message;
    }
    private long vault(String status) {
        return db.queryForObject("INSERT INTO vault_items(user_id,original_name,storage_key,size_bytes,mime,digest_status) VALUES(?,'test.txt',?,12,'text/plain',?) RETURNING id",Long.class,user,UUID.randomUUID().toString(),status);
    }

    @Test void tenRecordsGrantOriginalAndPlatingOnceWithIndependentAcknowledgement() {
        for(int i=0;i<10;i++) record("record "+i);
        fixture.reconcile();fixture.reconcile();
        assertEquals(1,fixture.earned("R-04"));assertTrue(plated("R-04"));
        var item=collectionItem("R-04");
        assertEquals(true,item.get("plating"));assertEquals(true,item.get("plated"));assertNotNull(item.get("platedAt"));
        assertFalse(item.containsKey("platingRule"));assertFalse(item.containsKey("platingProgress"));
        assertEquals(2,service.pending(user).stream().filter(n->n.get("code").equals("R-04")).count());
        service.acknowledge(user,List.of("R-04"));
        assertEquals(List.of("plated"),service.pending(user).stream().filter(n->n.get("code").equals("R-04")).map(n->n.get("kind")).toList());
        service.acknowledgeNotifications(user,List.of(new AchievementService.Notification("R-04","plated")));
        assertEquals(0,service.pending(user).stream().filter(n->n.get("code").equals("R-04")).count());
        db.update("UPDATE records SET deleted_at=now() WHERE user_id=?",user);
        fixture.reconcile();assertTrue(plated("R-04"));
    }

    @Test void migrationReplayKeepsBothGrantsAndAcknowledgements() throws Exception {
        for(int i=0;i<10;i++)record("record");fixture.reconcile();
        service.acknowledgeNotifications(user,List.of(new AchievementService.Notification("R-04","earned"),new AchievementService.Notification("R-04","plated")));
        db.execute(resource("db/migration-v17-achievement-progression.sql"));
        fixture.reconcile();assertTrue(plated("R-04"));
        assertEquals(0,service.pending(user).stream().filter(n->n.get("code").equals("R-04")).count());
    }

    @Test void editsRequireExtraTextOnAnExistingOnlySavedRecord() {
        long id=record("abcd");
        db.update("UPDATE records SET content='abcd  '||chr(10)||'　' WHERE id=?",id);
        fixture.reconcile();assertEquals(0,fixture.earned("R-05"));
        db.update("UPDATE records SET content='xyz' WHERE id=?",id);fixture.reconcile();assertEquals(0,fixture.earned("R-05"));
        db.update("UPDATE records SET content='abcdefgh' WHERE id=?",id);fixture.reconcile();assertEquals(1,fixture.earned("R-05"));
        assertEquals(1,observations("record.edited"));
        db.update("UPDATE records SET content='abcdefgh' WHERE id=?",id);assertEquals(1,observations("record.edited"));
    }

    @Test void configurationRequiresPersistedChangesAndSuccessfulFormatSpecificExports() {
        db.update("INSERT INTO user_settings(user_id) VALUES(?)",user);
        db.update("UPDATE user_settings SET rag_half_life=30 WHERE user_id=?",user);
        fixture.reconcile();assertEquals(0,fixture.earned("SYS-03"));
        assertThrows(BusinessException.class,()->activities.report(user,"preference.saved","memory",null,"30"));
        db.update("UPDATE user_settings SET rag_half_life=60,default_record_mode='AI_ASSISTED',mirror_lookback=2 WHERE user_id=?",user);
        activities.report(user,"home.changed","records","auto",null);
        activities.exportGenerated(user,"json");activities.exportGenerated(user,"json");fixture.reconcile();
        assertEquals(0,fixture.earned("S-07"));
        activities.exportGenerated(user,"markdown");fixture.reconcile();
        assertEquals(1,fixture.earned("SYS-02"));assertEquals(1,fixture.earned("SYS-03"));assertEquals(1,fixture.earned("SYS-04"));
        assertEquals(1,fixture.earned("SYS-07"));assertEquals(1,fixture.earned("S-07"));
        assertEquals(2,observations("export.generated"));
    }

    @Test void sourceOpenRequiresTheExactSuccessfulReplyAndOwnedActualReference() {
        long id=record("source"),other=record("not referenced");
        long message=chat("[{\"record_id\":"+id+"}]","[]","completed",false);
        assertThrows(BusinessException.class,()->activities.report(user,"record.source_opened",""+other,null,null,""+message));
        activities.report(user,"record.source_opened",""+id,null,null,""+message);
        activities.report(user,"record.source_opened",""+id,null,null,""+message);
        fixture.reconcile();assertEquals(1,fixture.earned("D-04"));assertEquals(1,fixture.earned("D-05"));
        assertEquals(1,observations("record.source_opened"));
        db.update("DELETE FROM agent_runs WHERE assistant_message_id=?",message);
        activities.report(user,"record.source_opened",""+id,null,null,""+message);
        UUID foreign=fixture.otherAccount();
        assertThrows(BusinessException.class,()->activities.report(foreign,"record.source_opened",""+id,null,null,""+message));
        long failed=chat("[{\"record_id\":"+id+"}]","[]","failed",false);
        assertThrows(BusinessException.class,()->activities.report(user,"record.source_opened",""+id,null,null,""+failed));
        long fallback=chat("[{\"record_id\":"+id+"}]","[]","completed",true);
        assertThrows(BusinessException.class,()->activities.report(user,"record.source_opened",""+id,null,null,""+fallback));
    }

    @Test void fileReferencesCannotBeForgedByPassingAnyConfirmedFile() {
        long v=vault("confirmed"),unreferenced=vault("confirmed");
        long message=chat("[]","[{\"vault_item_id\":"+v+"}]","completed",false);
        assertThrows(BusinessException.class,()->activities.report(user,"vault.search_opened",""+v,null,"chat_retrieval"));
        assertThrows(BusinessException.class,()->activities.report(user,"vault.search_opened",""+unreferenced,null,null,""+message));
        activities.report(user,"vault.search_opened",""+v,null,null,""+message);fixture.reconcile();
        assertEquals(1,fixture.earned("V-03"));
    }

    @Test void calendarMonthDateAndFiltersMustMatchTheOpenedRecord() {
        long id=record("entry");
        db.update("INSERT INTO chunks(user_id,record_id,content,segment,metadata) VALUES(?,?,'entry','entry','{\"mood\":[\"calm\"],\"contentType\":\"learning\"}')",user,id);
        String day=db.queryForObject("SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD') FROM records WHERE id=?",String.class,id);
        assertThrows(BusinessException.class,()->activities.report(user,"calendar.viewed",""+id,"1999-01","month"));
        activities.report(user,"calendar.viewed",""+id,day,"date");
        activities.report(user,"calendar.viewed",""+id,day.substring(0,7),"month");
        assertThrows(BusinessException.class,()->activities.report(user,"record.filtered",""+id,"angry","mood"));
        activities.report(user,"record.filtered",""+id,"calm","mood");
        activities.report(user,"record.filtered",""+id,"learning","type");fixture.reconcile();
        for(String code:List.of("C-04","C-05","S-04","S-05"))assertEquals(1,fixture.earned(code));
    }

    @Test void glossaryOnlyCountsExplicitPersonalChangesAndValidatedOriginalSources() {
        long id=record("source");
        long term=db.queryForObject("INSERT INTO user_terms(user_id,term,description,aliases,status,source_record_id) VALUES(?,'term','description','[\"alias\"]','confirmed',?) RETURNING id",Long.class,user,id);
        fixture.reconcile();assertEquals(0,fixture.earned("K-05"));
        activities.glossaryEdited(user,term,true,true);
        activities.report(user,"glossary.source_opened",""+term,""+id,null);fixture.reconcile();
        assertEquals(1,fixture.earned("K-05"));assertEquals(1,fixture.earned("K-06"));
        assertEquals(1,observations("glossary.described"));
        assertThrows(BusinessException.class,()->activities.report(user,"glossary.source_opened",""+term,""+record("wrong"),null));
    }

    @Test void readingReviewBeforeArchiveIsRequiredForTheReviewedFileBadge() {
        long v=vault("extracted");
        db.update("INSERT INTO chunks(user_id,record_id,content,segment,vault_item_id) VALUES(?,?, 'file','parsed file',?)",user,record("container"),v);
        activities.report(user,"vault.reviewed",""+v,null,null);
        db.update("UPDATE vault_items SET digest_status='confirmed' WHERE id=?",v);fixture.reconcile();
        assertEquals(1,fixture.earned("V-05"));
        activities.report(user,"vault.previewed",""+v,null,null);fixture.reconcile();assertEquals(1,fixture.earned("V-04"));
        assertThrows(BusinessException.class,()->activities.report(user,"vault.reviewed",""+v,null,null));
    }

    @Test void meaningsRequireACollectedRealCardAndBothOrientations() {
        String card=tarot.activeCards().get(0).id();fixture.tarotCard(user,card,1);
        activities.report(user,"tarot.meaning_opened",card,null,"upright");fixture.reconcile();assertEquals(0,fixture.earned("T-04"));
        activities.report(user,"tarot.meaning_opened",card,null,"reversed");fixture.reconcile();assertEquals(1,fixture.earned("T-04"));
        assertThrows(BusinessException.class,()->activities.report(user,"tarot.meaning_opened","forged-card",null,"upright"));
    }

    @Test void settingsRegionsRequireTheDocumentedOrderAndBothLayoutsRequireRecords() {
        for(String section:List.of("data","memory","authorization","presentation"))activities.report(user,"settings.section_viewed",section,null,null);
        fixture.reconcile();assertEquals(0,fixture.earned("SYS-06"));
        for(String section:List.of("presentation","authorization","memory","data"))activities.report(user,"settings.section_viewed",section,null,null);
        fixture.reconcile();assertEquals(1,fixture.earned("SYS-06"));
        long id=record("entry");activities.report(user,"layout.viewed",""+id,null,"desktop");
        fixture.reconcile();assertEquals(0,fixture.earned("SYS-05"));
        activities.report(user,"layout.viewed",""+id,null,"mobile");fixture.reconcile();assertEquals(1,fixture.earned("SYS-05"));
    }

    @Test void sevenConfiguredUseDaysStartAfterAllFourChangesAndRequireMatchingValues() {
        for(var entry:Map.of("home","records","record","ONLY_SAVE","memory","60","lookback","2").entrySet())
            observed("settings.saved",entry.getKey(),"","",entry.getValue(),"2026-01-10T12:00:00+08:00");
        observed("settings.used","home","","","records","2026-01-09T12:00:00+08:00");
        for(int day=10;day<16;day++) observed("settings.used","memory","","","60","2026-01-"+day+"T13:00:00+08:00");
        fixture.reconcile();assertFalse(plated("SYS-07"));
        observed("settings.used","lookback","","","3","2026-01-16T13:00:00+08:00");fixture.reconcile();assertFalse(plated("SYS-07"));
        observed("settings.used","home","","","records","2026-01-16T14:00:00+08:00");fixture.reconcile();assertTrue(plated("SYS-07"));
    }

    @Test void deletingAScoredSourceCannotMoveCustomizationBeforeTheFirstScore() {
        db.update("INSERT INTO growth_axes(user_id,dimension_id,name) VALUES(?,'axis','Default')",user);
        long source=record("scored source");
        db.update("""
            INSERT INTO growth_entries(user_id,dimension_id,points,reason,definition_version,source_type,source_record_id)
            VALUES(?,'axis',3,'real score',1,'record',?)
            """,user,source);
        db.update("UPDATE growth_axes SET name='Personal name',description='Personal description',definition_version=2 WHERE user_id=?",user);
        fixture.reconcile();assertEquals(0,fixture.earned("G-03"));
        db.update("DELETE FROM records WHERE id=?",source);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM growth_entries WHERE user_id=?",Long.class,user));
        assertEquals(1,observations("growth.scored"));
        fixture.reconcile();assertEquals(0,fixture.earned("G-03"));
        UUID firstCustom=fixture.otherAccount();
        db.update("INSERT INTO growth_axes(user_id,dimension_id,name) VALUES(?,'axis','Default')",firstCustom);
        db.update("UPDATE growth_axes SET name='Personal name',description='Personal description',definition_version=2 WHERE user_id=?",firstCustom);
        db.update("""
            INSERT INTO growth_entries(user_id,dimension_id,points,reason,definition_version,source_type)
            VALUES(?,'axis',3,'later score',2,'manual')
            """,firstCustom);
        service.reconcile(firstCustom);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM user_achievement WHERE user_id=? AND code='G-03'",Long.class,firstCustom));
    }
}
