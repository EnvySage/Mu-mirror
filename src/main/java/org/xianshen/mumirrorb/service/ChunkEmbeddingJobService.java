package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface ChunkEmbeddingJobService {
    void enqueue(Chunk chunk);
    Long latestJobId(Long chunkId, UUID userId);
    void cancel(Long chunkId, UUID userId);
    boolean retry(Long chunkId, UUID userId);
    List<ChunkEmbeddingJob> claimDue(int limit, Duration lease);
    String currentAuthorizedText(ChunkEmbeddingJob job);
    void markReady(ChunkEmbeddingJob job, List<Float> embedding);
    void markFailure(ChunkEmbeddingJob job, String error, int maxAttempts, Duration baseDelay);
    void cancelStale(ChunkEmbeddingJob job);
}
