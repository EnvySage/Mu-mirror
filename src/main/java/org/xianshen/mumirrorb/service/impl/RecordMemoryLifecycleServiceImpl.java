package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.ChunkEmbeddingJobMapper;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.RecordMemoryLifecycleService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordMemoryLifecycleServiceImpl implements RecordMemoryLifecycleService {
    private final RecordMapper records;
    private final ChunkMapper chunks;
    private final ChunkEmbeddingJobMapper jobs;
    private final SnapshotLineageService lineage;
    private final ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired private org.xianshen.mumirrorb.service.GrowthService growthService;

    @Override
    @Transactional
    public void onInitialIndexComplete(UUID userId, Long recordId, int processingVersion) {
        Record record = records.selectOwnedForUpdate(userId, recordId);
        if (!currentInitialIndex(record, processingVersion)) return;
        List<Chunk> selected = selectedChunks(recordId);
        if (selected.isEmpty() || selected.stream().anyMatch(c -> !ready(c))) return;
        record.setMemoryState(RecordMemoryState.READY);
        record.setUpdatedAt(OffsetDateTime.now());
        records.updateById(record);
        if (growthService != null && record.getGrowthSuggestions() != null && !record.getGrowthSuggestions().isBlank()
                && record.getGrowthAxesVersion() != null && growthService.axesVersion(userId) == record.getGrowthAxesVersion()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var suggestions = mapper.readValue(record.getGrowthSuggestions(), new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO>>() {});
                growthService.applyConfirmedRecord(userId, recordId, record.getMemoryActionId(), suggestions,
                        record.getGrowthAxesVersion() == null ? 0 : record.getGrowthAxesVersion());
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("成长建议无法入账", e); }
        }
        completeAction(record, "READY", null);
    }

    @Override
    @Transactional
    public void onInitialIndexExhausted(UUID userId, Long recordId, int processingVersion, String reason) {
        Record record = records.selectOwnedForUpdate(userId, recordId);
        if (!currentInitialIndex(record, processingVersion)) return;
        // Revoke the record generation before touching chunks/jobs. Any worker already in flight
        // now fails both the generation and per-chunk authorization checks.
        record.setProcessingVersion(processingVersion + 1);
        record.setMemoryState(RecordMemoryState.NONE);
        record.setAiProcessingAuthorized(false);
        record.setMemoryFailureReason(reason == null ? "Embedding 重试耗尽" : reason.substring(0, Math.min(500, reason.length())));
        record.setStatus(RecordStatus.RECORD_ONLY);
        record.setUserReviewed(false);
        record.setGrowthSuggestions("[]");
        record.setGrowthAxesVersion(0L);
        record.setFailReason(reason == null ? "记忆索引失败，已降级为仅保存" : reason.substring(0, Math.min(500, reason.length())));
        record.setUpdatedAt(OffsetDateTime.now());
        records.updateById(record);

        for (Chunk chunk : chunks.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getRecordId, recordId))) {
            jobs.cancelActive(chunk.getId(), userId);
            lineage.invalidateSource(userId, "chunk", chunk.getId(), "initial_memory_index_failed");
            if (Boolean.TRUE.equals(chunk.getAiMemoryApproved())) {
                chunk.setAiMemoryApproved(false);
                chunk.setEmbedding(null);
                chunk.setEmbeddingStatus("not_requested");
                chunk.setEmbeddingError(null);
                chunk.setContentVersion((chunk.getContentVersion() == null ? 1L : chunk.getContentVersion()) + 1L);
                chunks.updateById(chunk);
                chunks.clearEmbedding(chunk.getId(), userId);
            }
        }
        lineage.invalidateSource(userId, "record", recordId, "initial_memory_index_failed");
        completeAction(record, "NONE", "INDEXING_EXHAUSTED");
        log.warn("初次记忆索引耗尽，记录已安全降级为仅保存，recordId={} reason={}", recordId, reason);
    }

    @Override
    @Transactional
    public void disable(UUID userId, Long recordId, int expectedProcessingVersion) {
        Record record = records.selectOwnedForUpdate(userId, recordId);
        if (record == null || !java.util.Objects.equals(record.getProcessingVersion(), expectedProcessingVersion))
            throw new org.xianshen.mumirrorb.common.exception.BusinessException(409, "记录已变化，请重新准备永久移除记忆操作");
        if (record.getMemoryState() != RecordMemoryState.READY)
            throw new org.xianshen.mumirrorb.common.exception.BusinessException(400, "只有已就绪记忆可以永久移除");
        record.setMemoryState(RecordMemoryState.DISABLED);
        record.setMemoryFailureReason(null);
        record.setAiProcessingAuthorized(false);
        record.setProcessingVersion(expectedProcessingVersion + 1);
        record.setUpdatedAt(OffsetDateTime.now());
        records.updateById(record);
        for (Chunk chunk : selectedChunks(recordId)) {
            chunk.setAiMemoryApproved(false);
            chunk.setEmbedding(null);
            chunk.setEmbeddingStatus("not_requested");
            chunk.setEmbeddingError(null);
            chunk.setContentVersion((chunk.getContentVersion() == null ? 1L : chunk.getContentVersion()) + 1L);
            chunks.updateById(chunk);
            chunks.clearEmbedding(chunk.getId(), userId);
            jobs.cancelActive(chunk.getId(), userId);
            lineage.invalidateSource(userId, "chunk", chunk.getId(), "memory_disabled");
        }
        lineage.invalidateSource(userId, "record", recordId, "memory_disabled");
    }

    private boolean currentInitialIndex(Record record, int version) {
        return record != null && record.getMemoryState() == RecordMemoryState.PENDING
                && java.util.Objects.equals(record.getProcessingVersion(), version)
                && record.getStatus() == RecordStatus.DONE
                && record.getDeletedAt() == null;
    }

    private List<Chunk> selectedChunks(Long recordId) {
        return chunks.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getRecordId, recordId).eq(Chunk::getAiMemoryApproved, true));
    }

    private boolean ready(Chunk chunk) {
        return "ready".equalsIgnoreCase(chunk.getEmbeddingStatus())
                && chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty();
    }

    private void completeAction(Record record, String memoryState, String errorCode) {
        if (record.getMemoryActionId() == null || record.getMemoryActionExecutionVersion() == null) {
            log.info("记忆索引完成但无待完成的操作卡，recordId={}, memoryState={}", record.getId(), memoryState);
            return;
        }
        log.info("发布操作卡完成事件，actionId={}, executionVersion={}, recordId={}, memoryState={}",
                record.getMemoryActionId(), record.getMemoryActionExecutionVersion(), record.getId(), memoryState);
        var event = new RecordMemoryActionCompletedEvent(record.getMemoryActionId(),
                record.getMemoryActionExecutionVersion(), record.getId(), memoryState, errorCode,
                record.getMemoryFailureReason());
        events.publishEvent(event);
        record.setMemoryActionId(null);
        record.setMemoryActionExecutionVersion(null);
        records.updateById(record);
    }
}
