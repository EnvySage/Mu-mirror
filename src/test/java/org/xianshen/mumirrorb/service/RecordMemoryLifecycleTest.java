package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.ChunkEmbeddingJobMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.impl.RecordMemoryLifecycleServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecordMemoryLifecycleTest {
    @Mock private RecordMapper records;
    @Mock private ChunkMapper chunks;
    @Mock private ChunkEmbeddingJobMapper jobs;
    @Mock private SnapshotLineageService lineage;
    @Mock private ApplicationEventPublisher events;

    private final UUID userId = UUID.randomUUID();

    @Test
    void partialEmbeddingsNeverMakeWholeRecordReady() {
        Record record = record(5, RecordMemoryState.PENDING);
        Chunk ready = chunk(1L, "ready", List.of(.1f));
        Chunk pending = chunk(2L, "pending", null);
        when(records.selectOwnedForUpdate(userId, 12L)).thenReturn(record);
        when(chunks.selectList(any())).thenReturn(List.of(ready, pending));

        new RecordMemoryLifecycleServiceImpl(records, chunks, jobs, lineage, events)
                .onInitialIndexComplete(userId, 12L, 5);

        assertEquals(RecordMemoryState.PENDING, record.getMemoryState());
        verify(records, never()).updateById(any(Record.class));
    }

    @Test
    void lateWorkerCannotRestoreDegradedRecord() {
        Record record = record(8, RecordMemoryState.NONE);
        when(records.selectOwnedForUpdate(userId, 12L)).thenReturn(record);

        new RecordMemoryLifecycleServiceImpl(records, chunks, jobs, lineage, events)
                .onInitialIndexComplete(userId, 12L, 7);

        assertEquals(RecordMemoryState.NONE, record.getMemoryState());
        verify(chunks, never()).selectList(any());
        verify(records, never()).updateById(any(Record.class));
    }

    @Test
    void initialExhaustionInvalidatesGenerationBeforeCleaningAndPreservesTodoEffects() {
        Record record = record(7, RecordMemoryState.PENDING);
        record.setGrowthSuggestions("[{\"dimensionId\":\"knowledge\",\"points\":5}]");
        record.setGrowthAxesVersion(5L);
        record.setMemoryActionId(UUID.randomUUID());
        record.setMemoryActionExecutionVersion(4);
        Chunk memoryOnly = chunk(1L, "pending", null);
        memoryOnly.setSegment("用户审核确认的最终片段，不允许在降级时丢失");
        Chunk confirmedTodo = chunk(2L, "ready", List.of(.2f));
        confirmedTodo.setSegment("已经登记待办的最终片段");
        confirmedTodo.setTodoApproved(true);
        when(records.selectOwnedForUpdate(userId, 12L)).thenReturn(record);
        when(chunks.selectList(any())).thenReturn(List.of(memoryOnly, confirmedTodo));

        new RecordMemoryLifecycleServiceImpl(records, chunks, jobs, lineage, events)
                .onInitialIndexExhausted(userId, 12L, 7, "embedding timeout");

        assertEquals(RecordMemoryState.NONE, record.getMemoryState());
        assertEquals(8, record.getProcessingVersion());
        assertEquals(RecordStatus.RECORD_ONLY, record.getStatus());
        assertFalse(record.getAiProcessingAuthorized());
        assertEquals("embedding timeout", record.getMemoryFailureReason());
        assertEquals("[]", record.getGrowthSuggestions(), "failed initial indexing must discard staged growth");
        assertEquals(0L, record.getGrowthAxesVersion());
        assertFalse(confirmedTodo.getAiMemoryApproved());
        assertFalse(memoryOnly.getAiMemoryApproved());
        assertEquals("用户审核确认的最终片段，不允许在降级时丢失", memoryOnly.getSegment());
        assertEquals("已经登记待办的最终片段", confirmedTodo.getSegment());
        assertTrue(confirmedTodo.getTodoApproved(), "降级只撤销记忆授权，已确认待办应保留");
        verify(jobs).cancelActive(1L, userId);
        verify(jobs).cancelActive(2L, userId);
        verify(chunks, never()).deleteById(anyLong());
        verify(chunks).updateById(memoryOnly);
        verify(chunks).updateById(confirmedTodo);
        verify(lineage).invalidateSource(userId, "record", 12L, "initial_memory_index_failed");
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    void laterOutageDoesNotUnlockPreviouslyReadyRecord() {
        Record record = record(7, RecordMemoryState.READY);
        when(records.selectOwnedForUpdate(userId, 12L)).thenReturn(record);

        new RecordMemoryLifecycleServiceImpl(records, chunks, jobs, lineage, events)
                .onInitialIndexExhausted(userId, 12L, 7, "later outage");

        assertEquals(RecordMemoryState.READY, record.getMemoryState());
        assertEquals(7, record.getProcessingVersion());
        verify(records, never()).updateById(any(Record.class));
        verify(chunks, never()).selectList(any());
    }

    private Record record(int version, RecordMemoryState state) {
        return Record.builder().id(12L).userId(userId).status(RecordStatus.DONE)
                .processingVersion(version).memoryState(state).build();
    }

    private Chunk chunk(Long id, String embeddingStatus, List<Float> vector) {
        return Chunk.builder().id(id).recordId(12L).userId(userId).aiMemoryApproved(true)
                .embeddingStatus(embeddingStatus).embedding(vector).contentVersion(2L).build();
    }
}
