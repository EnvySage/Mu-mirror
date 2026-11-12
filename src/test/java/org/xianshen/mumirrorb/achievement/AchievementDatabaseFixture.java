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
abstract class AchievementDatabaseFixture {
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
        db.execute(resource("db/migration-v17-achievement-progression.sql"));
        db.execute(resource("db/migration-v17-achievement-progression.sql"));
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
        db.update("INSERT INTO chunks(user_id,record_id,content,segment,metadata) VALUES(?,?,'summary','summary',jsonb_build_object('summaryDate',?::text))",owner,record,day.toString());
        activities.report(owner,"summary.opened",day.toString(),null,null);
    }
    void vault(UUID owner,String state,boolean deleted) {
        db.update("""
            INSERT INTO vault_items(user_id,original_name,storage_key,size_bytes,mime,digest_status,deleted_at)
            VALUES(?,'fixture.txt',?,1,'text/plain',?,CASE WHEN ? THEN now() ELSE NULL END)
            """,owner,UUID.randomUUID().toString(),state,deleted);
    }















    void action(UUID owner,String state,String outcome) {
        db.update("""
            INSERT INTO assistant_actions(id,user_id,kind,prepare_key,state,payload,preview,target_versions,execution_version,request_id,result)
            VALUES(?,?,'fixture',?,?,'{}','{}','{}',1,?,jsonb_build_object('outcome',?::text))
            """,UUID.randomUUID(),owner,UUID.randomUUID().toString(),state,UUID.randomUUID().toString(),outcome);
    }
}
