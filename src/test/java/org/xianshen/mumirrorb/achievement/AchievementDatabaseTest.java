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

/** Real PostgreSQL coverage. Explicitly refuses business databases. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementDatabaseTest {
    static JdbcTemplate admin, db;
    static String schema;
    static TransactionTemplate tx;
    static AchievementService service;
    static AchievementActivity activities;
    static AchievementWorker worker;
    static AchievementCatalog catalog;
    static TarotCatalog tarot;
    static UUID legacyUser;
    UUID user;
    static final Set<String> FIRST_BATCH_CODES=Set.of("R-04","R-06","R-07","R-08","D-06","D-07",
            "K-04","K-08","P-07","P-08","C-07","REF-07","REF-08","T-05","T-06","T-07","T-08",
            "V-07","G-05","G-06","G-07","G-08");
    static final String VECTOR="["+String.join(",",Collections.nCopies(1024,"0.001"))+"]";

    @BeforeAll static void initialize() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),System.getenv("MIRROR_TEST_DB_USERNAME"),System.getenv("MIRROR_TEST_DB_PASSWORD"));
        admin=new JdbcTemplate(ds);
        String name=admin.queryForObject("SELECT current_database()",String.class);
        if(name==null || !name.startsWith("codex_assistant_growth_test_")) throw new IllegalStateException("Disposable DB required");
        schema="achievement_fixture_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        Properties properties=new Properties();
        properties.setProperty("currentSchema",schema+",public");
        ds.setConnectionProperties(properties);
        db=new JdbcTemplate(ds);
        db.execute(resource("db/schema.sql").split("-- BEGIN GENERATED ACHIEVEMENTS V16",2)[0]);
        legacyUser=UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,'test')",legacyUser,"legacy-"+legacyUser);
        db.update("INSERT INTO records(user_id,content,status) VALUES(?,'before-v16','record_only')",legacyUser);
        for(int i=0;i<3;i++) db.update("INSERT INTO todo_registry(user_id,title,origin_kind,current_status) VALUES(?,'old task','direct','completed')",legacyUser);
        db.execute(resource("db/migration-v16-achievements.sql"));
        db.execute(resource("db/migration-v16-achievements.sql"));
        ObjectMapper json=new ObjectMapper();
        tarot=new TarotCatalog(json);
        var metrics=new AchievementMetrics(db,tarot);
        catalog=new AchievementCatalog(json,metrics);
        var manager=new DataSourceTransactionManager(ds);
        tx=new TransactionTemplate(manager);
        service=new AchievementService(db,json,catalog,metrics);
        activities=new AchievementActivity(db);
        worker=new AchievementWorker(db,service,manager);
        tx.executeWithoutResult(status->service.installCatalog());
    }
    static String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
    @AfterAll static void cleanup() {
        if(admin!=null && schema!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE");
    }
    @BeforeEach void account() {
        user=UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,?)",user,"achievement-"+user,"test-only");
    }
    void reconcile() { tx.executeWithoutResult(status->service.reconcile(user)); }
    long earned(String code) { return db.queryForObject("SELECT count(*) FROM user_achievement WHERE user_id=? AND code=?",Long.class,user,code); }
    long record(int days) {
        return db.queryForObject("""
            INSERT INTO records(user_id,content,status,created_at) VALUES(?,'test','record_only',now()-?*interval '1 day') RETURNING id
            """,Long.class,user,days);
    }
    UUID session(int days) {
        return db.queryForObject("INSERT INTO chat_sessions(user_id,created_at) VALUES(?,now()-?*interval '1 day') RETURNING id",UUID.class,user,days);
    }
    UUID run(String mode,String status,boolean fallback) {
        UUID session=session(2), id=UUID.randomUUID();
        db.update("""
            INSERT INTO agent_runs(run_id,user_id,session_id,client_request_id,request_hash,question_hash,engine,mode,status,lease_expires_at)
            VALUES(?,?,?,?,repeat('a',64),repeat('b',64),'legacy',?,'running',now()+interval '1 hour')
            """,id,user,session,UUID.randomUUID(),mode);
        Long message=db.queryForObject("""
            INSERT INTO conversation_history(session_id,user_id,role,content,is_fallback) VALUES(?,?,'assistant','response',?) RETURNING id
            """,Long.class,session,user,fallback);
        db.update("UPDATE agent_runs SET status=?,assistant_message_id=?,finished_at=now() WHERE run_id=?",status,message,id);
        return id;
    }

    @Test void catalogContainsAll56IncludingTheFirstBatchAndRejectsUnsupportedRules() throws Exception {
        assertEquals(56,catalog.definitions().size());
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

    UUID otherAccount() {
        UUID owner=UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,'test-only')",owner,"fixture-"+owner);
        return owner;
    }
    static OffsetDateTime shanghai(LocalDate date) { return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(); }
    long recordAt(UUID owner,String instant,String source,String status,boolean deleted) {
        return db.queryForObject("""
            INSERT INTO records(user_id,content,source,status,created_at,deleted_at)
            VALUES(?,'fixture',?,?,?,CASE WHEN ? THEN now() ELSE NULL END) RETURNING id
            """,Long.class,owner,source,status,OffsetDateTime.parse(instant),deleted);
    }
    long recordAt(String instant) { return recordAt(user,instant,"user","record_only",false); }
    JsonNode item(String code) {
        var result=tx.execute(status->service.collection(user));
        return ((List<?>)result.get("items")).stream().map(JsonNode.class::cast)
                .filter(entry->code.equals(entry.path("code").asText())).findFirst().orElseThrow();
    }
    void progress(String code,long current,long target) {
        JsonNode result=item(code);
        assertEquals(current,result.path("progress").path("current").asLong(),code+" current");
        assertEquals(target,result.path("progress").path("target").asLong(),code+" target");
    }
    long readyRecord(UUID owner,String source,boolean deleted) {
        return db.queryForObject("""
            INSERT INTO records(user_id,content,source,status,memory_state,user_reviewed,deleted_at)
            VALUES(?,'memory fixture',?,'done','READY',true,CASE WHEN ? THEN now() ELSE NULL END) RETURNING id
            """,Long.class,owner,source,deleted);
    }
    long chunk(UUID owner,long record,boolean approved,String state,String vector,String segment) {
        return db.queryForObject("""
            INSERT INTO chunks(user_id,record_id,content,segment,classified_segment,metadata,ai_memory_approved,embedding_status,embedding)
            VALUES(?,?,'fixture',?,?,'{}'::jsonb,?,?,?::vector) RETURNING id
            """,Long.class,owner,record,segment,segment,approved,state,vector);
    }
    long completedTodoAt(UUID owner,OffsetDateTime time) {
        long id=db.queryForObject("""
            INSERT INTO todo_registry(user_id,title,origin_kind,current_status,closed_at)
            VALUES(?,'completed fixture','direct','completed',?) RETURNING id
            """,Long.class,owner,time);
        // This durable receipt is the historical success time; production writes it once.
        db.update("UPDATE user_activity_fact SET occurred_at=? WHERE user_id=? AND kind='todo.completed' AND subject_key=?",time,owner,Long.toString(id));
        return id;
    }
    void growth(UUID owner,String axis,int points,String instant) {
        db.update("INSERT INTO growth_axes(user_id,dimension_id,name) VALUES(?,?,?) ON CONFLICT DO NOTHING",owner,axis,axis);
        db.update("""
            INSERT INTO growth_entries(user_id,dimension_id,points,reason,definition_version,source_type,created_at)
            VALUES(?,?,?,'fixture',1,'manual',?)
            """,owner,axis,points,OffsetDateTime.parse(instant));
    }
    void tarotCard(UUID owner,String card,long count) {
        db.update("""
            INSERT INTO tarot_collection(user_id,card_id,first_obtained_at,daily_draw_count)
            VALUES(?,?,now(),?) ON CONFLICT DO NOTHING
            """,owner,card,count);
    }
    void monthlySnapshot(UUID owner,String month,boolean current) {
        db.update("INSERT INTO profile_snapshots(user_id,snapshot_type,period_month,is_current) VALUES(?,'monthly',?,?)",owner,month,current);
    }
    void summaryOpened(UUID owner,LocalDate day) {
        long record=db.queryForObject("INSERT INTO records(user_id,content,source,status) VALUES(?,'summary','system','done') RETURNING id",Long.class,owner);
        db.update("INSERT INTO chunks(user_id,record_id,content,metadata) VALUES(?,?,'summary',jsonb_build_object('summaryDate',?::text))",owner,record,day.toString());
        activities.report(owner,"summary.opened",day.toString(),null,null);
    }
    void vault(UUID owner,String state,boolean deleted) {
        db.update("""
            INSERT INTO vault_items(user_id,original_name,storage_key,size_bytes,mime,digest_status,deleted_at)
            VALUES(?,'fixture.txt',?,1,'text/plain',?,CASE WHEN ? THEN now() ELSE NULL END)
            """,owner,UUID.randomUUID().toString(),state,deleted);
    }

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

    void action(UUID owner,String state,String outcome) {
        db.update("""
            INSERT INTO assistant_actions(id,user_id,kind,prepare_key,state,payload,preview,target_versions,execution_version,request_id,result)
            VALUES(?,?,'fixture',?,?,'{}','{}','{}',1,?,jsonb_build_object('outcome',?::text))
            """,UUID.randomUUID(),owner,UUID.randomUUID().toString(),state,UUID.randomUUID().toString(),outcome);
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
