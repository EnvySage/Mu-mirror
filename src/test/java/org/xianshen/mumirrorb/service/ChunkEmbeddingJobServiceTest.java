package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.mapper.ChunkEmbeddingJobMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;
import org.xianshen.mumirrorb.service.impl.ChunkEmbeddingJobServiceImpl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingJobServiceTest {

    @Mock private ChunkEmbeddingJobMapper jobMapper;
    @Mock private ChunkMapper chunkMapper;

    @Test
    void enqueueIsBoundToExactChunkVersionAndMarksPending() {
        UUID userId = UUID.randomUUID();
        Chunk chunk = Chunk.builder().id(4L).userId(userId).segment("正文")
                .contentVersion(7L).aiMemoryApproved(true).build();
        when(jobMapper.insertIfNoActiveVersion(eq(4L), eq(userId), eq(7L), anyString())).thenReturn(1);

        new ChunkEmbeddingJobServiceImpl(jobMapper, chunkMapper).enqueue(chunk);

        verify(jobMapper).insertIfNoActiveVersion(eq(4L), eq(userId), eq(7L), anyString());
        verify(chunkMapper).markEmbeddingPending(4L, userId, 7L);
        assertEquals("pending", chunk.getEmbeddingStatus());
    }

    @Test
    void readyWriteRaceCancelsJobWhenChunkVersionNoLongerMatches() {
        ChunkEmbeddingJob job = job(3);
        when(chunkMapper.saveEmbeddingIfCurrent(eq(9L), eq(job.getUserId()), eq(3L), eq(12L), anyList())).thenReturn(0);

        new ChunkEmbeddingJobServiceImpl(jobMapper, chunkMapper).markReady(job, List.of(0.1f));

        verify(jobMapper).finishAttempt(eq(12L), eq("cancelled"), any(OffsetDateTime.class), anyString());
        verify(jobMapper, never()).markReady(anyLong());
    }

    @Test
    void workerWillNotReadTextFromNewerChunkVersion() {
        ChunkEmbeddingJob job = job(2);
        Chunk newer = Chunk.builder().id(9L).userId(job.getUserId()).contentVersion(3L)
                .segment("new text").aiMemoryApproved(true).build();
        when(chunkMapper.selectById(9L)).thenReturn(newer);

        String text = new ChunkEmbeddingJobServiceImpl(jobMapper, chunkMapper).currentAuthorizedText(job);

        assertNull(text);
    }

    @Test
    void transientFailureSchedulesRetryAndTerminalFailureIsVisible() {
        ChunkEmbeddingJob job = job(2);
        job.setAttempts(2);
        ChunkMapper chunks = mock(ChunkMapper.class);
        ChunkEmbeddingJobMapper jobs = mock(ChunkEmbeddingJobMapper.class);
        ChunkEmbeddingJobServiceImpl service = new ChunkEmbeddingJobServiceImpl(jobs, chunks);

        service.markFailure(job, "timeout", 5, Duration.ofSeconds(1));
        verify(jobs).finishAttempt(eq(12L), eq("pending"), any(OffsetDateTime.class), eq("timeout"));
        verify(chunks).markEmbeddingPending(job.getChunkId(), job.getUserId(), 2L);

        reset(jobs, chunks);
        job.setAttempts(5);
        service.markFailure(job, "invalid vector dimension", 5, Duration.ofSeconds(1));
        verify(jobs).finishAttempt(eq(12L), eq("failed"), any(OffsetDateTime.class), eq("invalid vector dimension"));
        verify(chunks).markEmbeddingFailedIfCurrent(job.getChunkId(), job.getUserId(), 2L, "invalid vector dimension");
    }

    private ChunkEmbeddingJob job(int version) {
        ChunkEmbeddingJob job = new ChunkEmbeddingJob();
        job.setId(12L);
        job.setChunkId(9L);
        job.setUserId(UUID.randomUUID());
        job.setContentVersion((long) version);
        job.setContentHash("hash");
        job.setStatus("processing");
        job.setAttempts(1);
        return job;
    }
}
