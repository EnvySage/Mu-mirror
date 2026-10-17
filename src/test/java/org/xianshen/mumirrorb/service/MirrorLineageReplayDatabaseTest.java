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
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.service.impl.MirrorServiceImpl;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiGrpcClient aiGrpcClient;

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
        jdbcTemplate.update("DELETE FROM snapshot_inputs WHERE snapshot_id IN " +
                "(SELECT id FROM profile_snapshots WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM profile_snapshots WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM chunks WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM records WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_settings WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
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

        assertEquals(List.of(archiveOnlyMonth.toString(), authorizedRecordMonth.toString()),
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

    private ProfileSnapshot insertMonthly(String month, boolean invalidated, boolean current,
                                          OffsetDateTime createdAt) {
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .periodMonth(month)
                .versionNo(1)
                .isCurrent(current)
                .lineageTracked(true)
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
        jdbcTemplate.update("""
                INSERT INTO chunks (user_id, record_id, content, segment, metadata, ai_memory_approved)
                VALUES (?, ?, 'test chunk', 'test chunk', '{}'::jsonb, ?)
                """, userId, recordId, authorized);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(MirrorProperties.class)
    @MapperScan("org.xianshen.mumirrorb.mapper")
    @Import(MirrorServiceImpl.class)
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
