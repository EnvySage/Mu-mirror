package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.xianshen.mumirrorb.action.*;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.controller.AssistantActionController;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DTO.*;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real PostgreSQL + MyBatis + Spring transaction tests. No application context/config defaults.
 * Requires an explicitly provisioned disposable DB named codex_assistant_growth_test_*.
 * Each suite creates its own schema and drops only that schema after closing the context.
 */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionProposalDatabaseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String schema = "action_test_" + UUID.randomUUID().toString().replace("-", "");
    private DriverManagerDataSource bootstrap;
    private DriverManagerDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ActionProposalService service;
    private DatabaseHandler handler;
    private UUID owner;
    private long targetId;

    @BeforeAll
    void initializeDedicatedSchema() throws Exception {
        bootstrap = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),
                System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        try (Connection connection = bootstrap.getConnection(); Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_database()")) {
                assertTrue(result.next());
                assertTrue(result.getString(1).startsWith("codex_assistant_growth_test_"),
                        "Refusing to mutate a database outside the explicit disposable test namespace");
            }
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource = new DriverManagerDataSource(System.getenv("MIRROR_TEST_DB_URL"),
                System.getenv("MIRROR_TEST_DB_USERNAME"), System.getenv("MIRROR_TEST_DB_PASSWORD"));
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE chat_sessions(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id))");
        jdbc.execute("CREATE TABLE conversation_history(id BIGSERIAL PRIMARY KEY, session_id UUID REFERENCES chat_sessions(id), user_id UUID NOT NULL REFERENCES users(id), role TEXT NOT NULL)");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration-v6-assistant-actions.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource); // Explicitly exercise rerunnable migration.
        jdbc.execute("CREATE TABLE test_targets(id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), version INTEGER NOT NULL DEFAULT 1, title TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE test_effects(action_id UUID PRIMARY KEY, target_id BIGINT REFERENCES test_targets(id), UNIQUE(target_id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE TABLE test_jobs(id BIGSERIAL PRIMARY KEY, action_id UUID NOT NULL UNIQUE, state TEXT NOT NULL DEFAULT 'pending')");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        configuration.addMapper(ActionProposalMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        ActionProposalMapper mapper = new SqlSessionTemplate(Objects.requireNonNull(factory)).getMapper(ActionProposalMapper.class);
        handler = new DatabaseHandler(jdbc);
        context = new AnnotationConfigApplicationContext();
        context.register(TxConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ActionProposalServiceImpl.class, () -> new ActionProposalServiceImpl(mapper, List.of(handler)));
        context.refresh();
        service = context.getBean(ActionProposalService.class);
    }

    @AfterAll
    void removeOnlyDedicatedSchema() throws Exception {
        if (context != null) context.close();
        if (dataSource != null) {
            try (Connection connection = bootstrap.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @BeforeEach
    void fixture() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)", owner);
        targetId = jdbc.queryForObject("INSERT INTO test_targets(user_id,title) VALUES (?, 'before') RETURNING id", Long.class, owner);
        handler.executions.set(0);
        handler.throwAfterWrite = false;
        handler.throwConflictAfterWrite = false;
        handler.async = false;
    }

    @Test
    void concurrentConfirmWithDifferentRequestIdsExecutesExactlyOnce() throws Exception {
        var card = service.prepare(owner, request("concurrent"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "device-a")); });
            var second = workers.submit(() -> { start.await(); return service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "device-b")); });
            start.countDown();
            assertEquals("SUCCEEDED", first.get(15, TimeUnit.SECONDS).getState());
            assertEquals("SUCCEEDED", second.get(15, TimeUnit.SECONDS).getState());
        } finally { workers.shutdownNow(); }
        assertEquals(1, handler.executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_effects WHERE target_id = ?", Integer.class, targetId));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM test_targets WHERE id = ?", Integer.class, targetId));
        assertEquals("APPLIED", service.get(owner, card.getId()).getResult().path("outcome").asText());
    }

    @Test
    void handlerFailureRollsBackBusinessAndProposalTogether() {
        var card = service.prepare(owner, request("rollback"));
        handler.throwAfterWrite = true;
        assertThrows(IllegalStateException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "fail")));
        assertEquals("PENDING", service.get(owner, card.getId()).getState());
        assertEquals(1, service.get(owner, card.getId()).getVersion());
        assertNull(service.get(owner, card.getId()).getResult());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_effects WHERE target_id = ?", Integer.class, targetId));
        assertEquals("before", jdbc.queryForObject("SELECT title FROM test_targets WHERE id = ?", String.class, targetId));
    }

    @Test
    void handlerConflictAfterWritesRollsBackDomainAndExecutingCardAndReturns409() throws Exception {
        var card = service.prepare(owner, request("handler-conflict"));
        handler.throwConflictAfterWrite = true;
        var mvc = MockMvcBuilders.standaloneSetup(new AssistantActionController(service)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.toString(), "test", List.of()));
        try {
            mvc.perform(post("/assistant/actions/" + card.getId() + "/confirm")
                            .contentType("application/json")
                            .content("{\"expectedVersion\":1,\"requestId\":\"handler-conflict\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409));
        } finally {
            SecurityContextHolder.clearContext();
        }
        var recovered = service.get(owner, card.getId());
        assertEquals("PENDING", recovered.getState());
        assertEquals(1, recovered.getVersion());
        assertNull(recovered.getExecutionVersion());
        assertNull(recovered.getResult());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_effects WHERE action_id = ?", Integer.class, card.getId()));
        assertEquals("before", jdbc.queryForObject("SELECT title FROM test_targets WHERE id = ?", String.class, targetId));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM test_targets WHERE id = ?", Integer.class, targetId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_jobs WHERE action_id = ?", Integer.class, card.getId()));
    }

    @Test
    void commitFailureDoesNotReturnSuccessOrPersistDomainEffects() {
        var card = service.prepare(owner, request("deferred-constraint"));
        jdbc.update("INSERT INTO test_effects(action_id,target_id) VALUES (?,?)", UUID.randomUUID(), targetId);
        assertThrows(org.springframework.transaction.TransactionSystemException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "commit-failure")));
        assertEquals("PENDING", service.get(owner, card.getId()).getState());
        assertEquals("before", jdbc.queryForObject("SELECT title FROM test_targets WHERE id = ?", String.class, targetId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_effects WHERE action_id = ?", Integer.class, card.getId()));
    }

    @Test
    void staleTargetCommitsStaleCardWithoutApplyingOldPayload() {
        var card = service.prepare(owner, request("stale"));
        jdbc.update("UPDATE test_targets SET version = version + 1, title = 'changed elsewhere' WHERE id = ?", targetId);
        ActionConflictException conflict = assertThrows(ActionConflictException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "stale")));
        assertEquals(409, conflict.getCode());
        assertEquals("STALE", service.get(owner, card.getId()).getState());
        assertEquals("changed elsewhere", jdbc.queryForObject("SELECT title FROM test_targets WHERE id = ?", String.class, targetId));
        assertEquals(0, handler.executions.get());
    }

    @Test
    void cancelledKeyRemainsDurablyCancelled() {
        var card = service.prepare(owner, request("cancelled-key"));
        service.cancel(owner, card.getId(), 1);
        assertEquals("CANCELLED", service.prepare(owner, request("cancelled-key")).getState());
        assertThrows(ActionConflictException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(2, "retry")));
        assertEquals(0, handler.executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assistant_actions WHERE user_id = ? AND prepare_key = 'cancelled-key'", Integer.class, owner));
    }

    @Test
    void lostAsyncResponseRecoversJobAndIgnoresStaleOrDuplicateCallbacks() {
        handler.async = true;
        var card = service.prepare(owner, request("async"));
        service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "lost-response"));
        var recovered = service.get(owner, card.getId());
        assertEquals("EXECUTING", recovered.getState());
        long jobId = recovered.getResult().path("jobIds").get(0).asLong();
        assertEquals("pending", jdbc.queryForObject("SELECT state FROM test_jobs WHERE id = ?", String.class, jobId));
        assertEquals("EXECUTING", service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "retry")).getState());
        assertEquals(1, handler.executions.get());
        service.completeAsync(card.getId(), recovered.getExecutionVersion() - 1, json("{\"outcome\":\"APPLIED\"}"));
        assertEquals("EXECUTING", service.get(owner, card.getId()).getState());
        service.completeAsync(card.getId(), recovered.getExecutionVersion(), json("{\"outcome\":\"SAVED_ONLY_FALLBACK\",\"recordId\":7}"));
        service.completeAsync(card.getId(), recovered.getExecutionVersion(), json("{\"outcome\":\"APPLIED\"}"));
        var actual = service.get(owner, card.getId());
        assertEquals("SUCCEEDED", actual.getState());
        assertEquals("SAVED_ONLY_FALLBACK", actual.getResult().path("outcome").asText());
        assertEquals("已仅保存，未加入记忆", actual.getResult().path("message").asText());
    }

    @Test
    void concurrentPreparationUsesOneUserKeyAndOtherUsersCanReuseKey() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return service.prepare(owner, request("same-key")); });
            var second = workers.submit(() -> { start.await(); return service.prepare(owner, request("same-key")); });
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS).getId(), second.get(15, TimeUnit.SECONDS).getId());
        } finally { workers.shutdownNow(); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assistant_actions WHERE user_id = ?", Integer.class, owner));
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)", other);
        long otherTarget = jdbc.queryForObject("INSERT INTO test_targets(user_id,title) VALUES (?, 'other') RETURNING id", Long.class, other);
        var body = new ActionPrepareDTO("test.update", json("{\"targetId\":" + otherTarget + ",\"title\":\"next\"}"), "same-key", null, null);
        assertEquals("PENDING", service.prepare(other, body).getState());
        assertEquals(0, handler.executions.get());
    }

    @Test
    void ownershipAndSessionSourcesSurviveActualMapping() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_sessions(id,user_id) VALUES (?,?)", sessionId, owner);
        long messageId = jdbc.queryForObject("INSERT INTO conversation_history(session_id,user_id,role) VALUES (?,?,'user') RETURNING id", Long.class, sessionId, owner);
        var body = request("source");
        body.setSourceSessionId(sessionId);
        body.setSourceMessageId(messageId);
        var card = service.prepare(owner, body);
        assertEquals(1, service.listForSession(owner, sessionId).size());
        assertEquals(messageId, service.get(owner, card.getId()).getSourceMessageId());
        UUID stranger = UUID.randomUUID();
        assertThrows(org.xianshen.mumirrorb.common.exception.BusinessException.class, () -> service.get(stranger, card.getId()));
        assertThrows(org.xianshen.mumirrorb.common.exception.BusinessException.class,
                () -> service.edit(stranger, card.getId(), new ActionEditDTO(1, json("{}"))));
        assertThrows(org.xianshen.mumirrorb.common.exception.BusinessException.class,
                () -> service.confirm(stranger, card.getId(), new ActionConfirmDTO(1, "foreign")));
        assertThrows(org.xianshen.mumirrorb.common.exception.BusinessException.class, () -> service.listForSession(stranger, sessionId));
    }

    @Test
    void editRoundTripsJsonAndInvalidatesOldConfirmationVersion() {
        var card = service.prepare(owner, request("edit"));
        var edited = service.edit(owner, card.getId(), new ActionEditDTO(1,
                json("{\"targetId\":" + targetId + ",\"title\":\"edited 中文\"}")));
        assertEquals(2, edited.getVersion());
        assertEquals("edited 中文", service.get(owner, card.getId()).getPayload().path("title").asText());
        assertThrows(ActionConflictException.class, () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "old-version")));
        assertEquals("SUCCEEDED", service.confirm(owner, card.getId(), new ActionConfirmDTO(2, "edited")).getState());
        assertEquals("edited 中文", jdbc.queryForObject("SELECT title FROM test_targets WHERE id = ?", String.class, targetId));
    }

    private ActionPrepareDTO request(String key) {
        return new ActionPrepareDTO("test.update", json("{\"targetId\":" + targetId + ",\"title\":\"after\"}"), key, null, null);
    }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Configuration
    @EnableTransactionManagement
    static class TxConfiguration {}

    private static class DatabaseHandler implements ActionHandler {
        final JdbcTemplate jdbc;
        final AtomicInteger executions = new AtomicInteger();
        boolean throwAfterWrite;
        boolean throwConflictAfterWrite;
        boolean async;

        DatabaseHandler(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Set<String> kinds() { return Set.of("test.update"); }
        @Override public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
            long targetId = payload.path("targetId").asLong();
            var target = jdbc.queryForMap("SELECT version,title FROM test_targets WHERE id = ? AND user_id = ? FOR UPDATE", targetId, userId);
            return JSON.createObjectNode().set("targetVersions", JSON.createObjectNode().put("target:" + targetId, (Integer) target.get("version")));
        }
        @Override public ActionExecution execute(AuthorizedAction action) {
            executions.incrementAndGet();
            long id = action.getPayload().path("targetId").asLong();
            jdbc.update("INSERT INTO test_effects(action_id,target_id) VALUES (?,?)", action.getActionId(), id);
            jdbc.update("UPDATE test_targets SET version = version + 1, title = ? WHERE id = ? AND user_id = ?",
                    action.getPayload().path("title").asText(), id, action.getUserId());
            if (throwAfterWrite) throw new IllegalStateException("Deliberate failure after domain writes");
            if (throwConflictAfterWrite) {
                ActionProposal original = new ActionProposal();
                original.setId(action.getActionId());
                original.setState("PENDING");
                original.setVersion(action.getExecutionVersion() - 1);
                throw new ActionConflictException("Deliberate target conflict after domain writes", original);
            }
            if (async) {
                long jobId = jdbc.queryForObject("INSERT INTO test_jobs(action_id) VALUES (?) RETURNING id", Long.class, action.getActionId());
                return ActionExecution.executing(json("{\"jobIds\":[" + jobId + "]}"));
            }
            return ActionExecution.applied(JSON.createObjectNode().put("id", id));
        }
    }
}
