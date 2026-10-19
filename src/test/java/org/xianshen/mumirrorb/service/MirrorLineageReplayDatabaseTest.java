package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.service.impl.MirrorServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Local PostgreSQL coverage for durable versions and all-or-nothing lazy replay. */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
@SpringBootTest(
        classes = MirrorLineageReplayDatabaseTest.TestApplication.class,
        properties = {
                "spring.main.web-application-type=none",
                "spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource"
        }
)
@ActiveProfiles("test")
class MirrorLineageReplayDatabaseTest {

    @Autowired
    private MirrorServiceImpl mirrorService;

    @Autowired
    private ProfileSnapshotMapper snapshotMapper;

    @Autowired
    private ConversationHistoryMapper conversationHistoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiGrpcClient aiGrpcClient;

    @Autowired
    private SnapshotLineageService snapshotLineageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private String username;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MIRROR_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("MIRROR_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MIRROR_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("ALTER TABLE conversation_history ADD COLUMN IF NOT EXISTS context_source_message_ids JSONB NOT NULL DEFAULT '[]'::jsonb");
        // Recover only this class's uniquely tagged users if an earlier test process was interrupted.
        List<UUID> abandonedUsers = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE username LIKE 'mirror-replay-%'", UUID.class);
        for (UUID abandoned : abandonedUsers) {
            jdbcTemplate.update("DELETE FROM snapshot_inputs WHERE snapshot_id IN (SELECT id FROM profile_snapshots WHERE user_id=?)", abandoned);
            jdbcTemplate.update("DELETE FROM profile_snapshots WHERE user_id=?", abandoned);
            jdbcTemplate.update("DELETE FROM chunks WHERE user_id=?", abandoned);
            jdbcTemplate.update("DELETE FROM records WHERE user_id=?", abandoned);
            jdbcTemplate.update("DELETE FROM user_settings WHERE user_id=?", abandoned);
            jdbcTemplate.update("DELETE FROM chat_sessions WHERE user_id=?", abandoned);
            jdbcTemplate.update("DELETE FROM users WHERE id=?", abandoned);
        }
        reset(aiGrpcClient);
        when(aiGrpcClient.generateProfile(any(), any())).thenReturn(MirrorProfileProto.GenerateProfileResponse
                .newBuilder().build());
        userId = UUID.randomUUID();
        username = "mirror-replay-" + userId;
        jdbcTemplate.update("INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                userId, username, "integration-only");
        jdbcTemplate.update("INSERT INTO user_settings (user_id, ai_api_key, mirror_lookback) VALUES (?, ?, 1)",
                userId, "local-test-key");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM chat_sessions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM snapshot_inputs WHERE snapshot_id IN " +
                "(SELECT id FROM profile_snapshots WHERE user_id = ?)", userId);
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM snapshot_inputs si
                JOIN profile_snapshots ps ON ps.id = si.snapshot_id
                WHERE ps.user_id = ?
                """, Integer.class, userId));
        jdbcTemplate.update("DELETE FROM profile_snapshots WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM chunks WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM records WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_settings WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM profile_snapshots WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_source_exclusions WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assistant_actions WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_memories WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_day_summaries WHERE user_id = ?",
                Integer.class, userId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username LIKE 'mirror-replay-%'",
                Integer.class), "all users created by this integration fixture are removed after each case");
    }

    @Test
    void archivePageUsesFullPrecisionKeysetAndCountsDeletedSources() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-12T10:20:30.123456+08:00");
        ProfileSnapshot older = insertArchive("manual", null, false, true, createdAt);
        ProfileSnapshot current = insertArchive("manual", null, false, true, createdAt);
        ProfileSnapshot archived = insertArchive("monthly", "2026-08", true, false, createdAt);

        long softDeletedRecordId = insertRecord(createdAt, createdAt.plusDays(1));
        long removedRecordId = insertRecord(createdAt, null);
        jdbcTemplate.update("INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role) " +
                "VALUES (?, 'record', ?, 'stats'), (?, 'record', ?, 'stats')",
                archived.getId(), softDeletedRecordId, archived.getId(), removedRecordId);
        jdbcTemplate.update("DELETE FROM records WHERE id = ?", removedRecordId);

        var first = mirrorService.listSnapshots(userId, null, null, 1);
        assertEquals(List.of(archived.getId()), first.getItems().stream().map(item -> item.getId()).toList());
        assertEquals("archive_only", first.getItems().get(0).getContextStatus());
        assertEquals(2, first.getItems().get(0).getDeletedSourceCount());
        assertTrue(first.isHasMore());
        assertEquals(createdAt.toInstant(), first.getNextCreatedAt().toInstant());
        assertEquals(123_456_000, first.getNextCreatedAt().getNano());
        assertEquals(archived.getId(), first.getNextId());

        var second = mirrorService.listSnapshots(userId, first.getNextCreatedAt(), first.getNextId(), 1);
        assertEquals(List.of(current.getId()), second.getItems().stream().map(item -> item.getId()).toList());
        assertTrue(second.isHasMore());
        var third = mirrorService.listSnapshots(userId, second.getNextCreatedAt(), second.getNextId(), 1);
        assertEquals(List.of(older.getId()), third.getItems().stream().map(item -> item.getId()).toList());
        assertFalse(third.isHasMore());
    }

    @Test
    void lateAssistantReplyUsingForgottenMemoryIsExcludedFromFutureModelHistory() {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?)", sessionId, userId, "lineage-race");
        Long forgottenSource = jdbcTemplate.queryForObject("""
                INSERT INTO conversation_history (user_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', 'old memory source', now()) RETURNING id
                """, Long.class, userId, sessionId);
        jdbcTemplate.update("""
                INSERT INTO chat_source_exclusions(user_id,source_kind,source_id,reason)
                VALUES (?,'chat_message',?,'memory_forgotten')
                """, userId, forgottenSource);
        Long newerQuestion = jdbcTemplate.queryForObject("""
                INSERT INTO conversation_history (user_id, session_id, role, content, created_at)
                VALUES (?, ?, 'user', 'newer user turn', now() + interval '1 second') RETURNING id
                """, Long.class, userId, sessionId);
        Long laterReply = jdbcTemplate.queryForObject("""
                INSERT INTO conversation_history (user_id, session_id, role, content, context_source_message_ids, created_at)
                VALUES (?, ?, 'assistant', 'late paraphrase', jsonb_build_array(?::bigint), now() + interval '2 seconds') RETURNING id
                """, Long.class, userId, sessionId, forgottenSource);

        var modelHistory = conversationHistoryMapper.selectRecentForModel(sessionId, userId, 20);
        assertEquals(List.of(newerQuestion), modelHistory.stream().map(message -> message.getId()).toList(),
                "the newer user turn remains while its late assistant reply that consumed the forgotten source is excluded");
        assertTrue(conversationHistoryMapper.selectBySession(sessionId, userId).stream()
                        .anyMatch(message -> laterReply.equals(message.getId()) && "late paraphrase".equals(message.getContent())),
                "the retained UI history remains readable");
        assertEquals(List.of(laterReply), conversationHistoryMapper.selectAssistantRepliesDependingOn(userId, new Long[]{forgottenSource}));
    }

    @Test
    void rebuildMonthCandidatesCombineAuthorizedRecordsAndArchivePeriod() {
        YearMonth currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        YearMonth archiveOnlyMonth = currentMonth.minusMonths(3);
        YearMonth availableMonth = currentMonth.minusMonths(2);
        YearMonth authorizedRecordMonth = currentMonth.minusMonths(1);
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        ProfileSnapshot archive = insertArchive("monthly", archiveOnlyMonth.toString(), true, true, now);
        ProfileSnapshot available = insertArchive("monthly", availableMonth.toString(), false, true, now.plusSeconds(1));
        ProfileSnapshot oldVersion = insertArchive("monthly", availableMonth.toString(), false, false,
                now.plusSeconds(2));
        assertNotNull(archive.getId());
        assertNotNull(available.getId());

        long authorizedRecord = insertRecord(authorizedRecordMonth.atDay(10)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), null);
        long currentRecord = insertRecord(currentMonth.atDay(2)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), null);
        long unauthorizedRecord = insertRecord(authorizedRecordMonth.atDay(11)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), null);
        insertChunk(authorizedRecord, true);
        insertChunk(currentRecord, true);
        insertChunk(unauthorizedRecord, false);

        assertEquals(List.of(archiveOnlyMonth.toString(), availableMonth.toString(), authorizedRecordMonth.toString()),
                mirrorService.snapshotMonths(userId));
        List<Long> activeContextIds = snapshotMapper.selectAllByUser(userId).stream()
                .map(ProfileSnapshot::getId).toList();
        assertTrue(activeContextIds.contains(available.getId()));
        assertFalse(activeContextIds.contains(archive.getId()));
        assertFalse(activeContextIds.contains(oldVersion.getId()));
        assertNull(snapshotMapper.selectActiveById(userId, oldVersion.getId()));
    }

    @Test
    void regenerationKeepsPreviousVersionAndStoresParentMirrorDependency() {
        ProfileSnapshot july = insertMonthly("2026-07", false, true,
                OffsetDateTime.parse("2026-08-01T02:00:00+08:00"));
        ProfileSnapshot oldAugust = insertMonthly("2026-08", true, true,
                OffsetDateTime.parse("2026-09-01T02:00:00+08:00"));

        mirrorService.generateMonthlyFor(userId, "2026-08");

        ProfileSnapshot archived = snapshotMapper.selectById(oldAugust.getId());
        assertTrue(archived.getInvalidated());
        assertFalse(archived.getIsCurrent());
        List<ProfileSnapshot> augustVersions = snapshotMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                        .eq(ProfileSnapshot::getUserId, userId)
                        .eq(ProfileSnapshot::getPeriodMonth, "2026-08")
                        .orderByAsc(ProfileSnapshot::getVersionNo));
        assertEquals(2, augustVersions.size());
        ProfileSnapshot replacement = augustVersions.get(1);
        assertEquals(2, replacement.getVersionNo());
        assertTrue(replacement.getIsCurrent());
        assertFalse(replacement.getInvalidated());
        assertTrue(replacement.getLineageTracked());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM snapshot_inputs
                WHERE snapshot_id = ? AND input_kind = 'snapshot' AND input_id = ? AND input_role = 'parent'
                """, Integer.class, replacement.getId(), july.getId()));
    }

    @Test
    void failedReplayRollsBackEveryNewVersionAndCurrentMarker() {
        insertMonthly("2026-07", false, true,
                OffsetDateTime.parse("2026-08-01T02:00:00+08:00"));
        ProfileSnapshot oldAugust = insertMonthly("2026-08", true, true,
                OffsetDateTime.parse("2026-09-01T02:00:00+08:00"));
        ProfileSnapshot oldSeptember = insertMonthly("2026-09", true, true,
                OffsetDateTime.parse("2026-10-01T02:00:00+08:00"));
        when(aiGrpcClient.generateProfile(eq(userId), any()))
                .thenReturn(MirrorProfileProto.GenerateProfileResponse.newBuilder().build())
                .thenThrow(new IllegalStateException("simulated September AI failure"));

        assertThrows(IllegalStateException.class, () -> mirrorService.generateMonthlyFor(userId, "2026-09"));

        assertEquals(2L, mockingDetails(aiGrpcClient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("generateProfile")).count());
        assertTrue(snapshotMapper.selectById(oldAugust.getId()).getIsCurrent());
        assertTrue(snapshotMapper.selectById(oldAugust.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(oldSeptember.getId()).getIsCurrent());
        assertTrue(snapshotMapper.selectById(oldSeptember.getId()).getInvalidated());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM profile_snapshots WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void legacyAncestorThatIsNoLongerCurrentStillInvalidatesItsDescendants() {
        ProfileSnapshot legacyAugust = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .periodMonth("2026-08")
                .versionNo(1)
                .isCurrent(false)
                .lineageTracked(false)
                .invalidated(false)
                .overallSummary("legacy August archive")
                .createdAt(OffsetDateTime.parse("2026-09-01T02:00:00+08:00"))
                .build();
        snapshotMapper.insert(legacyAugust);

        ProfileSnapshot septemberChild = insertMonthly("2026-09", false, true,
                OffsetDateTime.parse("2026-10-01T02:00:00+08:00"));
        jdbcTemplate.update("""
                INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
                VALUES (?, 'snapshot', ?, 'parent')
                """, septemberChild.getId(), legacyAugust.getId());

        ProfileSnapshot augustReplacement = insertMonthly("2026-08", false, true,
                OffsetDateTime.parse("2026-10-02T02:00:00+08:00"));
        long sourceRecordId = insertRecord(OffsetDateTime.parse("2026-08-12T10:00:00+08:00"), null);
        insertChunk(sourceRecordId, true);
        jdbcTemplate.update("""
                INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
                VALUES (?, 'record', ?, 'stats')
                """, augustReplacement.getId(), sourceRecordId);

        snapshotLineageService.invalidateSource(userId, "record", sourceRecordId, "record_deleted");

        assertTrue(snapshotMapper.selectById(augustReplacement.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(legacyAugust.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(septemberChild.getId()).getInvalidated());
    }

    @Test
    void forgottenChatSourceInvalidatesKnownDependentsAndUnknownLegacyDescendantsOnly() {
        ProfileSnapshot chatDependent = insertMonthly("2026-06", false, false,
                OffsetDateTime.parse("2026-07-01T02:00:00+08:00"));
        snapshotLineageService.recordChatMessageInputs(chatDependent.getId(), List.of(81001L, 81002L));

        ProfileSnapshot knownDescendant = insertMonthly("2026-07", false, false,
                OffsetDateTime.parse("2026-08-01T02:00:00+08:00"));
        jdbcTemplate.update("""
                INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
                VALUES (?, 'snapshot', ?, 'parent')
                """, knownDescendant.getId(), chatDependent.getId());

        ProfileSnapshot unrelatedRecordSnapshot = insertMonthly("2026-08", false, false,
                OffsetDateTime.parse("2026-09-01T02:00:00+08:00"));
        long unrelatedRecord = insertRecord(OffsetDateTime.parse("2026-08-12T10:00:00+08:00"), null);
        jdbcTemplate.update("""
                INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
                VALUES (?, 'record', ?, 'stats')
                """, unrelatedRecordSnapshot.getId(), unrelatedRecord);

        ProfileSnapshot legacyUnknown = ProfileSnapshot.builder()
                .userId(userId).snapshotType("manual").versionNo(1).isCurrent(true)
                .lineageTracked(true).chatLineageKnown(false).invalidated(false)
                .overallSummary("legacy unknown chat lineage").createdAt(OffsetDateTime.now()).build();
        snapshotMapper.insert(legacyUnknown);
        ProfileSnapshot legacyDescendant = insertMonthly("2026-09", false, false,
                OffsetDateTime.parse("2026-10-01T02:00:00+08:00"));
        jdbcTemplate.update("""
                INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
                VALUES (?, 'snapshot', ?, 'parent')
                """, legacyDescendant.getId(), legacyUnknown.getId());

        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM snapshot_inputs
                WHERE snapshot_id = ? AND input_kind = 'chat_message' AND input_role = 'memory_source'
                """, Integer.class, chatDependent.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM profile_snapshots
                WHERE user_id = ? AND invalidated = TRUE
                """, Integer.class, userId), "migration must preserve historical rows until a forget event");

        snapshotLineageService.invalidateForForgottenChatSources(userId, List.of(81001L, 81002L));

        assertTrue(snapshotMapper.selectById(chatDependent.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(knownDescendant.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(legacyUnknown.getId()).getInvalidated());
        assertTrue(snapshotMapper.selectById(legacyDescendant.getId()).getInvalidated());
        assertFalse(snapshotMapper.selectById(unrelatedRecordSnapshot.getId()).getInvalidated());
        assertEquals(4, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM profile_snapshots
                WHERE user_id = ? AND invalidated = TRUE
                """, Integer.class, userId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM profile_snapshots
                WHERE user_id = ? AND id = ?
                """, Integer.class, userId, legacyUnknown.getId()),
                "invalidated legacy archive remains available for human history");
    }

    @Test
    void lateSnapshotSaveAfterForgetIsRejectedAndRollsBackTheSnapshot() {
        long forgottenMessageId = 82001L;
        jdbcTemplate.update("""
                INSERT INTO chat_source_exclusions (user_id, source_kind, source_id, reason)
                VALUES (?, 'chat_message', ?, 'memory_forgotten')
                """, userId, forgottenMessageId);
        snapshotLineageService.invalidateForForgottenChatSources(userId, List.of(forgottenMessageId));
        int snapshotsBeforeLateResult = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM profile_snapshots WHERE user_id = ?", Integer.class, userId);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            ProfileSnapshot lateSnapshot = ProfileSnapshot.builder()
                    .userId(userId).snapshotType("manual").versionNo(1).isCurrent(true)
                    .lineageTracked(true).chatLineageKnown(true).invalidated(false)
                    .overallSummary("stale LLM result").createdAt(OffsetDateTime.now()).build();
            snapshotMapper.insert(lateSnapshot);
            snapshotLineageService.recordChatMessageInputs(lateSnapshot.getId(), List.of(forgottenMessageId));
            return null;
        }));

        assertEquals(snapshotsBeforeLateResult, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM profile_snapshots WHERE user_id = ?", Integer.class, userId),
                "the surrounding snapshot transaction must roll back when a source was forgotten");
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM snapshot_inputs si
                JOIN profile_snapshots ps ON ps.id = si.snapshot_id
                WHERE ps.user_id = ? AND si.input_kind = 'chat_message'
                """, Integer.class, userId));
    }

    @Test
    void replayIncludesAuthorizedMonthsMissingFromArchiveAndMarksUnrecoverableGap() {
        YearMonth currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        YearMonth archivedMonth = currentMonth.minusMonths(3);
        YearMonth authorizedMonth = currentMonth.minusMonths(2);
        YearMonth targetMonth = currentMonth.minusMonths(1);
        ProfileSnapshot unrecoverableArchive = insertMonthly(archivedMonth.toString(), true, true,
                archivedMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());

        long activeRecordId = insertRecord(authorizedMonth.atDay(12).atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), null);
        insertChunk(activeRecordId, true);

        mirrorService.generateMonthlyFor(userId, targetMonth.toString());

        List<ProfileSnapshot> rebuilt = snapshotMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                        .eq(ProfileSnapshot::getUserId, userId)
                        .eq(ProfileSnapshot::getSnapshotType, "monthly")
                        .eq(ProfileSnapshot::getIsCurrent, true)
                        .eq(ProfileSnapshot::getInvalidated, false)
                        .orderByAsc(ProfileSnapshot::getPeriodMonth));
        assertEquals(List.of(authorizedMonth.toString(), targetMonth.toString()),
                rebuilt.stream().map(ProfileSnapshot::getPeriodMonth).toList());
        assertTrue(rebuilt.get(0).getArchiveDiscontinuity());
        assertTrue(rebuilt.get(1).getArchiveDiscontinuity());
        assertFalse(snapshotMapper.selectById(unrecoverableArchive.getId()).getIsCurrent(),
                "an unreconstructable invalidated archive must no longer remain a replay trigger");

        mirrorService.generateMonthlyFor(userId, targetMonth.toString());

        verify(aiGrpcClient, times(3)).generateProfile(eq(userId), any());
    }

    @Test
    void sourceInvalidationWaitsForInFlightGenerationThenInvalidatesItsSnapshot() throws Exception {
        YearMonth targetMonth = YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
        long sourceRecordId = insertRecord(targetMonth.atDay(12).atTime(9, 0)
                .atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(), null);
        insertChunk(sourceRecordId, true);
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            aiStarted.countDown();
            assertTrue(releaseAi.await(10, TimeUnit.SECONDS), "generation test was not released");
            return MirrorProfileProto.GenerateProfileResponse.newBuilder().build();
        }).when(aiGrpcClient).generateProfile(eq(userId), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> generation = executor.submit(() -> mirrorService.generateMonthlyFor(userId, targetMonth.toString()));
            assertTrue(aiStarted.await(5, TimeUnit.SECONDS), "AI generation did not start");
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            Future<?> invalidation = executor.submit(() -> {
                mutationStarted.countDown();
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update("UPDATE records SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", sourceRecordId);
                    snapshotLineageService.invalidateSource(userId, "record", sourceRecordId, "record_deleted");
                    return null;
                });
            });
            assertTrue(mutationStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> invalidation.get(250, TimeUnit.MILLISECONDS));
            releaseAi.countDown();
            generation.get(10, TimeUnit.SECONDS);
            invalidation.get(10, TimeUnit.SECONDS);

            ProfileSnapshot generated = snapshotMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileSnapshot>()
                            .eq(ProfileSnapshot::getUserId, userId)
                            .eq(ProfileSnapshot::getPeriodMonth, targetMonth.toString())
                            .eq(ProfileSnapshot::getIsCurrent, true));
            assertNotNull(generated);
            assertTrue(generated.getInvalidated(), "the just-created snapshot must be invalidated after the source deletion");
        } finally {
            releaseAi.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ProfileSnapshot insertMonthly(String month, boolean invalidated, boolean current,
                                          OffsetDateTime createdAt) {
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .periodMonth(month)
                .versionNo(1)
                .isCurrent(current)
                .lineageTracked(true)
                .chatLineageKnown(true)
                .invalidated(invalidated)
                .invalidatedAt(invalidated ? createdAt : null)
                .invalidatedReason(invalidated ? "record_deleted" : null)
                .overallSummary(month + " archive")
                .createdAt(createdAt)
                .build();
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private ProfileSnapshot insertArchive(String type, String month, boolean invalidated, boolean current,
                                          OffsetDateTime createdAt) {
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType(type)
                .periodMonth(month)
                .versionNo(1)
                .isCurrent(current)
                .lineageTracked(true)
                .chatLineageKnown(true)
                .invalidated(invalidated)
                .invalidatedAt(invalidated ? createdAt : null)
                .invalidatedReason(invalidated ? "record_deleted" : null)
                .overallSummary("archive " + month)
                .createdAt(createdAt)
                .build();
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private long insertRecord(OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO records (user_id, content, source, status, created_at, deleted_at)
                VALUES (?, 'test record', 'user', 'done', ?, ?)
                RETURNING id
                """, Long.class, userId, createdAt, deletedAt);
    }

    private void insertChunk(long recordId, boolean authorized) {
        if (authorized) {
            jdbcTemplate.update("UPDATE records SET memory_state = 'READY' WHERE id = ? AND user_id = ?", recordId, userId);
            jdbcTemplate.update("""
                    INSERT INTO chunks (user_id, record_id, content, segment, metadata, ai_memory_approved,
                                        embedding, embedding_status)
                    VALUES (?, ?, 'test chunk', 'test chunk', '{}'::jsonb, TRUE, ?::vector, 'ready')
                    """, userId, recordId, vectorLiteral());
        } else {
            jdbcTemplate.update("""
                    INSERT INTO chunks (user_id, record_id, content, segment, metadata, ai_memory_approved,
                                        embedding_status)
                    VALUES (?, ?, 'test chunk', 'test chunk', '{}'::jsonb, FALSE, 'not_requested')
                    """, userId, recordId);
        }
    }

    private String vectorLiteral() {
        return "[1" + ",0".repeat(1023) + "]";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(MirrorProperties.class)
    @MapperScan("org.xianshen.mumirrorb.mapper")
    @Import({MirrorServiceImpl.class, SnapshotLineageService.class})
    static class TestApplication {
        @Bean
        AiGrpcClient aiGrpcClient() {
            return mock(AiGrpcClient.class);
        }

        @Bean
        GlossaryService glossaryService() {
            return mock(GlossaryService.class);
        }
    }
}
