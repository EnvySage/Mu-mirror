package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.ChunkDTO;
import org.xianshen.mumirrorb.service.impl.ChunkServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkEditInvalidationTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Long CHUNK_ID = 10L;
    private static final Long RECORD_ID = 20L;

    @Mock private ChunkMapper chunkMapper;
    @Mock private RecordMapper recordMapper;
    @Mock private SnapshotLineageService snapshotLineageService;
    @Mock private AiGrpcClient aiGrpcClient;
    @Mock private ChunkEmbeddingJobService chunkEmbeddingJobService;
    @InjectMocks private ChunkServiceImpl service;

    private Chunk chunk;

    @BeforeEach
    void setUp() {
        chunk = Chunk.builder()
                .id(CHUNK_ID)
                .userId(USER_ID)
                .recordId(RECORD_ID)
                .segment("原始片段")
                .classifiedSegment("原始片段")
                .metadata(new HashMap<>(Map.of("contentType", "learning")))
                .aiMemoryApproved(true)
                .embedding(List.of(0.25f, 0.75f))
                .build();
        when(chunkMapper.selectById(CHUNK_ID)).thenReturn(chunk);
        lenient().when(recordMapper.selectOwnedForUpdate(USER_ID, RECORD_ID)).thenReturn(Record.builder()
                .id(RECORD_ID).userId(USER_ID).status(RecordStatus.REVIEWING).build());
    }

    @Test
    void changedSegmentRevokesOldTextAndInvalidatesLineage() {
        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("明确保存的新片段");

        service.update(CHUNK_ID, edit, USER_ID);

        assertEquals("明确保存的新片段", chunk.getSegment());
        assertNull(chunk.getClassifiedSegment());
        assertFalse(chunk.getAiMemoryApproved());
        assertNull(chunk.getEmbedding());
        verify(chunkMapper).clearClassifiedSegment(CHUNK_ID, USER_ID);
        verify(chunkMapper).clearEmbedding(CHUNK_ID, USER_ID);
        verify(snapshotLineageService).invalidateSource(USER_ID, "chunk", CHUNK_ID, "approved_chunk_edited");
        verify(chunkEmbeddingJobService).cancel(CHUNK_ID, USER_ID);
    }

    @Test
    void sameSegmentDoesNotRevokeAuthorizationOrClearVector() {
        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("原始片段");

        var result = service.update(CHUNK_ID, edit, USER_ID);

        assertTrue(chunk.getAiMemoryApproved());
        assertEquals(List.of(0.25f, 0.75f), chunk.getEmbedding());
        assertTrue(result.getHasEmbedding());
        assertTrue(result.getAiMemoryApproved());
        verify(chunkMapper, never()).clearClassifiedSegment(anyLong(), any());
        verify(chunkMapper, never()).clearEmbedding(anyLong(), any());
        verify(chunkMapper, never()).updateById(any(Chunk.class));
        verify(snapshotLineageService, never()).invalidateSource(any(), anyString(), anyLong(), anyString());
        verify(chunkEmbeddingJobService, never()).cancel(anyLong(), any());
    }

    @Test
    void metadataOnlyEditDoesNotInvalidateTextVectorOrConsent() {
        ChunkDTO edit = new ChunkDTO();
        edit.setMood(List.of("happy"));

        var result = service.update(CHUNK_ID, edit, USER_ID);

        assertTrue(chunk.getAiMemoryApproved());
        assertEquals(List.of(0.25f, 0.75f), chunk.getEmbedding());
        assertEquals("原始片段", chunk.getClassifiedSegment());
        assertTrue(result.getHasEmbedding());
        verify(chunkMapper, never()).clearEmbedding(anyLong(), any());
        verify(snapshotLineageService, never()).invalidateSource(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void readyRecordRejectsTextAndMetadataEdits() {
        when(recordMapper.selectOwnedForUpdate(USER_ID, RECORD_ID)).thenReturn(Record.builder()
                .id(RECORD_ID).userId(USER_ID).status(RecordStatus.DONE).memoryState(RecordMemoryState.READY).build());
        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("修改过的正文");

        assertThrows(RuntimeException.class, () -> service.update(CHUNK_ID, edit, USER_ID));

        verify(chunkMapper, never()).updateById(any(Chunk.class));
        verify(chunkEmbeddingJobService, never()).cancel(anyLong(), any());
    }

    @Test
    void pendingRecordRejectsConcurrentSegmentEdit() {
        when(recordMapper.selectOwnedForUpdate(USER_ID, RECORD_ID)).thenReturn(Record.builder()
                .id(RECORD_ID).userId(USER_ID).status(RecordStatus.DONE).memoryState(RecordMemoryState.PENDING).build());
        ChunkDTO edit = new ChunkDTO();
        edit.setSegment("并发修改");

        assertThrows(RuntimeException.class, () -> service.update(CHUNK_ID, edit, USER_ID));

        verify(chunkMapper, never()).updateById(any(Chunk.class));
    }
}
