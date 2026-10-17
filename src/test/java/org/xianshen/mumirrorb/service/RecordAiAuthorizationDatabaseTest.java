package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.mybatis.spring.annotation.MapperScan;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ChunkEmbeddingJobMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.SnapshotInputMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.SnapshotInput;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.SnapshotSourceRef;
import org.xianshen.mumirrorb.service.impl.ChunkServiceImpl;
import org.xianshen.mumirrorb.service.impl.ChunkEmbeddingJobServiceImpl;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;
import org.xianshen.mumirrorb.maintenance.ChunkEmbeddingWorker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PostgreSQL/MyBatis integration coverage for record AI authorization.
 *
 * <p>Run with MIRROR_TEST_DB_URL, MIRROR_TEST_DB_USERNAME and MIRROR_TEST_DB_PASSWORD.
 * Each test uses a Spring-managed transaction and rolls its fixture rows back.</p>
 */
@EnabledIfEnvironmentVariable(named = "MIRROR_TEST_DB_URL", matches = "^jdbc:postgresql:.*$")
@SpringBootTest(
        classes = RecordAiAuthorizationDatabaseTest.TestApplication.class,
        properties = {
                "spring.main.web-application-type=none",
                "spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource"
        }
)
@ActiveProfiles("test")
@Transactional
class RecordAiAuthorizationDatabaseTest {

    @Autowired
    private RecordMapper recordMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private ChunkEmbeddingJobMapper chunkEmbeddingJobMapper;

    @Autowired
    private ChatSearchMapper chatSearchMapper;

    @Autowired
    private ProfileSnapshotMapper profileSnapshotMapper;

    @Autowired
    private SnapshotInputMapper snapshotInputMapper;

