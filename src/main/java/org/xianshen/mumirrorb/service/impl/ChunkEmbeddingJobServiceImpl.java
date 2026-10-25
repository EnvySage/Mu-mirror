package org.xianshen.mumirrorb.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChunkEmbeddingJobMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ChunkEmbeddingJob;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;
import org.xianshen.mumirrorb.service.RecordMemoryLifecycleService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ChunkEmbeddingJobServiceImpl implements ChunkEmbeddingJobService {
    private final ChunkEmbeddingJobMapper jobMapper;
    private final ChunkMapper chunkMapper;
    private final RecordMapper recordMapper;
    private final RecordMemoryLifecycleService lifecycle;
    private final ApplicationEventPublisher events;

    public ChunkEmbeddingJobServiceImpl(ChunkEmbeddingJobMapper jobMapper, ChunkMapper chunkMapper,
                                        RecordMapper recordMapper, RecordMemoryLifecycleService lifecycle) {
        this(jobMapper, chunkMapper, recordMapper, lifecycle, null);
    }

    @Autowired
    public ChunkEmbeddingJobServiceImpl(ChunkEmbeddingJobMapper jobMapper, ChunkMapper chunkMapper,
                                        RecordMapper recordMapper, RecordMemoryLifecycleService lifecycle,
                                        ApplicationEventPublisher events) {
        this.jobMapper = jobMapper;
        this.chunkMapper = chunkMapper;
        this.recordMapper = recordMapper;
        this.lifecycle = lifecycle;
        this.events = events;
    }

    public ChunkEmbeddingJobServiceImpl(ChunkEmbeddingJobMapper jobMapper, ChunkMapper chunkMapper) {
        this(jobMapper, chunkMapper, null, null);
    }

    @Override
    @Transactional
    public void enqueue(Chunk chunk) {
        long version = chunk.getContentVersion() == null ? 1L : chunk.getContentVersion();
        int inserted = jobMapper.insertIfNoActiveVersion(chunk.getId(), chunk.getUserId(), version,
                hash(chunk.getSegment()));
        if (inserted > 0 || "pending".equals(chunk.getEmbeddingStatus())) {
            chunkMapper.markEmbeddingPending(chunk.getId(), chunk.getUserId(), version);
            chunk.setEmbeddingStatus("pending");
            chunk.setEmbeddingError(null);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Long latestJobId(Long chunkId, UUID userId) {
        ChunkEmbeddingJob latest = jobMapper.findLatest(chunkId, userId);
        return latest == null ? null : latest.getId();
    }

    @Override
    @Transactional
    public void cancel(Long chunkId, UUID userId) {
        jobMapper.cancelActive(chunkId, userId);
    }

    @Override
    @Transactional
    public boolean retry(Long chunkId, UUID userId) {
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !userId.equals(chunk.getUserId())) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "Chunk 不存在");
        }
        if (!Boolean.TRUE.equals(chunk.getAiMemoryApproved())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该片段尚未授权给 AI 记忆");
        }
        if (chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty()) return false;
        ChunkEmbeddingJob latest = jobMapper.findLatest(chunkId, userId);
        if (latest != null && ("pending".equals(latest.getStatus()) || "processing".equals(latest.getStatus()))) return false;
        if (latest != null && "failed".equals(latest.getStatus())) {
            if (jobMapper.retryFailed(latest.getId()) == 0) return false;
        } else {
            enqueue(chunk);
            return true;
        }
        chunkMapper.markEmbeddingPending(chunkId, userId,
                chunk.getContentVersion() == null ? 1L : chunk.getContentVersion());
        return true;
    }

    @Override
    @Transactional
    public List<ChunkEmbeddingJob> claimDue(int limit, Duration lease) {
        return jobMapper.claimDue(limit, OffsetDateTime.now().plus(lease));
    }

    @Override
    @Transactional(readOnly = true)
    public String currentAuthorizedText(ChunkEmbeddingJob job) {
        Chunk chunk = chunkMapper.selectById(job.getChunkId());
        if (chunk == null || !job.getUserId().equals(chunk.getUserId())
                || !Boolean.TRUE.equals(chunk.getAiMemoryApproved())
                || !java.util.Objects.equals(job.getContentVersion(), chunk.getContentVersion())
                || !hash(chunk.getSegment()).equals(job.getContentHash())
                || chunk.getSegment() == null || chunk.getSegment().isBlank()) {
            return null;
        }
        return chunk.getSegment();
    }

    @Override
    @Transactional
    public void markReady(ChunkEmbeddingJob job, List<Float> embedding) {
        int updated = chunkMapper.saveEmbeddingIfCurrent(job.getChunkId(), job.getUserId(),
                job.getContentVersion(), job.getId(), embedding);
        if (updated == 1) {
            jobMapper.markReady(job.getId());
            publishParentCheck(job, "READY", null);
        } else {
            jobMapper.finishAttempt(job.getId(), "cancelled", OffsetDateTime.now(), "片段内容或授权已变化");
        }
    }

    @Override
    @Transactional
    public void markFailure(ChunkEmbeddingJob job, String error, int maxAttempts, Duration baseDelay) {
        boolean exhausted = job.getAttempts() >= maxAttempts;
        String status = exhausted ? "failed" : "pending";
        long multiplier = 1L << Math.min(Math.max(job.getAttempts() - 1, 0), 10);
        OffsetDateTime availableAt = exhausted ? OffsetDateTime.now()
                : OffsetDateTime.now().plus(baseDelay.multipliedBy(multiplier));
        String safeError = error == null ? "未知 embedding 错误" : error.substring(0, Math.min(error.length(), 500));
        jobMapper.finishAttempt(job.getId(), status, availableAt, safeError);
        if (exhausted) {
            chunkMapper.markEmbeddingFailedIfCurrent(job.getChunkId(), job.getUserId(),
                    job.getContentVersion(), safeError);
            publishParentCheck(job, "FAILED", safeError);
        } else {
            chunkMapper.markEmbeddingPending(job.getChunkId(), job.getUserId(), job.getContentVersion());
        }
    }

    @Override
    @Transactional
    public void cancelStale(ChunkEmbeddingJob job) {
        jobMapper.finishAttempt(job.getId(), "cancelled", OffsetDateTime.now(), "片段内容或授权已变化");
    }

    static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void publishParentCheck(ChunkEmbeddingJob job, String state, String reason) {
        if (events != null && recordMapper != null && lifecycle != null)
            events.publishEvent(new ChunkEmbeddingJobStateChangedEvent(job.getUserId(), job.getChunkId(), state, reason));
    }
}
