package org.xianshen.mumirrorb.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingWorkerTest {
    @Mock private ChunkEmbeddingJobService jobService;
    @Mock private AiGrpcClient aiGrpcClient;

    @Test
    void successfulEmbeddingIsCommittedByContentVersion() {
        ChunkEmbeddingJob job = job();
        when(jobService.claimDue(anyInt(), any())).thenReturn(List.of(job));
        when(jobService.currentAuthorizedText(job)).thenReturn("当前正文");
        when(aiGrpcClient.embed(job.getUserId(), "当前正文")).thenReturn(response(1024));

        new ChunkEmbeddingWorker(jobService, aiGrpcClient).processDueJobs();

        verify(jobService).markReady(eq(job), argThat(vector -> vector.size() == 1024));
        verify(jobService, never()).markFailure(any(), anyString(), anyInt(), any());
    }

    @Test
    void staleOrRevokedContentIsCancelledWithoutCallingAi() {
        ChunkEmbeddingJob job = job();
        when(jobService.claimDue(anyInt(), any())).thenReturn(List.of(job));
        when(jobService.currentAuthorizedText(job)).thenReturn(null);

        new ChunkEmbeddingWorker(jobService, aiGrpcClient).processDueJobs();

        verify(jobService).cancelStale(job);
        verifyNoInteractions(aiGrpcClient);
    }

    @Test
    void malformedVectorIsRetriedAndNeverMarkedReady() {
        ChunkEmbeddingJob job = job();
        when(jobService.claimDue(anyInt(), any())).thenReturn(List.of(job));
        when(jobService.currentAuthorizedText(job)).thenReturn("当前正文");
        when(aiGrpcClient.embed(job.getUserId(), "当前正文")).thenReturn(response(3));

        new ChunkEmbeddingWorker(jobService, aiGrpcClient).processDueJobs();

        verify(jobService, never()).markReady(any(), anyList());
        verify(jobService).markFailure(eq(job), contains("1024"), anyInt(), any());
    }

    private ChunkEmbeddingJob job() {
        ChunkEmbeddingJob job = new ChunkEmbeddingJob();
        job.setId(2L);
        job.setChunkId(5L);
        job.setUserId(UUID.randomUUID());
        job.setContentVersion(4L);
        job.setContentHash("hash");
        job.setAttempts(1);
        return job;
    }

    private EmbeddingProto.EmbedResponse response(int dimensions) {
        EmbeddingProto.EmbedResponse.Builder response = EmbeddingProto.EmbedResponse.newBuilder()
                .setDimension(dimensions).setModelName("test");
        for (int i = 0; i < dimensions; i++) response.addVector(0.1f);
        return response.build();
    }
}