    @Autowired
    private ProfileStatsMapper profileStatsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MIRROR_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("MIRROR_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MIRROR_TEST_DB_PASSWORD"));
    }

    @Test
    void createWithoutAiRequestPersistsRecordOnlyAuthorization() {
        UUID userId = createUser();
        RecordDTO dto = new RecordDTO();
        dto.setContent("database authorization fixture");

        RecordServiceImpl service = service(mock(AiGrpcClient.class));
        var created = service.create(dto, userId);
        Record persisted = recordMapper.selectById(created.getId());

        assertNotNull(persisted);
        assertEquals(RecordStatus.RECORD_ONLY, persisted.getStatus());
        assertFalse(persisted.getAiProcessingAuthorized());
        assertEquals(0, persisted.getProcessingVersion());
    }

    @Test
    void confirmAndRevokePersistSeparateApprovalsAndRemoveRevokedMemoryFromSearch() {
        UUID userId = createUser();
        Record record = createReviewingRecord(userId);
        Chunk memoryChunk = createChunk(userId, record.getId(), "approved memory",
                Map.of("contentType", "learning"));
        Chunk todoChunk = createChunk(userId, record.getId(), "separately approved todo",
                Map.of("contentType", "todo"));
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("manual")
                .invalidated(false)
                .createdAt(OffsetDateTime.now())
                .build();
        profileSnapshotMapper.insert(snapshot);

        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        EmbeddingProto.EmbedResponse.Builder embedding = EmbeddingProto.EmbedResponse.newBuilder()
                .setDimension(1024)
                .setModelName("integration-test");
        for (int i = 0; i < 1024; i++) embedding.addVector(i == 0 ? 1.0f : 0.0f);
        when(aiGrpcClient.embed(eq(userId), anyString())).thenReturn(embedding.build());
        RecordServiceImpl service = service(aiGrpcClient);

        service.confirmReview(record.getId(), userId,
                List.of(memoryChunk.getId()), List.of(todoChunk.getId()), List.of());

        Chunk pendingMemory = chunkMapper.selectById(memoryChunk.getId());
        assertTrue(pendingMemory.getAiMemoryApproved());
        assertNull(pendingMemory.getEmbedding());
        assertEquals("pending", pendingMemory.getEmbeddingStatus());
        verify(aiGrpcClient, never()).embed(any(), any());
        new ChunkEmbeddingWorker(new ChunkEmbeddingJobServiceImpl(chunkEmbeddingJobMapper, chunkMapper), aiGrpcClient)
                .processDueJobs();

        Chunk approvedMemory = chunkMapper.selectById(memoryChunk.getId());
        Chunk approvedTodo = chunkMapper.selectById(todoChunk.getId());
        assertTrue(approvedMemory.getAiMemoryApproved());
        assertNotNull(approvedMemory.getEmbedding());
        assertEquals(1024, approvedMemory.getEmbedding().size());
        assertFalse(approvedTodo.getAiMemoryApproved());
        assertTrue(approvedTodo.getTodoApproved());
        assertNull(approvedTodo.getEmbedding());
        assertEquals(List.of(memoryChunk.getId()), chunkMapper.searchBySimilarity(
                        userId, vectorLiteral(), 10).stream().map(Chunk::getId).toList());
        assertEquals(List.of(record.getId()), chatSearchMapper.searchStructured(
                        userId, null, null, null, null, null, 10).stream()
                .map(result -> result.getRecordId()).toList());
        assertEquals(List.of(record.getId()), chatSearchMapper.searchSemantic(
                        userId, vectorLiteral(), false, 30.0, null, 10).stream()
                .map(result -> result.getRecordId()).toList());
        assertEquals(List.of(memoryChunk.getId()), chunkMapper.selectLookbackChunks(
                        userId, null, null, 0).stream().map(Chunk::getId).toList());

        service.confirmReview(record.getId(), userId, List.of(), List.of(), List.of());

        Chunk revokedMemory = chunkMapper.selectById(memoryChunk.getId());
        assertFalse(revokedMemory.getAiMemoryApproved());
        assertNull(revokedMemory.getEmbedding());
        assertTrue(chunkMapper.searchBySimilarity(userId, vectorLiteral(), 10).isEmpty());
        assertTrue(chatSearchMapper.searchStructured(userId, null, null, null, null, null, 10).isEmpty());
        assertTrue(chatSearchMapper.searchSemantic(userId, vectorLiteral(), false, 30.0, null, 10).isEmpty());
        assertTrue(chunkMapper.selectLookbackChunks(userId, null, null, 0).isEmpty());
        assertTrue(profileSnapshotMapper.selectById(snapshot.getId()).getInvalidated());
    }

    @Test
    void editingApprovedChunkPersistsRevocationAndClearsStaleDerivedFields() {
        UUID userId = createUser();
        Record record = createDoneRecord(userId);
        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(record.getId())
                .content("immutable original content")
                .segment("previous approved segment")
                .classifiedSegment("previous approved segment")
                .metadata(Map.of("contentType", "learning"))
                .aiMemoryApproved(true)
                .todoApproved(false)
                .embedding(embeddingValues())
                .createdAt(OffsetDateTime.now())
                .build();
        chunkMapper.insert(chunk);
        ProfileSnapshot snapshot = trackedSnapshot(userId, "uses approved chunk");
        profileSnapshotMapper.insert(snapshot);
        snapshotInputMapper.insertAll(snapshot.getId(), List.of(SnapshotInput.builder()
                .inputKind("chunk").inputId(chunk.getId()).inputRole("stats").build()));
        Chunk beforeEdit = chunkMapper.selectById(chunk.getId());
        chunkEmbeddingJobMapper.insertIfNoActiveVersion(chunk.getId(), userId,
                beforeEdit.getContentVersion(), "pending-fixture-hash");

        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("edited segment");
        new ChunkServiceImpl(chunkMapper, recordMapper, new SnapshotLineageService(profileSnapshotMapper),
                mock(AiGrpcClient.class), new ChunkEmbeddingJobServiceImpl(chunkEmbeddingJobMapper, chunkMapper))
                .update(chunk.getId(), edit, userId);

        Chunk persisted = chunkMapper.selectById(chunk.getId());
        assertEquals("edited segment", persisted.getSegment());
        assertNull(persisted.getClassifiedSegment());
        assertFalse(persisted.getAiMemoryApproved());
        assertNull(persisted.getEmbedding());
        assertEquals("cancelled", chunkEmbeddingJobMapper.findLatest(chunk.getId(), userId).getStatus());
        assertTrue(chunkMapper.searchBySimilarity(userId, vectorLiteral(), 10).isEmpty());
        ProfileSnapshot invalidated = profileSnapshotMapper.selectById(snapshot.getId());
        assertTrue(invalidated.getInvalidated());
        assertEquals("approved_chunk_edited", invalidated.getInvalidatedReason());
    }

    @Test
    void deletingApprovedChunkInvalidatesItsDependentArchiveBeforeRemovingSource() {
        UUID userId = createUser();
        Record record = createReviewingRecord(userId);
        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(record.getId())
                .content("approved source")
                .segment("approved source")
                .aiMemoryApproved(true)
                .createdAt(OffsetDateTime.now())
                .build();
        chunkMapper.insert(chunk);
        ProfileSnapshot snapshot = trackedSnapshot(userId, "uses removed chunk");
        profileSnapshotMapper.insert(snapshot);
        snapshotInputMapper.insertAll(snapshot.getId(), List.of(SnapshotInput.builder()
                .inputKind("chunk").inputId(chunk.getId()).inputRole("stats").build()));

        new ChunkServiceImpl(chunkMapper, recordMapper, new SnapshotLineageService(profileSnapshotMapper),
                mock(AiGrpcClient.class), new ChunkEmbeddingJobServiceImpl(chunkEmbeddingJobMapper, chunkMapper))
                .delete(chunk.getId(), userId);

        assertNull(chunkMapper.selectById(chunk.getId()));
        ProfileSnapshot invalidated = profileSnapshotMapper.selectById(snapshot.getId());
        assertTrue(invalidated.getInvalidated());
        assertEquals("chunk_removed", invalidated.getInvalidatedReason());
    }

    @Test
    void archivedSnapshotKeepsBodyAndDependencyTombstoneWithoutSourceForeignKey() {
        UUID userId = createUser();
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .periodMonth("2026-09")
                .overallSummary("archive body")
                .embedding(embeddingValues())
                .invalidated(true)
                .createdAt(OffsetDateTime.now())
                .build();
        profileSnapshotMapper.insert(snapshot);

        long physicallyRemovedRecordId = Long.MAX_VALUE;
        snapshotInputMapper.insertAll(snapshot.getId(), List.of(SnapshotInput.builder()
                .inputKind("record")
                .inputId(physicallyRemovedRecordId)
                .inputRole("stats")
                .build()));

        ProfileSnapshot persisted = profileSnapshotMapper.selectById(snapshot.getId());
        assertEquals("archive body", persisted.getOverallSummary());
        assertTrue(persisted.getInvalidated());
        assertEquals(1024, persisted.getEmbedding().size());
        List<SnapshotInput> inputs = snapshotInputMapper.selectBySnapshotId(snapshot.getId());
        assertEquals(1, inputs.size());
        assertEquals(physicallyRemovedRecordId, inputs.get(0).getInputId());
    }

    @Test
    void sourceQueriesReturnIdsForTheExactAuthorizedInputs() {
        UUID userId = createUser();
        Record record = createDoneRecord(userId);
        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(record.getId())
                .content("approved learning source")
                .segment("approved learning source")
                .metadata(Map.of("contentType", "learning", "title", "学习来源"))
                .aiMemoryApproved(true)
                .todoApproved(true)
                .createdAt(record.getCreatedAt())
                .build();
        chunkMapper.insert(chunk);
        jdbcTemplate.update("""
                INSERT INTO todo_registry (user_id, title, current_status, source_chunk_id)
                VALUES (?, ?, 'not_started', ?)
                """, userId, "待办来源", chunk.getId());

        OffsetDateTime since = record.getCreatedAt().minusDays(1);
        OffsetDateTime until = record.getCreatedAt().plusDays(1);
        SnapshotSourceRef statsRecord = SnapshotSourceRef.builder()
                .inputKind("record").inputId(record.getId()).inputRole("stats").build();
        SnapshotSourceRef statsChunk = SnapshotSourceRef.builder()
                .inputKind("chunk").inputId(chunk.getId()).inputRole("stats").build();
        assertTrue(profileStatsMapper.selectStatsSourceRefs(userId, since, until).containsAll(
                List.of(statsRecord, statsChunk)));
        assertTrue(profileStatsMapper.selectRecentLearningSourceRefs(userId).containsAll(List.of(
                SnapshotSourceRef.builder().inputKind("record").inputId(record.getId())
                        .inputRole("learning").build(),
                SnapshotSourceRef.builder().inputKind("chunk").inputId(chunk.getId())
                        .inputRole("learning").build())));
        assertTrue(profileStatsMapper.selectTodoSourceRefs(userId).containsAll(List.of(
                SnapshotSourceRef.builder().inputKind("record").inputId(record.getId())
                        .inputRole("todo").build(),
                SnapshotSourceRef.builder().inputKind("chunk").inputId(chunk.getId())
                        .inputRole("todo").build())));

        Map<String, Object> correction = chunkMapper.selectCorrectionIndex(userId, since, until).get(0);
        assertEquals(record.getId(), ((Number) correction.get("recordId")).longValue());
        assertEquals(chunk.getId(), ((Number) correction.get("chunkId")).longValue());
    }

    @Test
    void softDeleteInvalidatesOnlyDependentSnapshotsAndTheirDescendants() {
        UUID userA = createUser();
        UUID userB = createUser();
        Record deletedSource = createDoneRecord(userA);
        ProfileSnapshot direct = trackedSnapshot(userA, "direct dependent");
        ProfileSnapshot descendant = trackedSnapshot(userA, "descendant");
        ProfileSnapshot unrelated = trackedSnapshot(userA, "unrelated");
        ProfileSnapshot legacy = trackedSnapshot(userA, "legacy without lineage");
        legacy.setLineageTracked(false);
        ProfileSnapshot otherUser = trackedSnapshot(userB, "other user");
        profileSnapshotMapper.insert(direct);
        profileSnapshotMapper.insert(descendant);
        profileSnapshotMapper.insert(unrelated);
        profileSnapshotMapper.insert(legacy);
        profileSnapshotMapper.insert(otherUser);
        snapshotInputMapper.insertAll(direct.getId(), List.of(SnapshotInput.builder()
                .inputKind("record").inputId(deletedSource.getId()).inputRole("stats").build()));
        snapshotInputMapper.insertAll(descendant.getId(), List.of(SnapshotInput.builder()
                .inputKind("snapshot").inputId(direct.getId()).inputRole("parent").build()));

        service(mock(AiGrpcClient.class)).softDelete(deletedSource.getId(), userA);

        assertTrue(profileSnapshotMapper.selectById(direct.getId()).getInvalidated());
        assertTrue(profileSnapshotMapper.selectById(descendant.getId()).getInvalidated());
        assertEquals("record_deleted", profileSnapshotMapper.selectById(direct.getId()).getInvalidatedReason());
        assertEquals("legacy_untracked_source_change",
                profileSnapshotMapper.selectById(legacy.getId()).getInvalidatedReason());
        assertFalse(profileSnapshotMapper.selectById(unrelated.getId()).getInvalidated());
        assertFalse(profileSnapshotMapper.selectById(otherUser.getId()).getInvalidated());
    }

    private ProfileSnapshot trackedSnapshot(UUID userId, String summary) {
        return ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("manual")
                .overallSummary(summary)
                .invalidated(false)
                .lineageTracked(true)
                .versionNo(1)
                .isCurrent(true)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                userId, "db-it-" + userId, "integration-only");
        return userId;
    }

    private Record createReviewingRecord(UUID userId) {
        Record record = Record.builder()
                .userId(userId)
                .content("authorization review fixture")
                .source("user")
                .status(RecordStatus.REVIEWING)
                .aiProcessingAuthorized(true)
                .processingVersion(1)
                .userReviewed(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        recordMapper.insert(record);
        return record;
    }

    private Record createDoneRecord(UUID userId) {
        Record record = Record.builder()
                .userId(userId)
                .content("approved memory edit fixture")
                .source("user")
                .status(RecordStatus.DONE)
                .aiProcessingAuthorized(true)
                .processingVersion(1)
                .userReviewed(true)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        recordMapper.insert(record);
        return record;
    }

    private Chunk createChunk(UUID userId, Long recordId, String segment, Map<String, Object> metadata) {
        Chunk chunk = Chunk.builder()
                .userId(userId)
                .recordId(recordId)
                .content(segment)
                .segment(segment)
                .classifiedSegment(segment)
                .metadata(metadata)
                .aiMemoryApproved(false)
                .todoApproved(false)
                .createdAt(OffsetDateTime.now())
                .build();
        chunkMapper.insert(chunk);
        return chunk;
    }

    private RecordServiceImpl service(AiGrpcClient aiGrpcClient) {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        TodoRegistryService todoRegistryService = mock(TodoRegistryService.class);
        when(applicationContext.getBean(TodoRegistryService.class)).thenReturn(todoRegistryService);
        return new RecordServiceImpl(recordMapper, chunkMapper, new SnapshotLineageService(profileSnapshotMapper),
                mock(ApplicationEventPublisher.class), aiGrpcClient, applicationContext,
                new ChunkEmbeddingJobServiceImpl(chunkEmbeddingJobMapper, chunkMapper));
    }

    private String vectorLiteral() {
        return "[1" + ",0".repeat(1023) + "]";
    }

    private List<Float> embeddingValues() {
        return java.util.stream.IntStream.range(0, 1024)
                .mapToObj(i -> i == 0 ? 1.0f : 0.0f)
                .toList();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("org.xianshen.mumirrorb.mapper")
    static class TestApplication {
    }
}
