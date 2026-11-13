package org.xianshen.mumirrorb.ledger.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.ledger.LedgerQueries;
import org.xianshen.mumirrorb.ledger.LedgerService;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.*;

@EnabledIfEnvironmentVariable(named = "MIRROR_LEDGER_PLANNING_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
class LedgerPlanningDatabaseTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static JdbcTemplate admin;
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static String schema;
    private static LedgerWishService wishes;
    private static LedgerRecurringService recurring;
    private UUID owner;
    private UUID outsider;

    @BeforeAll
    static void prepare() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("MIRROR_LEDGER_PLANNING_TEST_DB_URL"),
                System.getenv("MIRROR_LEDGER_PLANNING_TEST_DB_USERNAME"),
                System.getenv("MIRROR_LEDGER_PLANNING_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(ds);
        if (!"mirror_ledger_planning_test".equals(admin.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("Ledger planning tests require the disposable mirror_ledger_planning_test database");
        }
        schema = "ledger_planning_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        ds.setConnectionProperties(properties);
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        db.execute("CREATE TABLE records(id BIGSERIAL PRIMARY KEY,user_id UUID NOT NULL,deleted_at TIMESTAMPTZ)");
        db.execute("CREATE TABLE vault_items(id BIGSERIAL PRIMARY KEY,user_id UUID NOT NULL,mime TEXT,deleted_at TIMESTAMPTZ)");
        for (String resource : new String[]{"db/migration-v19-ledger.sql", "db/migration-v20-ledger-planning.sql"}) {
            String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
            db.execute(sql);
            db.execute(sql);
        }
        LedgerService ledger = new LedgerService(db, new LedgerQueries(db), null, JSON, mock(SnapshotLineageService.class));
        wishes = new LedgerWishService(new LedgerWishRepository(db, JSON), ledger);
        recurring = new LedgerRecurringService(db, ledger);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll
    static void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @BeforeEach
    void seedOwners() {
        owner = UUID.randomUUID();
        outsider = UUID.randomUUID();
        db.update("INSERT INTO users(id) VALUES (?), (?)", owner, outsider);
    }

    @Test
    void pendingBillConfirmationSerializesAndUsesActualPaymentDate() throws Exception {
        LocalDate today = PlanningDates.today();
        var rule = tx.execute(s -> recurring.create(owner,
                new RecurringInput("订阅", new BigDecimal("39.90"), "life", today, Interval.MONTH, 1, "")));
        var due = tx.execute(s -> recurring.pending(owner)).get(0);
        assertEquals(rule.id(), due.ruleId());
        assertEquals(0, ledgerRecordCount(owner));
        assertEquals(due.id(), tx.execute(s -> recurring.pending(owner)).get(0).id());
        assertTrue(tx.execute(s -> recurring.pending(outsider)).isEmpty());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> tx.execute(s -> recurring.confirm(outsider, due.id(), null))).getCode());
        db.update("UPDATE ledger_recurring_occurrences SET due_on = ? WHERE id = ?", today.minusDays(1), due.id());

        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(1);
        try {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(n -> executor.submit(() -> {
                ready.await();
                return tx.execute(s -> recurring.confirm(owner, due.id(), new ConfirmBill(new BigDecimal("38.50"))));
            })).toList();
            ready.countDown();
            var first = jobs.get(0).get(10, TimeUnit.SECONDS);
            var retry = jobs.get(1).get(10, TimeUnit.SECONDS);
            assertEquals(DueState.CONFIRMED, first.state());
            assertEquals(first.recordId(), retry.recordId());
            assertEquals(new BigDecimal("38.50"), first.amount());
            assertEquals(1, ledgerRecordCount(owner));
            assertEquals(today, db.queryForObject("SELECT occurred_on FROM ledger_flows WHERE user_id = ?", LocalDate.class, owner));
            assertTrue(tx.execute(s -> recurring.pending(owner)).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fixedRecurringWishResetsAndPreservesPriorBudgetAndRecoveryEvidence() {
        LocalDate today = PlanningDates.today();
        LocalDate start = today.minusDays(7);
        UUID record = UUID.randomUUID();
        UUID expense = insertFlow(owner, record, "EXPENSE", "food", "100.00", start, null);
        insertFlow(owner, record, "RECOVERY", "other", "40.00", today.minusDays(1), expense);
        insertFlow(owner, UUID.randomUUID(), "EXPENSE", "study", "200.00", start, null);
        insertFlow(outsider, UUID.randomUUID(), "EXPENSE", "food", "400.00", start, null);

        var wish = tx.execute(s -> wishes.create(owner, new WishInput("吃顿好的", null, null, null,
                WishKind.RECURRING, Metric.SAVINGS, start, 7, WindowMode.FIXED, new BigDecimal("50"),
                null, Map.of("food", new BigDecimal("120")))));
        assertEquals(WishState.ACTIVE, wish.state());
        assertEquals(today, wish.periodStart());
        assertNull(wish.achievement());
        var events = tx.execute(s -> wishes.events(owner, start, today));
        assertEquals(1, events.size());
        var achieved = events.get(0);
        assertEquals(today.minusDays(1), achieved.achievedOn());
        assertEquals(new BigDecimal("60.00"), achieved.amount());
        assertEquals(java.util.List.of(record), achieved.recordIds());
        tx.execute(s -> wishes.updateAmounts(owner, wish.id(), JSON.createObjectNode().put("targetAmount", 75)));
        assertEquals(events, tx.execute(s -> wishes.events(owner, start, today)));
        assertTrue(tx.execute(s -> wishes.events(outsider, start, today)).isEmpty());
    }

    @Test
    void rollingWishDoesNotSettleUntilWindowIsCompleteAndStopsOnFirstSuccess() {
        LocalDate today = PlanningDates.today();
        var future = tx.execute(s -> wishes.create(owner, new WishInput("仍在统计", null, null, null,
                WishKind.ONCE, Metric.SAVINGS, today.minusDays(6), 7, WindowMode.ROLLING,
                new BigDecimal("20"), new BigDecimal("100"), null)));
        assertEquals(WishState.ACTIVE, future.state());
        assertNull(future.achievement());
        UUID record = UUID.randomUUID();
        LocalDate start = today.minusDays(7);
        insertFlow(owner, record, "EXPENSE", "food", "80.00", start, null);
        var completed = tx.execute(s -> wishes.create(owner, new WishInput("已经达成", null, null, new BigDecimal("30"),
                WishKind.ONCE, Metric.SAVINGS, start, 7, WindowMode.ROLLING,
                new BigDecimal("20"), new BigDecimal("100"), null)));
        assertEquals(WishState.ACHIEVED, completed.state());
        assertEquals(new BigDecimal("20.00"), completed.achievement().amount());
        assertEquals(today.minusDays(1), completed.periodEnd());
        var updated = tx.execute(s -> wishes.updateAmounts(owner, completed.id(), JSON.createObjectNode().put("targetAmount", 90)));
        assertEquals(completed.achievement(), updated.achievement());
        assertEquals(400, assertThrows(BusinessException.class, () -> tx.execute(s ->
                wishes.updateAmounts(owner, completed.id(), JSON.createObjectNode().put("windowDays", 14)))).getCode());
        tx.executeWithoutResult(s -> wishes.delete(owner, completed.id()));
        assertEquals(java.util.List.of(completed.achievement()), tx.execute(s -> wishes.events(owner, start, today)));
    }

    private long ledgerRecordCount(UUID user) {
        return db.queryForObject("SELECT count(*) FROM ledger_records WHERE user_id = ?", Long.class, user);
    }

    private UUID insertFlow(UUID user, UUID record, String kind, String category, String amount, LocalDate date, UUID source) {
        db.update("""
                INSERT INTO ledger_records(id,user_id,title,record_date) VALUES(?,?,?,?) ON CONFLICT (id) DO NOTHING
                """, record, user, "测试账目", date);
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO ledger_flows(id,record_id,user_id,kind,amount,category_id,occurred_on,related_expense_id)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, record, user, kind, new BigDecimal(amount), category, date, source);
        return id;
    }
}
