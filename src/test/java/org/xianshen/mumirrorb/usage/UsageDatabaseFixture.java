package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches=".+")
abstract class UsageDatabaseFixture {
    static JdbcTemplate db,admin; static String schema;
    static UsageStore store; static UsageQueries queries; static TransactionTemplate tx;
    UUID user;
    @BeforeAll static void setup() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),System.getenv("MIRROR_TEST_DB_USERNAME"),System.getenv("MIRROR_TEST_DB_PASSWORD"));
        admin=new JdbcTemplate(ds);
        String name=admin.queryForObject("select current_database()",String.class);
        if(name==null||!name.startsWith("codex_assistant_growth_test_")) throw new IllegalStateException("Disposable DB required");
        schema="usage_fixture_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        Properties p=new Properties();p.setProperty("currentSchema",schema+",public");ds.setConnectionProperties(p);
        db=new JdbcTemplate(ds); db.execute("create table users(id uuid primary key)");
        db.execute("create table user_model_profiles(id uuid primary key,user_id uuid,name text)");
        db.execute("create table user_settings(user_id uuid,active_model_profile_id uuid,ai_base_url text,ai_model text,ai_provider text)");
        String ddl=new ClassPathResource("db/migration-v21-ai-usage.sql").getContentAsString(StandardCharsets.UTF_8);
        db.execute(ddl);db.execute(ddl);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        store=new UsageStore(db,manager);queries=new UsageQueries(db);
    }
    @AfterAll static void cleanup() { if(admin!=null&&schema!=null) admin.execute("drop schema "+schema+" cascade"); }
    @BeforeEach void account() { user=UUID.randomUUID();db.update("insert into users(id) values(?)",user); }
    UsageEnvelope envelope(UUID task,boolean standalone,String channel,String time) {
        return new UsageEnvelope(UUID.randomUUID(),task,user,"chat",standalone,
                new UsageEnvelope.Snapshot("deepseek","cn:deepseek-v4.1-flash",channel,"渠道 "+channel,null),OffsetDateTime.parse(time));
    }
    UsageReport report(UUID invocation,String id,String tokens,String credits,boolean complete) {
        String json="{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":"+complete+",\"attempts\":[{\"id\":\""+id+"\",\"status\":\"success\",\"provider\":\"deepseek\",\"model\":\"deepseek-v4.1-flash\",\"total_tokens\":"+tokens+",\"credits\":"+credits+",\"usage_state\":\"complete\"}]}";
        return new UsageReport.Parser(new ObjectMapper()).parse(json.getBytes(StandardCharsets.UTF_8),invocation);
    }
    Map<String,Object> summary(String from,String to) { return queries.summary(user,"all",from,to,null,null,null); }
}

