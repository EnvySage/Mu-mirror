package org.xianshen.mumirrorb.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL-only concurrency proof for the two run uniqueness indexes. */
@Tag("integration")
class AgentRunConcurrencyIT {

    @Test
    void activeSessionAndRequestIndexesRejectConcurrentDuplicates() throws Exception {
        String url = System.getenv("MIRROR_AGENT_TEST_JDBC_URL");
        String user = System.getenv("MIRROR_AGENT_TEST_JDBC_USER");
        String password = System.getenv("MIRROR_AGENT_TEST_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "set MIRROR_AGENT_TEST_JDBC_URL to an isolated PostgreSQL test database");
        Assumptions.assumeTrue(user != null && !user.isBlank(), "set MIRROR_AGENT_TEST_JDBC_USER");
        Assumptions.assumeTrue(password != null, "set MIRROR_AGENT_TEST_JDBC_PASSWORD (may be empty for trust auth)");
        Assumptions.assumeTrue(url.toLowerCase().contains("_test"), "integration URL must clearly name a test database");

        UUID userId = UUID.randomUUID();
        String username = "agent_run_it_" + UUID.randomUUID().toString().replace("-", "");
        UUID sessionId = UUID.randomUUID();
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondReachedInsert = new CountDownLatch(1);
        try {
            insertUser(url, user, password, userId, username);
            Future<Boolean> first = workers.submit(() -> {
                try (Connection connection = connect(url, user, password)) {
                    connection.setAutoCommit(false);
                    insert(connection, UUID.randomUUID(), userId, sessionId, firstRequest);
                    firstInserted.countDown();
                    if (!allowFirstCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("commit latch timed out");
                    connection.commit();
                    return true;
                }
            });
            assertTrue(firstInserted.await(5, TimeUnit.SECONDS));
            Future<String> concurrent = workers.submit(() -> {
                try (Connection connection = connect(url, user, password)) {
                    connection.setAutoCommit(false);
                    secondReachedInsert.countDown();
                    try {
                        insert(connection, UUID.randomUUID(), userId, sessionId, secondRequest);
                        connection.commit();
                        return "inserted";
                    } catch (SQLException error) {
                        connection.rollback();
                        return error.getSQLState();
                    }
                }
            });
            assertTrue(secondReachedInsert.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            allowFirstCommit.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertEquals("23505", concurrent.get(5, TimeUnit.SECONDS), "active live session must be unique");

            try (Connection connection = connect(url, user, password)) {
                connection.setAutoCommit(false);
                try {
                    insert(connection, UUID.randomUUID(), userId, UUID.randomUUID(), firstRequest);
                    connection.commit();
                    throw new AssertionError("user/request id duplicate unexpectedly inserted");
                } catch (SQLException error) {
                    connection.rollback();
                    assertEquals("23505", error.getSQLState(), "user/request id must be unique across sessions");
                }
            }
        } finally {
            allowFirstCommit.countDown();
            workers.shutdownNow();
            deleteFixture(url, user, password, userId);
        }
    }

    @Test
    void retentionDeletesCompletedRunsOlderThanThirtyDays() throws Exception {
        String url = System.getenv("MIRROR_AGENT_TEST_JDBC_URL");
        String user = System.getenv("MIRROR_AGENT_TEST_JDBC_USER");
        String password = System.getenv("MIRROR_AGENT_TEST_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "set MIRROR_AGENT_TEST_JDBC_URL to an isolated PostgreSQL test database");
        Assumptions.assumeTrue(user != null && !user.isBlank(), "set MIRROR_AGENT_TEST_JDBC_USER");
        Assumptions.assumeTrue(password != null, "set MIRROR_AGENT_TEST_JDBC_PASSWORD (may be empty for trust auth)");
        Assumptions.assumeTrue(url.toLowerCase().contains("_test"), "integration URL must clearly name a test database");

        UUID userId = UUID.randomUUID();
        String username = "ar_ret_" + UUID.randomUUID().toString().replace("-", "");
        UUID runId = UUID.randomUUID();
        try {
            insertUser(url, user, password, userId, username);
            try (Connection connection = connect(url, user, password);
                 var insert = connection.prepareStatement("""
                         INSERT INTO agent_runs(
                             run_id, user_id, session_id, client_request_id, request_hash,
                             engine, mode, question_hash, status, finished_at, lease_expires_at
                         ) VALUES (?, ?, ?, ?, ?, 'legacy', 'live', ?, 'completed',
                                   NOW() - INTERVAL '31 days', NOW())
                         """)) {
                insert.setObject(1, runId);
                insert.setObject(2, userId);
                insert.setObject(3, UUID.randomUUID());
                insert.setObject(4, UUID.randomUUID());
                insert.setString(5, "1".repeat(64));
                insert.setString(6, "2".repeat(64));
                insert.executeUpdate();
            }

            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
            AgentRunRetentionService retention = new AgentRunRetentionService(
                    AgentRunMapperTestSupport.mapper(dataSource));
            int deleted = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                    .execute(status -> retention.deleteExpired(OffsetDateTime.now().minusDays(30)));
            assertEquals(1, deleted);
            try (Connection connection = connect(url, user, password);
                 var query = connection.prepareStatement("SELECT count(*) FROM agent_runs WHERE run_id = ?")) {
                query.setObject(1, runId);
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
        } finally {
            deleteFixture(url, user, password, userId);
        }
    }

    private static void insertUser(String url, String user, String password, UUID userId, String username) throws SQLException {
        try (Connection connection = connect(url, user, password)) {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO users(id, username, password_hash) VALUES (?, ?, 'integration-fixture')")) {
                insert.setObject(1, userId);
                insert.setString(2, username);
                insert.executeUpdate();
            }
        }
    }

    private static void deleteFixture(String url, String user, String password, UUID userId) throws SQLException {
        try (Connection connection = connect(url, user, password); var deleteRuns = connection.prepareStatement(
                "DELETE FROM agent_runs WHERE user_id = ?")) {
            deleteRuns.setObject(1, userId);
            deleteRuns.executeUpdate();
            try (var deleteUser = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                deleteUser.setObject(1, userId);
                deleteUser.executeUpdate();
            }
        }
    }

    private static void insert(Connection connection, UUID runId, UUID userId,
                               UUID sessionId, UUID requestId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_runs(
                    run_id, user_id, session_id, client_request_id, request_hash,
                    engine, mode, question_hash, status, lease_expires_at
                ) VALUES (?, ?, ?, ?, ?, 'legacy', 'live', ?, 'running', NOW() + INTERVAL '1 minute')
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, userId);
            statement.setObject(3, sessionId);
            statement.setObject(4, requestId);
            statement.setString(5, "1".repeat(64));
            statement.setString(6, "2".repeat(64));
            statement.executeUpdate();
        }
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
