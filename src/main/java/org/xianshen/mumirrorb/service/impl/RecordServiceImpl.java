package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
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

import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordServiceImpl implements RecordService {

    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AiGrpcClient aiGrpcClient;
    private final org.springframework.context.ApplicationContext applicationContext;

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

        Record record = Record.builder()
                .userId(userId)
                .content(dto.getContent())
                .source("user")
                .status(dto.isRequestAiProcessing() ? RecordStatus.PROCESSING : RecordStatus.RECORD_ONLY)
                .aiProcessingAuthorized(dto.isRequestAiProcessing())
                .processingVersion(dto.isRequestAiProcessing() ? 1 : 0)
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
        Record record = recordMapper.selectOne(new LambdaQueryWrapper<Record>()
                .eq(Record::getId, recordId).eq(Record::getUserId, userId)
                .eq(Record::getSource, "user").isNull(Record::getDeletedAt));
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        if (record.getStatus() != RecordStatus.RECORD_ONLY) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有仅记录状态可以请求 AI 整理");
        }
        int version = (record.getProcessingVersion() == null ? 0 : record.getProcessingVersion()) + 1;
        record.setAiProcessingAuthorized(true);
        record.setProcessingVersion(version);
        record.setStatus(RecordStatus.PROCESSING);
        record.setFailReason(null);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        eventPublisher.publishEvent(new RecordCreatedEvent(this, recordId, userId, version));
        return toVO(record);
    }

    @Override
    @Transactional
    public RecordVO updateRaw(Long recordId, String content, UUID userId) {
        if (content == null || content.isBlank())
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        Record record = recordMapper.selectOne(new LambdaQueryWrapper<Record>()
                .eq(Record::getId, recordId).eq(Record::getUserId, userId)
                .eq(Record::getSource, "user").isNull(Record::getDeletedAt));
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        if (record.getStatus() != RecordStatus.RECORD_ONLY || Boolean.TRUE.equals(record.getAiProcessingAuthorized()))
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
        profileSnapshotMapper.invalidateForUser(userId);
        log.info("记录已软删除，ID: {}, 用户: {}", recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO retry(Long recordId, UUID userId) {
        log.info("============ retry 开始，记录ID: {}, 用户: {} ============", recordId, userId);

        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );

        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }

        // 裁决 #21：仅 FAILED 可重试（重跑分类管道）
        if (record.getStatus() != RecordStatus.FAILED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有处理失败的记录才能重试");
        }
        if (!Boolean.TRUE.equals(record.getAiProcessingAuthorized())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该记录尚未获得 AI 整理授权");
        }

        record.setStatus(RecordStatus.PROCESSING);
        record.setProcessingVersion((record.getProcessingVersion() == null ? 0 : record.getProcessingVersion()) + 1);
        record.setFailReason(null); // 清除上次失败原因
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);

        eventPublisher.publishEvent(new RecordCreatedEvent(this, record.getId(), userId, record.getProcessingVersion()));
        log.info("记录已重置为 processing 并重新发布管道事件，ID: {}", recordId);
        return toVO(record);
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
        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .eq(Record::getSource, "user")
                        .isNull(Record::getDeletedAt)
        );
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
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
        boolean authorizationChanged = false;
        for (Chunk chunk : chunks) {
            boolean approveMemory = approvedIds.contains(chunk.getId());
            boolean approveTodo = approvedTodoIds.contains(chunk.getId());
            boolean wasApproved = Boolean.TRUE.equals(chunk.getAiMemoryApproved());
            authorizationChanged |= wasApproved != approveMemory;
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
                            chunk.setSegment(resolved);
                            chunk.setClassifiedSegment(resolved);
                        }
                    } catch (Exception e) {
                        log.warn("片段补分类失败，保留待确认文本，chunk={}: {}", chunk.getId(), e.getMessage());
                    }
                }
            }
            if (approveMemory && chunk.getSegment() != null && !chunk.getSegment().isBlank() &&
                    (!wasApproved || chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty())) {
                try {
                    EmbeddingProto.EmbedResponse embedded = aiGrpcClient.embed(userId, chunk.getSegment());
                    chunk.setEmbedding(embedded.getVectorList());
                } catch (Exception e) {
                    log.warn("片段向量生成失败，后续可补录，chunk={}: {}", chunk.getId(), e.getMessage());
                }
            }
            if (!approveMemory) chunk.setEmbedding(null);
            chunk.setAiMemoryApproved(approveMemory);
            // 已创建的待办由待办页管理；此处不因片段记忆开关变化而撤销它。
            chunk.setTodoApproved(approveTodo || Boolean.TRUE.equals(chunk.getTodoApproved()));
            chunkMapper.updateById(chunk);
        }

        if (record.getStatus() == RecordStatus.REVIEWING) record.setStatus(RecordStatus.DONE);
        record.setUserReviewed(true);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        if (authorizationChanged) profileSnapshotMapper.invalidateForUser(userId);

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
