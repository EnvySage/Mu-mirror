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
        jdbcTemplate.update("DELETE FROM user_settings WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
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
