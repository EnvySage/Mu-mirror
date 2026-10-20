package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContext;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.AgentCommandReceiptMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.ToolCallMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentCommandReceipt;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** PostgreSQL proof that record, receipt, and Java audit share one transaction and run lock. */
@Tag("integration")
class AgentCommandTransactionIT {

    private String url;
    private String dbUser;
    private String password;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID sessionId;
    private UUID runId;

    @BeforeEach
    void setUp() throws Exception {
        url = System.getenv("MIRROR_AGENT_TEST_JDBC_URL");
        dbUser = System.getenv("MIRROR_AGENT_TEST_JDBC_USER");
        password = System.getenv("MIRROR_AGENT_TEST_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "set MIRROR_AGENT_TEST_JDBC_URL to an isolated PostgreSQL test database");
        Assumptions.assumeTrue(dbUser != null && !dbUser.isBlank(), "set MIRROR_AGENT_TEST_JDBC_USER");
        Assumptions.assumeTrue(password != null, "set MIRROR_AGENT_TEST_JDBC_PASSWORD (may be empty for trust auth)");
        Assumptions.assumeTrue(url.toLowerCase().contains("_test"), "integration URL must clearly name a test database");
        dataSource = new DriverManagerDataSource(url, dbUser, password);
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        runId = UUID.randomUUID();
        insertFixture();
    }

    @AfterEach
    void tearDown() {
        if (jdbc == null || userId == null) return;
        jdbc.update("DELETE FROM agent_command_receipts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tool_calls WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM records WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM agent_runs WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void concurrentSameCallCreatesOneRecordOneReceiptAndOneAudit() throws Exception {
        AtomicInteger recordCreates = new AtomicInteger();
        AgentCommandService service = service(new AtomicBoolean(false), recordCreates);
        VerifiedGrant grant = grant("今天完成了镜子聊天功能");
        UUID callId = AgentWriteGrantService.callId(runId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentCommandResponse> first = workers.submit(() -> invoke(service, grant, callId, ready, start));
            Future<AgentCommandResponse> second = workers.submit(() -> invoke(service, grant, callId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            AgentCommandResponse firstResult = first.get(10, TimeUnit.SECONDS);
            AgentCommandResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstResult.record().id(), secondResult.record().id());
            assertEquals(1, (firstResult.replayed() ? 1 : 0) + (secondResult.replayed() ? 1 : 0));
            assertEquals(1, recordCreates.get());
            assertEquals(1, count("SELECT count(*) FROM records WHERE user_id = ?", userId));
            assertEquals(1, count("SELECT count(*) FROM agent_command_receipts WHERE user_id = ? AND call_id = ?", userId, callId));
            assertEquals(1, count("SELECT count(*) FROM tool_calls WHERE user_id = ? AND run_id = ? AND call_id = ?",
                    userId, runId, callId.toString()));
            assertEquals("今天完成了镜子聊天功能", jdbc.queryForObject(
                    "SELECT content FROM records WHERE user_id = ?", String.class, userId));
            assertEquals("record_only", jdbc.queryForObject(
                    "SELECT status FROM records WHERE user_id = ?", String.class, userId));
            assertEquals(false, jdbc.queryForObject(
                    "SELECT ai_processing_authorized FROM records WHERE user_id = ?", Boolean.class, userId));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void failureAfterRecordAndAuditInsertsRollsBackAllThreeRows() {
        AgentCommandService service = service(new AtomicBoolean(true), new AtomicInteger());
        VerifiedGrant grant = grant("事务回滚注入" );
        UUID callId = AgentWriteGrantService.callId(runId);

        AgentCommandException error = assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, runId, callId, "事务回滚注入"));

        assertEquals("WRITE_UNAVAILABLE", error.getCode());
        assertEquals(0, count("SELECT count(*) FROM records WHERE user_id = ?", userId));
        assertEquals(0, count("SELECT count(*) FROM agent_command_receipts WHERE user_id = ? AND call_id = ?", userId, callId));
        assertEquals(0, count("SELECT count(*) FROM tool_calls WHERE user_id = ? AND run_id = ? AND call_id = ?",
                userId, runId, callId.toString()));
    }

    private AgentCommandService service(AtomicBoolean failAfterAudit, AtomicInteger recordCreates) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentCommandReceiptMapper receipts = new JdbcReceiptMapper(mapper);
        RecordMapper recordMapper = mock(RecordMapper.class);
        doAnswer(invocation -> {
            var record = (org.xianshen.mumirrorb.pojo.DO.Record) invocation.getArgument(0);
            recordCreates.incrementAndGet();
            Long id = jdbc.queryForObject("""
                    INSERT INTO records(user_id, content, source, status, ai_processing_authorized,
                                        processing_version, user_reviewed, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, record.getUserId(), record.getContent(), record.getSource(),
                    record.getStatus().getValue(), record.getAiProcessingAuthorized(), record.getProcessingVersion(),
                    record.getUserReviewed(), record.getCreatedAt(), record.getUpdatedAt());
            record.setId(id);
            return 1;
        }).when(recordMapper).insert(any(org.xianshen.mumirrorb.pojo.DO.Record.class));
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        org.mockito.Mockito.when(chunkMapper.selectList(any())).thenReturn(java.util.List.of());
        RecordService records = new RecordServiceImpl(recordMapper, chunkMapper,
                mock(ProfileSnapshotMapper.class), mock(ApplicationEventPublisher.class), mock(AiGrpcClient.class),
                mock(ApplicationContext.class));

        ToolCallMapper audits = mock(ToolCallMapper.class);
        doAnswer(invocation -> {
            var audit = (org.xianshen.mumirrorb.pojo.DO.ToolCall) invocation.getArgument(0);
            String args = mapper.writeValueAsString(audit.getArgs());
            jdbc.update("""
                    INSERT INTO tool_calls(user_id, session_id, tool, args, result_summary, success, latency_ms,
                                           run_id, call_id, engine, code)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                    """, audit.getUserId(), audit.getSessionId(), audit.getTool(), args, audit.getResultSummary(),
                    audit.getSuccess(), audit.getLatencyMs(), audit.getRunId(), audit.getCallId(), audit.getEngine(), audit.getCode());
            if (failAfterAudit.compareAndSet(true, false)) throw new IllegalStateException("injected after record and audit insert");
            return 1;
        }).when(audits).insert(any());

        AgentRunRepository runs = new AgentRunRepository(jdbc, null);
        AgentCommandService target = new AgentCommandService(runs, receipts, records, audits, mapper);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor tx = new TransactionInterceptor();
        tx.setTransactionManager(txManager);
        tx.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(tx);
        return (AgentCommandService) proxy.getProxy();
    }

    private AgentCommandResponse invoke(AgentCommandService service, VerifiedGrant grant, UUID callId,
                                       CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
        return service.createRecord(grant, runId, callId, "今天完成了镜子聊天功能");
    }

    private VerifiedGrant grant(String content) {
        return new VerifiedGrant(userId, sessionId, runId, "create_record", "create_record:0",
                AgentWriteGrantService.sha256(content), false, OffsetDateTime.now().plusMinutes(2));
    }

    private void insertFixture() throws Exception {
        String username = "agent_command_it_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?, ?, 'integration-fixture')", userId, username);
        jdbc.update("""
                INSERT INTO agent_runs(run_id, user_id, session_id, client_request_id, request_hash,
                                       engine, mode, question_hash, status, lease_expires_at)
                VALUES (?, ?, ?, ?, ?, 'graph_v2', 'live', ?, 'running', NOW() + INTERVAL '2 minutes')
                """, runId, userId, sessionId, UUID.randomUUID(), "1".repeat(64), "2".repeat(64));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private final class JdbcReceiptMapper implements AgentCommandReceiptMapper {
        private final ObjectMapper mapper;
        private JdbcReceiptMapper(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public AgentCommandReceipt findByUserAndCall(UUID owner, UUID callId) {
            return select("SELECT user_id, call_id, run_id, command_name, request_hash, result_json::text AS result_json, created_at "
                    + "FROM agent_command_receipts WHERE user_id = ? AND call_id = ?", owner, callId);
        }

        @Override
        public AgentCommandReceipt findByRunAndCall(UUID owner, UUID run, UUID callId) {
            return select("SELECT user_id, call_id, run_id, command_name, request_hash, result_json::text AS result_json, created_at "
                    + "FROM agent_command_receipts WHERE user_id = ? AND run_id = ? AND call_id = ?", owner, run, callId);
        }

        @Override
        public int insert(AgentCommandReceipt receipt) {
            return jdbc.update("""
                    INSERT INTO agent_command_receipts(user_id, call_id, run_id, command_name, request_hash, result_json)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, receipt.getUserId(), receipt.getCallId(), receipt.getRunId(), receipt.getCommand(),
                    receipt.getRequestHash(), receipt.getResultJson());
        }

        private AgentCommandReceipt select(String sql, Object... args) {
            return jdbc.query(sql, rs -> rs.next() ? AgentCommandReceipt.builder()
                    .userId(rs.getObject("user_id", UUID.class)).callId(rs.getObject("call_id", UUID.class))
                    .runId(rs.getObject("run_id", UUID.class)).command(rs.getString("command_name"))
                    .requestHash(rs.getString("request_hash")).resultJson(rs.getString("result_json"))
                    .createdAt(rs.getObject("created_at", OffsetDateTime.class)).build() : null, args);
        }
    }
}
