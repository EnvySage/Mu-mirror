package org.xianshen.mumirrorb.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkEmbeddingWorker {
    private final ChunkEmbeddingJobService jobService;
    private final AiGrpcClient aiGrpcClient;

    @Value("${mirror.embedding.worker.batch-size:4}")
    private int batchSize = 4;
    @Value("${mirror.embedding.worker.lease-seconds:180}")
    private long leaseSeconds = 180;
    @Value("${mirror.embedding.worker.max-attempts:5}")
    private int maxAttempts = 5;
    @Value("${mirror.embedding.worker.retry-base-seconds:10}")
    private long retryBaseSeconds = 10;

    @Scheduled(fixedDelayString = "${mirror.embedding.worker.fixed-delay-ms:2000}")
    public void processDueJobs() {
        try {
            List<ChunkEmbeddingJob> jobs = jobService.claimDue(batchSize, Duration.ofSeconds(leaseSeconds));
            for (ChunkEmbeddingJob job : jobs) {
                try {
                    process(job);
                } catch (Exception e) {
                    // Keep other claimed jobs moving; the failed lease becomes claimable after expiry.
                    log.error("处理 chunk embedding 任务异常，jobId={} chunkId={}", job.getId(), job.getChunkId(), e);
                }
            }
        } catch (Exception e) {
            log.error("领取 chunk embedding 任务失败", e);
        }
    }

    void process(ChunkEmbeddingJob job) {
        String text = jobService.currentAuthorizedText(job);
        if (text == null) {
            jobService.cancelStale(job);
            return;
        }
        try {
            EmbeddingProto.EmbedResponse response = aiGrpcClient.embed(job.getUserId(), text);
            if (response.getDimension() != 1024 || response.getVectorCount() != 1024) {
                throw new IllegalStateException("embedding 维度应为 1024，实际声明/向量长度为 "
                        + response.getDimension() + "/" + response.getVectorCount());
            }
            jobService.markReady(job, response.getVectorList());
        } catch (Exception e) {
            log.warn("chunk embedding 失败，chunkId={} attempt={}: {}", job.getChunkId(), job.getAttempts(), e.getMessage());
            jobService.markFailure(job, e.getMessage(), maxAttempts, Duration.ofSeconds(retryBaseSeconds));
        }
    }
}
