package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.GrowthProperties;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.GrowthEntryMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO;
import org.xianshen.mumirrorb.pojo.VO.GrowthOverviewVO;
import org.xianshen.mumirrorb.service.impl.GrowthServiceImpl;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** PostgreSQL integration coverage. Uses only the explicitly supplied disposable test database. */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
@SpringBootTest(classes = GrowthLedgerDatabaseTest.TestApplication.class, properties = {
        "spring.main.web-application-type=none",
        "spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource"
})
@ActiveProfiles("test")
class GrowthLedgerDatabaseTest {
    @Autowired private GrowthService growth;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecordMapper records;
    @Autowired private GrowthEntryMapper entries;
    private UUID userId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MIRROR_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("MIRROR_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MIRROR_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?, ?, ?)", userId, "growth-db-" + userId, "test-only");
    }

    @AfterEach
    void removeUser() {
        if (userId == null) return;
        jdbc.update("DELETE FROM growth_entry_history WHERE user_id=?", userId);
        jdbc.update("DELETE FROM growth_axis_history WHERE user_id=?", userId);
        jdbc.update("DELETE FROM growth_entries WHERE user_id=?", userId);
        jdbc.update("DELETE FROM records WHERE user_id=?", userId);
        jdbc.update("DELETE FROM growth_axes WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    @Test
    void manualRetryCorrectionAndAxisRenamePreserveTheLedgerSnapshot() {
        var initial = growth.overview(userId);
        assertEquals(5, initial.getAxes().size());
        assertEquals(5, initial.getAxesVersion());
        var originalAxis = initial.getAxes().get(0);

        UUID requestId = UUID.randomUUID();
        GrowthAdjustmentDTO manual = adjustment(requestId, originalAxis.getId(), 10, "完成一次练习");
        growth.adjustManual(userId, manual);

        List<GrowthProperties.Axis> renamed = growth.axes(userId).stream()
                .map(axis -> new GrowthProperties.Axis(axis.getId(), axis.getName(), axis.getDescription())).toList();
        renamed.get(0).setName("我定义的学识");
        growth.updateAxes(userId, initial.getAxesVersion(), renamed);
        assertEquals(6, growth.axesVersion(userId));

        // Retrying with the same request key cannot create a second entry.
        growth.adjustManual(userId, manual);
        var entry = growth.entries(userId).stream()
                .map(raw -> (GrowthOverviewVO.Entry) raw)
                .findFirst().orElseThrow();
        assertEquals(10, entry.getPoints());
        assertEquals("学识", entry.getAxisName());
        assertEquals(1L, entry.getDefinitionVersion());

        GrowthAdjustmentDTO correction = adjustment(UUID.randomUUID(), "action", 24, "补充说明后的更正");
        correction.setEntryId(entry.getId());
        correction.setExpectedVersion(entry.getVersion());
        growth.adjustManual(userId, correction);
        var corrected = (GrowthOverviewVO.Entry) growth.entries(userId).get(0);
        assertEquals(24, corrected.getPoints());
        assertEquals(2L, corrected.getVersion());
        assertEquals("行动", corrected.getAxisName());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM growth_entry_history WHERE entry_id=?", Integer.class, entry.getId()));
        assertEquals("knowledge", jdbc.queryForObject("SELECT dimension_id FROM growth_entry_history WHERE entry_id=?", String.class, entry.getId()));
        assertEquals(24L, growth.overview(userId).getTotalPoints());
    }

    @Test
    void invalidLaterRecordAxisRollsBackEarlierInsertAndReplayIsIdempotent() {
        var record = createRecord();
        long axesVersion = growth.axesVersion(userId);
        UUID actionId = UUID.randomUUID();
        var valid = adjustment(UUID.randomUUID(), "knowledge", 5, "学习了新内容");
        var invalid = adjustment(UUID.randomUUID(), "unknown-axis", 4, "不能入账");

        assertThrows(BusinessException.class, () -> growth.applyConfirmedRecord(
                userId, record.getId(), actionId, List.of(valid, invalid), axesVersion));
        assertEquals(0, growth.entries(userId).size(), "the valid first row must roll back with the invalid second axis");

        growth.applyConfirmedRecord(userId, record.getId(), actionId, List.of(valid), axesVersion);
        growth.applyConfirmedRecord(userId, record.getId(), actionId, List.of(valid), axesVersion);
        assertEquals(1, growth.entries(userId).size(), "worker replay must not duplicate the record/axis contribution");
        assertEquals(5L, growth.overview(userId).getTotalPoints());
    }

    @Test
    void confirmedRecordDeletionRemovesOnlyItsLinkedGrowthEntries() {
        var record = createRecord();
        growth.applyConfirmedRecord(userId, record.getId(), UUID.randomUUID(),
                List.of(adjustment(UUID.randomUUID(), "knowledge", 5, "有来源的成长")), growth.axesVersion(userId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM growth_entries WHERE user_id=? AND source_record_id=?",
                Integer.class, userId, record.getId()));

        var service = new RecordServiceImpl(records, mock(ChunkMapper.class), mock(SnapshotLineageService.class),
                mock(ApplicationEventPublisher.class), mock(AiGrpcClient.class), mock(ApplicationContext.class),
                mock(ChunkEmbeddingJobService.class));
        ReflectionTestUtils.setField(service, "growthEntries", entries);
        service.softDelete(record.getId(), userId);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM growth_entries WHERE user_id=? AND source_record_id=?",
                Integer.class, userId, record.getId()));
        assertNotNull(records.selectById(record.getId()).getDeletedAt());
    }

    private Record createRecord() {
        var record = Record.builder().userId(userId).content("growth source fixture").source("user")
                .status(RecordStatus.RECORD_ONLY).memoryState(RecordMemoryState.NONE)
                .aiProcessingAuthorized(false).processingVersion(0).userReviewed(false)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
        records.insert(record);
        return record;
    }

    private GrowthAdjustmentDTO adjustment(UUID requestId, String axisId, int points, String reason) {
        var dto = new GrowthAdjustmentDTO();
        dto.setRequestId(requestId);
        dto.setDimensionId(axisId);
        dto.setPoints(points);
        dto.setReason(reason);
        return dto;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("org.xianshen.mumirrorb.mapper")
    @Import({GrowthServiceImpl.class, GrowthProperties.class})
    static class TestApplication {}
}
