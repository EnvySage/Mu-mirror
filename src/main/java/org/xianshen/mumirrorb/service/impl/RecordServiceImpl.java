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

/**
 * 记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordServiceImpl implements RecordService {
    @org.springframework.beans.factory.annotation.Autowired private org.xianshen.mumirrorb.mapper.GrowthEntryMapper growthEntries;
    @org.springframework.beans.factory.annotation.Autowired private org.xianshen.mumirrorb.service.GrowthService growthService;

    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final SnapshotLineageService snapshotLineageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AiGrpcClient aiGrpcClient;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final ChunkEmbeddingJobService chunkEmbeddingJobService;

    /**
     * 待办登记服务（todo-registry-design.md §3.1）
     *
     * <p>懒加载（应用上下文查找）：RecordServiceImpl 与 TodoRegistryServiceImpl 无相互依赖，
     * 不存在环；走构造器注入需要确认 Bean 初始化顺序无副作用，这里按调用期解析最稳。
     * confirmReview 调用时上下文必然就绪。</p>
     */
    private org.xianshen.mumirrorb.service.TodoRegistryService todoRegistryService() {
        return applicationContext.getBean(org.xianshen.mumirrorb.service.TodoRegistryService.class);
    }

    @Override
    @Transactional
    public RecordVO create(RecordDTO dto, UUID userId) {
        log.info("============ 创建记录开始 ============");
        log.info("用户ID: {}, 内容长度: {}", userId, dto.getContent() != null ? dto.getContent().length() : 0);

        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        }
        if (dto.isRequestAiProcessing()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "AI 整理必须先准备可审核的操作卡，请调用 /assistant/actions/prepare");
        }

        if (dto.getCorrectionOfRecordId() != null) {
            Record source = recordMapper.selectOne(new LambdaQueryWrapper<Record>()
                    .eq(Record::getId, dto.getCorrectionOfRecordId())
                    .eq(Record::getUserId, userId).eq(Record::getSource, "user")
                    .isNull(Record::getDeletedAt));
            if (source == null || (source.getMemoryState() != RecordMemoryState.READY
                    && source.getMemoryState() != RecordMemoryState.DISABLED))
                throw new BusinessException(ResultCode.PARAM_ERROR, "更正来源必须是本人已确认或已停止使用的只读记录");
        }

        Record record = Record.builder()
                .userId(userId)
                .content(dto.getContent())
                .source("user")
                .status(dto.isRequestAiProcessing() ? RecordStatus.PROCESSING : RecordStatus.RECORD_ONLY)
                .aiProcessingAuthorized(dto.isRequestAiProcessing())
                .processingVersion(dto.isRequestAiProcessing() ? 1 : 0)
                .memoryState(RecordMemoryState.NONE)
                .correctionOfRecordId(dto.getCorrectionOfRecordId())
                .userReviewed(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        recordMapper.insert(record);
        log.info("记录已入库，ID: {}, 状态: {}", record.getId(), record.getStatus().getValue());

        if (dto.isRequestAiProcessing()) {
            eventPublisher.publishEvent(new RecordCreatedEvent(this, record.getId(), userId,
                    record.getProcessingVersion()));
            log.info("用户请求本次 AI 整理，已发布 RecordCreatedEvent，记录ID: {}", record.getId());
        }

        log.info("============ 创建记录结束 ============");
        return toVO(record);
    }

    @Override
    @Transactional
    public RecordVO organize(Long recordId, UUID userId) {
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "旧的直接整理入口已关闭，请先通过操作卡准备候选并由用户确认");
    }

    @Override
    @Transactional
    public RecordVO updateRaw(Long recordId, String content, UUID userId) {
        if (content == null || content.isBlank())
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        Record record = recordMapper.selectOwnedForUpdate(userId, recordId);
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        if (record.getStatus() != RecordStatus.RECORD_ONLY || Boolean.TRUE.equals(record.getAiProcessingAuthorized())
                || (record.getMemoryState() != null && record.getMemoryState() != RecordMemoryState.NONE))
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有未授权 AI 的仅记录可以编辑原文");
        record.setContent(content);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        return toVO(record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId) {
        LocalDate startDate = queryDTO.getStartDate();
        LocalDate endDate = queryDTO.getEndDate();

        if (startDate == null && endDate == null) {
            startDate = LocalDate.now();
            endDate = startDate;
        } else if (startDate == null) {
            startDate = endDate;
        } else if (endDate == null) {
            endDate = startDate;
        }

        // 日期口径统一 Asia/Shanghai（与日历统计 TO_CHAR ... AT TIME ZONE 一致）
        OffsetDateTime startDateTime = startDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime endDateTime = endDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();

        LambdaQueryWrapper<Record> wrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")       // 统计口径：只查用户输入，排除系统记录
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, startDateTime)
                .lt(Record::getCreatedAt, endDateTime)
                .orderByDesc(Record::getCreatedAt);

        List<Record> records = recordMapper.selectList(wrapper);
        if (queryDTO.getTags() != null && !queryDTO.getTags().isEmpty()) {
            Set<String> requested = queryDTO.getTags().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet());
            if (!requested.isEmpty()) {
                Set<Long> matching = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                                .eq(Chunk::getUserId, userId).in(Chunk::getRecordId, records.stream().map(Record::getId).toList()))
                        .stream().filter(c -> c.getMetadata() != null && tagsOf(c.getMetadata()) instanceof List<?> tags
                                && tags.stream().map(String::valueOf).anyMatch(requested::contains))
                        .map(Chunk::getRecordId).collect(java.util.stream.Collectors.toSet());
                records = records.stream().filter(r -> matching.contains(r.getId())).toList();
            }
        }
        log.info("查询到 {} 条记录，用户: {}, 日期范围: {} ~ {}", records.size(), userId, startDate, endDate);

        return records.stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecordVO getById(Long recordId, UUID userId) {
        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );

        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }

        return toVO(record);
    }

    @Override
    @Transactional
    public void softDelete(Long recordId, UUID userId) {
        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );

        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }

        if (!"user".equals(record.getSource()))
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持删除用户记录");
        record.setDeletedAt(OffsetDateTime.now());
        record.setProcessingVersion((record.getProcessingVersion() == null ? 0 : record.getProcessingVersion()) + 1);
        recordMapper.updateById(record);
        if (growthEntries != null) growthEntries.delete(new LambdaQueryWrapper<org.xianshen.mumirrorb.pojo.DO.GrowthEntry>()
                .eq(org.xianshen.mumirrorb.pojo.DO.GrowthEntry::getUserId, userId)
                .eq(org.xianshen.mumirrorb.pojo.DO.GrowthEntry::getSourceRecordId, recordId));
        snapshotLineageService.invalidateSource(userId, "record", recordId, "record_deleted");
        log.info("记录已软删除，ID: {}, 用户: {}", recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO retry(Long recordId, UUID userId) {
        throw new BusinessException(ResultCode.PARAM_ERROR, "请通过记录重试操作卡准备候选，并由用户确认后执行");
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId) {
        return confirmReview(recordId, userId, List.of(), List.of(), null);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions) {
        return confirmReview(recordId, userId, aiMemoryChunkIds, todoChunkIds, resolutions, null, null);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion) {
        return confirmReview(recordId,userId,aiMemoryChunkIds,todoChunkIds,resolutions,actionId,actionExecutionVersion,null,null);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId, List<Long> aiMemoryChunkIds,
                                  List<Long> todoChunkIds, List<TodoResolutionDTO> resolutions,
                                  UUID actionId, Integer actionExecutionVersion,
                                  List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO> growthAdjustments, Long axesVersion) {
        Record record = recordMapper.selectOwnedForUpdate(userId, recordId);
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        if (record.getMemoryState() == RecordMemoryState.PENDING || record.getMemoryState() == RecordMemoryState.READY
                || record.getMemoryState() == RecordMemoryState.DISABLED)
            throw new BusinessException(ResultCode.PARAM_ERROR, "该记录的已确认正文不可再次修改");
        if (record.getStatus() != RecordStatus.REVIEWING && record.getStatus() != RecordStatus.DONE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有已完成 AI 整理的记录才能确认片段");
        }

        List<Chunk> chunks = chunkMapper.selectList(
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
                                aiGrpcClient.classifySingle(userId, text, chunk.getRecordId(), referenceDate);
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
                        log.warn("片段补分类失败，保留待确认文本，chunk={}: {}", chunk.getId(), e.getMessage());
                    }
                }
            }
            boolean needsEmbedding = approveMemory && chunk.getSegment() != null && !chunk.getSegment().isBlank()
                    && (segmentResolved || !wasApproved || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty());
            if (!approveMemory) {
                chunk.setEmbedding(null);
                chunk.setEmbeddingStatus("not_requested");
                chunk.setEmbeddingError(null);
                chunkEmbeddingJobService.cancel(chunk.getId(), userId);
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
            if (needsEmbedding) chunkMapper.clearEmbedding(chunk.getId(), userId);
            chunkMapper.updateById(chunk);
            if (needsEmbedding) {
                if (chunk.getContentVersion() == null) chunk.setContentVersion(1L);
                chunkEmbeddingJobService.enqueue(chunk);
                Long jobId = chunkEmbeddingJobService.latestJobId(chunk.getId(), userId);
                if (jobId != null) {
                    if (record.getQueuedEmbeddingJobIds() == null) record.setQueuedEmbeddingJobIds(new java.util.ArrayList<>());
                    record.getQueuedEmbeddingJobIds().add(jobId);
                }
            }
            if (!approveMemory) chunkMapper.clearEmbedding(chunk.getId(), userId);
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
            long currentAxesVersion = growthService.axesVersion(userId);
            if (axesVersion == null || axesVersion != currentAxesVersion)
                throw new BusinessException(409, "成长维度已变化，请刷新审核建议");
            try { record.setGrowthSuggestions(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(growthAdjustments)); }
            catch (Exception e) { throw new BusinessException(400, "成长调整格式无效"); }
            record.setGrowthAxesVersion(axesVersion);
        }
        recordMapper.updateById(record);
        if (record.getMemoryState() == RecordMemoryState.READY && record.getGrowthSuggestions() != null
                && !"[]".equals(record.getGrowthSuggestions()) && growthService != null) {
            try {
                var adjustments = new com.fasterxml.jackson.databind.ObjectMapper().readValue(record.getGrowthSuggestions(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO>>() {});
                growthService.applyConfirmedRecord(userId, recordId, actionId, adjustments, record.getGrowthAxesVersion());
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new BusinessException(400, "成长调整格式无效"); }
        }
        for (Long revokedChunkId : revokedMemoryChunkIds) {
            snapshotLineageService.invalidateSource(userId, "chunk", revokedChunkId, "memory_revoked");
        }

        // 8. 待办窗口决议（todo-status-removal-design.md §5）：审核页选的待办状态随本次入库一起生效。
        //    处理本记录 evidence 的 pending 建议：body.todoResolutions 中 confirmed 走 doConfirm
        //    （回写源头+证据 chunk taskStatus / registry / evidence link），dismissed 静默；
        //    未出现在 body 中的一律 dismissed（含 resolutions=null 的旧客户端——行为变化）。
        //    必须先于登记：evidence chunk 若本身是 todo 片段，回写的 taskStatus 会被登记读取。
        try {
            todoRegistryService().applyRecordResolutions(recordId, userId, resolutions);
        } catch (BusinessException e) {
            throw e; // 参数/业务错误（action/status 非法）→ 400，事务回滚，不吞
        } catch (Exception e) {
            // 运行时故障（含单测无上下文）不阻断确认主流程：建议错误退化为无跟踪，账本零污染
            log.warn("待办决议处理失败（不阻断确认主流程），记录ID: {}，原因: {}", recordId, e.getMessage());
        }

        // 9. 只有用户单独确认作为待办的片段才进入待办账本。
        try {
            int registered = todoRegistryService().registerFromRecord(recordId, userId);
            if (registered > 0) {
                log.info("confirmReview 待办登记：新增 {} 条，记录ID: {}", registered, recordId);
            }
        } catch (Exception e) {
            log.warn("待办登记失败（不阻断确认主流程），记录ID: {}，原因: {}", recordId, e.getMessage());
        }

        return toVO(record);
    }

    /**
     * DO → VO 转换（包含关联的 Chunk 列表）
     */
    /** 自由标签统一键：新数据 userTags，兼容旧 user_tags / tags。 */
    private Object tagsOf(Map<String, Object> metadata) {
        Object tags = metadata.get("userTags");
        if (tags instanceof List) return tags;
        tags = metadata.get("user_tags");
        if (tags instanceof List) return tags;
        return metadata.get("tags");
    }

    /** 解析记录上的五维成长建议；历史坏数据按空处理，不影响记录详情。 */
    private List<Object> growthSuggestionsOf(Record record) {
        String raw = record.getGrowthSuggestions();
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        } catch (Exception e) {
            log.warn("记录成长建议解析失败，按空处理，recordId={}: {}", record.getId(), e.getMessage());
            return List.of();
        }
    }

    private RecordVO toVO(Record record) {
        // 查询关联的 Chunks
        List<Chunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getRecordId, record.getId())
        );

        List<ChunkVO> chunkVOs = chunks.stream()
                .map(this::toChunkVO)
                .toList();

        return RecordVO.builder()
                .id(record.getId())
                .content(record.getContent())
                .segments(chunkVOs.stream().map(ChunkVO::getSegment).toList())
                .status(record.getStatus())
                .aiProcessingAuthorized(record.getAiProcessingAuthorized())
                .memoryState(record.getMemoryState() == null ? RecordMemoryState.NONE : record.getMemoryState())
                .processingVersion(record.getProcessingVersion())
                .correctionOfRecordId(record.getCorrectionOfRecordId())
                .memoryFailureReason(record.getMemoryFailureReason())
                .queuedEmbeddingJobIds(record.getQueuedEmbeddingJobIds())
                .growthSuggestions(growthSuggestionsOf(record))
                .growthAxesVersion(record.getGrowthAxesVersion())
                .userReviewed(record.getUserReviewed())
                .failReason(record.getFailReason())
                .chunks(chunkVOs)
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    /**
     * Chunk DO → ChunkVO 转换
     */
    private ChunkVO toChunkVO(Chunk chunk) {
        return ChunkVO.builder()
                .id(chunk.getId())
                .recordId(chunk.getRecordId())
                .segment(chunk.getSegment())
                .metadata(chunk.getMetadata())
                .hasEmbedding(chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty())
                .aiMemoryApproved(Boolean.TRUE.equals(chunk.getAiMemoryApproved()))
                .todoApproved(Boolean.TRUE.equals(chunk.getTodoApproved()))
                .contentVersion(chunk.getContentVersion())
                .embeddingStatus(chunk.getEmbeddingStatus() == null
                        ? (chunk.getEmbedding() == null ? "not_requested" : "ready")
                        : chunk.getEmbeddingStatus())
                .embeddingError(chunk.getEmbeddingError())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> getCalendarDates(String month, UUID userId) {
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.plusMonths(1).atDay(1);

        OffsetDateTime monthStart = startDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();
        OffsetDateTime monthEnd = endDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();

        List<CalendarDayVO> rows = recordMapper.countByDay(userId, monthStart, monthEnd);

        Map<String, Integer> result = new HashMap<>();
        for (CalendarDayVO row : rows) {
            result.put(row.getDate(), row.getCnt());
        }
        return result;
    }
}
