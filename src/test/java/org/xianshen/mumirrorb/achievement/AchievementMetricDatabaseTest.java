package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full rule projections against PostgreSQL, with one independent missing-prerequisite check per plating rule. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL",matches="^jdbc:postgresql:.*$")
class AchievementMetricDatabaseTest {
    static JdbcTemplate admin,db;
    static TransactionTemplate tx;
    static UUID user,empty;
    static String schema;
    static AchievementMetrics metrics;
    static AchievementCatalog catalog;
    static JsonNode plating;

    @BeforeAll static void seed() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),System.getenv("MIRROR_TEST_DB_USERNAME"),System.getenv("MIRROR_TEST_DB_PASSWORD"));
        admin=new JdbcTemplate(ds);
        String name=admin.queryForObject("SELECT current_database()",String.class);
        if(name==null||!name.startsWith("codex_assistant_growth_test_")) throw new IllegalStateException("Disposable DB required");
        schema="achievement_metrics_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var props=new Properties();props.setProperty("currentSchema",schema+",public");ds.setConnectionProperties(props);
        db=new JdbcTemplate(ds);
        db.execute(resource("db/schema.sql"));
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var json=new ObjectMapper();
        var tarot=new TarotCatalog(json);
        metrics=new AchievementMetrics(db,tarot);
        catalog=new AchievementCatalog(json,metrics);
        plating=json.readTree(resource("achievements/plating-rules.json"));
        user=account();empty=account();
        new AchievementMetricFixture(db,user,tarot).seed();
    }
    private static String resource(String path) throws Exception { return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8); }
    private static UUID account() {
        UUID owner=UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,'test')",owner,"metrics-"+owner);
        return owner;
    }
    @AfterAll static void cleanup() { if(admin!=null&&schema!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    static Set<String> allMetrics() {
        Set<String> requested=new HashSet<>(catalog.metrics());
        plating.elements().forEachRemaining(rule->requested.addAll(AchievementRules.metrics(rule.path("rule"))));
        return requested;
    }

    @Test void allNinetySixOrdinaryRulesMatchProvenBusinessAndActivityInputs() {
        Map<String,Long> values=metrics.values(user,allMetrics());
        for(JsonNode definition:catalog.definitions())
            assertTrue(AchievementRules.matches(definition.path("rule"),values),definition.path("code").asText()+" "+definition.path("rule")+" values="+values);
        assertEquals(96,catalog.definitions().size());
    }

    @Test void noOrdinaryOrPlatedRuleReadsAnotherUsersLedger() {
        Map<String,Long> values=metrics.values(empty,allMetrics());
        for(JsonNode definition:catalog.definitions())
            assertFalse(AchievementRules.matches(definition.path("rule"),values),definition.path("code").asText());
        plating.fields().forEachRemaining(entry->assertFalse(AchievementRules.matches(entry.getValue().path("rule"),values),entry.getKey()));
    }

    @TestFactory Collection<DynamicTest> everyPlatingRuleRequiresItsOwnEvidence() {
        Map<String,String> removed=new LinkedHashMap<>();
        removed.put("M-03",activity("record.source_opened"));
        removed.put("M-08",activity("chat.model_completed"));
        removed.put("R-02",activity("record.edited"));
        removed.put("R-04","UPDATE records SET status='failed' WHERE user_id=? AND content='daily max'");
        removed.put("D-05",activity("record.source_opened"));
        removed.put("D-08",activity("vault.search_opened"));
        removed.put("K-04",activity("glossary.described"));
        removed.put("K-08",activity("memory.reviewed"));
        removed.put("P-02","DELETE FROM todo_registry_links WHERE todo_id IN(SELECT id FROM todo_registry WHERE user_id=?)");
        removed.put("P-08",activity("todo.source_opened"));
        removed.put("C-02",activity("summary.source_opened"));
        removed.put("C-08",activity("summary.opened"));
        removed.put("REF-03",activity("snapshot.opened"));
        removed.put("REF-08",activity("snapshot.compared"));
        removed.put("T-02",activity("tarot.meaning_opened")+" AND value='reversed'");
        removed.put("T-07",activity("tarot.meaning_opened"));
        removed.put("V-02",activity("vault.search_opened"));
        removed.put("V-08",activity("vault.search_opened"));
        removed.put("G-05","DELETE FROM growth_entries WHERE user_id=?");
        removed.put("G-08","DELETE FROM growth_entries WHERE user_id=?");
        removed.put("S-02",activity("record.filtered")+" AND value='type'");
        removed.put("S-08",activity("record.filtered")+" AND value='mood'");
        removed.put("SYS-07",activity("settings.used"));
        removed.put("SYS-08",activity("showcase.saved")+" AND subject_id='10'");
        assertEquals(24,removed.size());
        List<DynamicTest> tests=new ArrayList<>();
        removed.forEach((code,statement)->tests.add(DynamicTest.dynamicTest(code+" plating proves and loses its prerequisite",()->{
            JsonNode rule=plating.path(code).path("rule");
            Set<String> dependencies=AchievementRules.metrics(rule);
            assertTrue(AchievementRules.matches(rule,metrics.values(user,dependencies)),code+" positive fixture");
            tx.executeWithoutResult(status->{
                db.update(statement,user);
                assertFalse(AchievementRules.matches(rule,metrics.values(user,dependencies)),code+" missing evidence");
                status.setRollbackOnly();
            });
        })));
        return tests;
    }
    private static String activity(String kind) {
        return "DELETE FROM user_achievement_activity WHERE user_id=? AND kind='"+kind+"'";
    }
}
