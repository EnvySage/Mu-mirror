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
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.service.impl.ChunkServiceImpl;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;

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
    private ChatSearchMapper chatSearchMapper;

    @Autowired
    private ProfileSnapshotMapper profileSnapshotMapper;

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

        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("edited segment");
        new ChunkServiceImpl(chunkMapper, recordMapper, profileSnapshotMapper,
                mock(AiGrpcClient.class)).update(chunk.getId(), edit, userId);

        Chunk persisted = chunkMapper.selectById(chunk.getId());
        assertEquals("edited segment", persisted.getSegment());
        assertNull(persisted.getClassifiedSegment());
        assertFalse(persisted.getAiMemoryApproved());
        assertNull(persisted.getEmbedding());
        assertTrue(chunkMapper.searchBySimilarity(userId, vectorLiteral(), 10).isEmpty());
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
        return new RecordServiceImpl(recordMapper, chunkMapper, profileSnapshotMapper,
                mock(ApplicationEventPublisher.class), aiGrpcClient, applicationContext);
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
