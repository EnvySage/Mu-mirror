package org.xianshen.mumirrorb.tarot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.mapper.TarotMapper;
import org.xianshen.mumirrorb.service.impl.TarotServiceImpl;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

/** Tests what people experience, using an explicitly provided disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="MIRROR_TEST_DB_URL", matches="^jdbc:postgresql:.*$")
class TarotExperienceDatabaseTest {
    static JdbcTemplate db;
    static TarotMapper repository;
    static TarotCatalog catalog;
    static TransactionTemplate transactions;
    UUID user;
    @BeforeAll static void database() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),
                System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        db = new JdbcTemplate(ds);
        // Minimal existing-table fixtures; the feature migration itself is executed unmodified.
        db.execute("CREATE TABLE IF NOT EXISTS users(id UUID PRIMARY KEY,username TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS chat_sessions(id UUID PRIMARY KEY,user_id UUID REFERENCES users(id) ON DELETE CASCADE)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-v12-tarot.sql")).execute(ds);
        repository = new TarotMapper(db, new ObjectMapper().findAndRegisterModules());
        catalog = new TarotCatalog(new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void user() {
        user = UUID.randomUUID();
        db.update("INSERT INTO users(id,username,password_hash) VALUES(?,?,?)", user, "tarot-test-"+user, "unused");
    }
    @AfterEach void cleanup() { db.update("DELETE FROM users WHERE id=?",user); }
    TarotServiceImpl at(String instant) {
        return new TarotServiceImpl(repository,catalog,transactions,Clock.fixed(Instant.parse(instant),ZoneOffset.UTC),new Random(17));
    }
    @Test void concurrentClicksAndNextDayRetriesKeepOneDailyCard() throws Exception {
        var service=at("2026-10-26T15:59:58Z");
        var requests=new ArrayList<UUID>();
        var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new ArrayList<Callable<DailyView>>();
            for(int i=0;i<20;i++){ var request=UUID.randomUUID(); requests.add(request); tasks.add(()->service.drawDaily(user,request)); }
            var results=pool.invokeAll(tasks);
            var first=results.get(0).get().draw();
            for(var result:results) assertEquals(first.id(),result.get().draw().id());
            assertEquals(1,service.collection(user).unlockedCount());
            assertEquals(1,service.collection(user).entries().get(0).dailyDrawCount());
            var tomorrow=at("2026-10-26T16:00:02Z");
            for(var request:requests) assertEquals(first.id(),tomorrow.drawDaily(user,request).draw().id());
            assertNull(tomorrow.daily(user).draw());
            assertEquals(LocalDate.of(2026,10,27),tomorrow.drawDaily(user,UUID.randomUUID()).draw().drawDate());
            assertEquals(2,db.queryForObject("SELECT count(*) FROM tarot_daily_draw WHERE user_id=?",Integer.class,user));
        } finally { pool.shutdownNow(); }
    }
    @Test void questionDrawsNeverUnlockAndOtherAccountsCannotReadThem() {
        var service=at("2026-10-26T04:00:00Z"); var request=UUID.randomUUID();
        var reading=service.createReading(user,request);
        assertEquals(reading.readingId(),service.createReading(user,request).readingId());
        for(int i=0;i<15;i++) service.createReading(user,UUID.randomUUID());
        assertEquals(0,service.collection(user).unlockedCount()); assertNull(service.daily(user).draw());
        var denied=assertThrows(BusinessException.class,()->service.getReading(UUID.randomUUID(),reading.readingId()));
        assertEquals(404,denied.getCode());
    }
    @Test void preferencesPersistAndStaleDeviceDoesNotOverwriteThem() {
        var service=at("2026-10-26T04:00:00Z"); var defaults=service.preferences(user);
        assertTrue(defaults.diaryEnabled()); assertEquals(7,defaults.diaryDays()); assertFalse(defaults.todosEnabled());
        var changed=service.updatePreferences(user,new PreferencesInput(false,30,true,true,defaults.version()));
        assertEquals(changed,service.preferences(user));
        assertEquals(409,assertThrows(BusinessException.class,()->service.updatePreferences(user,new PreferencesInput(true,7,false,false,defaults.version()))).getCode());
        assertEquals(400,assertThrows(BusinessException.class,()->service.updatePreferences(user,new PreferencesInput(true,100,false,false,changed.version()))).getCode());
    }
}
