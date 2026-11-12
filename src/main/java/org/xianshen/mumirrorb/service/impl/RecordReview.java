package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.CalendarDayVO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.pipeline.ClassifyItemConverter;
import org.xianshen.mumirrorb.pipeline.TimeSubstitutionApplier;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;

import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

import static org.xianshen.mumirrorb.service.impl.RecordServiceImpl.*;

/** RecordReview keeps the service entry points and transaction boundaries in RecordServiceImpl. */
final class RecordReview {
    private final RecordServiceImpl owner;
    RecordReview(RecordServiceImpl owner) { this.owner=owner; }

    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId) {
        return owner.confirmReview(recordId, userId, List.of(), List.of(), null);
    }


    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions) {
        return owner.confirmReview(recordId, userId, aiMemoryChunkIds, todoChunkIds, resolutions, null, null);
    }


    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion) {
        return owner.confirmReview(recordId,userId,aiMemoryChunkIds,todoChunkIds,resolutions,actionId,actionExecutionVersion,null,null);
    }


    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion,
                                  List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO> growthAdjustments, Long axesVersion) {
        Record record = owner.recordMapper.selectOwnedForUpdate(userId, recordId);
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        if (record.getMemoryState() == RecordMemoryState.PENDING || record.getMemoryState() == RecordMemoryState.READY
                || record.getMemoryState() == RecordMemoryState.DISABLED)
            throw new BusinessException(ResultCode.PARAM_ERROR, "该记录的已确认正文不可再次修改");
        if (record.getStatus() != RecordStatus.REVIEWING && record.getStatus() != RecordStatus.DONE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有已完成 AI 整理的记录才能确认片段");
        }

        List<Chunk> chunks = owner.chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getRecordId, recordId)
        );
        if (chunks.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "至少需要一个片段才能确认记录");
        }
        java.util.Set<Long> validIds = chunks.stream().map(Chunk::getId).collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> approvedIds = aiMemoryChunkIds == null ? java.util.Set.of() :
                aiMemoryChunkIds.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> approvedTodoIds = todoChunkIds == null ? java.util.Set.of() :
                todoChunkIds.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!validIds.containsAll(approvedIds) || !validIds.containsAll(approvedTodoIds)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "片段不属于该记录");
        }

        String referenceDate = TimeSubstitutionApplier.referenceDateOf(record.getCreatedAt());
        java.util.List<Long> revokedMemoryChunkIds = new java.util.ArrayList<>();
        for (Chunk chunk : chunks) {
            boolean approveMemory = approvedIds.contains(chunk.getId());
            boolean approveTodo = approvedTodoIds.contains(chunk.getId());
            boolean wasApproved = Boolean.TRUE.equals(chunk.getAiMemoryApproved());
            boolean segmentResolved = false;
            if (wasApproved && !approveMemory) revokedMemoryChunkIds.add(chunk.getId());
            if ((approveMemory || approveTodo) && (chunk.getSegment() == null || chunk.getSegment().isBlank())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "空片段不能授权给 AI 或登记为待办");
            }
            if (approveTodo) {
                Object type = chunk.getMetadata() == null ? null : chunk.getMetadata().get("contentType");
                if (!"todo".equals(type) && !"plan".equals(type)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "只有待办或计划片段可以单独登记为待办");
                }
            }
            if (approveMemory && chunk.getClassifiedSegment() == null) {
                String text = chunk.getSegment(); // 永不以整篇原文作为片段授权的回退值
                if (text != null && !text.isBlank()) {
                    try {
                        RecordProcessorProto.ClassifyResponse response =
                                owner.aiGrpcClient.classifySingle(userId, text, chunk.getRecordId(), referenceDate);
                        if (!response.getSkip() && response.getItemsCount() > 0) {
                            RecordProcessorProto.ClassifyItem item = response.getItems(0);
                            chunk.setMetadata(ClassifyItemConverter.toMetadata(item));
                            String resolved = TimeSubstitutionApplier.apply(text, item.getTimeSubstitutionsList());
                            if (!java.util.Objects.equals(chunk.getSegment(), resolved)) {
                                chunk.setContentVersion((chunk.getContentVersion() == null ? 1L : chunk.getContentVersion()) + 1L);
                                segmentResolved = true;
                            }
                            chunk.setSegment(resolved);
                            chunk.setClassifiedSegment(resolved);
                        }
                    } catch (Exception e) {
                        owner.log.warn("片段补分类失败，保留待确认文本，chunk={}: {}", chunk.getId(), e.getMessage());
                    }
                }
            }
            boolean needsEmbedding = approveMemory && chunk.getSegment() != null && !chunk.getSegment().isBlank()
                    && (segmentResolved || !wasApproved || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty());
            if (!approveMemory) {
                chunk.setEmbedding(null);
                chunk.setEmbeddingStatus("not_requested");
                chunk.setEmbeddingError(null);
                owner.chunkEmbeddingJobService.cancel(chunk.getId(), userId);
            } else if (needsEmbedding) {
                chunk.setEmbedding(null);
                chunk.setEmbeddingStatus("pending");
                chunk.setEmbeddingError(null);
            } else if (chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty()) {
                chunk.setEmbeddingStatus("ready");
            }
            chunk.setAiMemoryApproved(approveMemory);
            // 已创建的待办由待办页管理；此处不因片段记忆开关变化而撤销它。
            chunk.setTodoApproved(approveTodo || Boolean.TRUE.equals(chunk.getTodoApproved()));
            if (needsEmbedding) owner.chunkMapper.clearEmbedding(chunk.getId(), userId);
            owner.chunkMapper.updateById(chunk);
            if (needsEmbedding) {
                if (chunk.getContentVersion() == null) chunk.setContentVersion(1L);
                owner.chunkEmbeddingJobService.enqueue(chunk);
                Long jobId = owner.chunkEmbeddingJobService.latestJobId(chunk.getId(), userId);
                if (jobId != null) {
                    if (record.getQueuedEmbeddingJobIds() == null) record.setQueuedEmbeddingJobIds(new java.util.ArrayList<>());
                    record.getQueuedEmbeddingJobIds().add(jobId);
                }
            }
            if (!approveMemory) owner.chunkMapper.clearEmbedding(chunk.getId(), userId);
        }

        int generation = record.getProcessingVersion() == null ? 0 : record.getProcessingVersion();
        if (approvedIds.isEmpty()) {
            record.setMemoryState(RecordMemoryState.NONE);
            record.setAiProcessingAuthorized(false);
            record.setStatus(RecordStatus.RECORD_ONLY);
            record.setMemoryActionId(null);
            record.setMemoryActionExecutionVersion(null);
        } else {
            boolean selectedChunksReady = chunks.stream().filter(c -> approvedIds.contains(c.getId()))
                    .allMatch(c -> "ready".equalsIgnoreCase(c.getEmbeddingStatus())
                            && c.getEmbedding() != null && !c.getEmbedding().isEmpty());
            record.setMemoryState(selectedChunksReady ? RecordMemoryState.READY : RecordMemoryState.PENDING);
            record.setAiProcessingAuthorized(true);
            record.setStatus(RecordStatus.DONE);
            record.setProcessingVersion(generation + 1);
            record.setMemoryActionId(selectedChunksReady ? null : actionId);
            record.setMemoryActionExecutionVersion(selectedChunksReady ? null : actionExecutionVersion);
        }
        record.setUserReviewed(true);
        record.setUpdatedAt(OffsetDateTime.now());
        if (approvedIds.isEmpty() || growthAdjustments == null || growthAdjustments.isEmpty()) {
            record.setGrowthSuggestions("[]"); record.setGrowthAxesVersion(0L);
        } else {
            long currentAxesVersion = owner.growthService.axesVersion(userId);
            if (axesVersion == null || axesVersion != currentAxesVersion)
                throw new BusinessException(409, "成长维度已变化，请刷新审核建议");
            try { record.setGrowthSuggestions(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(growthAdjustments)); }
            catch (Exception e) { throw new BusinessException(400, "成长调整格式无效"); }
            record.setGrowthAxesVersion(axesVersion);
        }
        owner.recordMapper.updateById(record);
        if (record.getMemoryState() == RecordMemoryState.READY && record.getGrowthSuggestions() != null
                && !"[]".equals(record.getGrowthSuggestions()) && owner.growthService != null) {
            try {
                var adjustments = new com.fasterxml.jackson.databind.ObjectMapper().readValue(record.getGrowthSuggestions(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO>>() {});
                owner.growthService.applyConfirmedRecord(userId, recordId, actionId, adjustments, record.getGrowthAxesVersion());
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new BusinessException(400, "成长调整格式无效"); }
        }
        for (Long revokedChunkId : revokedMemoryChunkIds) {
            owner.snapshotLineageService.invalidateSource(userId, "chunk", revokedChunkId, "memory_revoked");
        }

        // 8. 待办窗口决议（todo-status-removal-design.md §5）：审核页选的待办状态随本次入库一起生效。
        //    处理本记录 evidence 的 pending 建议：body.todoResolutions 中 confirmed 走 doConfirm
        //    （回写源头+证据 chunk taskStatus / registry / evidence link），dismissed 静默；
        //    未出现在 body 中的一律 dismissed（含 resolutions=null 的旧客户端——行为变化）。
        //    必须先于登记：evidence chunk 若本身是 todo 片段，回写的 taskStatus 会被登记读取。
        try {
            owner.todoRegistryService().applyRecordResolutions(recordId, userId, resolutions);
        } catch (BusinessException e) {
            throw e; // 参数/业务错误（action/status 非法）→ 400，事务回滚，不吞
        } catch (Exception e) {
            // 运行时故障（含单测无上下文）不阻断确认主流程：建议错误退化为无跟踪，账本零污染
            owner.log.warn("待办决议处理失败（不阻断确认主流程），记录ID: {}，原因: {}", recordId, e.getMessage());
        }

        // 9. 只有用户单独确认作为待办的片段才进入待办账本。
        try {
            int registered = owner.todoRegistryService().registerFromRecord(recordId, userId);
            if (registered > 0) {
                owner.log.info("confirmReview 待办登记：新增 {} 条，记录ID: {}", registered, recordId);
            }
        } catch (Exception e) {
            owner.log.warn("待办登记失败（不阻断确认主流程），记录ID: {}，原因: {}", recordId, e.getMessage());
        }

        return owner.toVO(record);
    }
}
