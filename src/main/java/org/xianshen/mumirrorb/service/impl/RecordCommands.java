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

/** RecordCommands keeps the service entry points and transaction boundaries in RecordServiceImpl. */
final class RecordCommands {
    private final RecordServiceImpl owner;
    RecordCommands(RecordServiceImpl owner) { this.owner=owner; }


    /**
     * 待办登记服务（todo-registry-design.md §3.1）
     *
     * <p>懒加载（应用上下文查找）：RecordServiceImpl 与 TodoRegistryServiceImpl 无相互依赖，
     * 不存在环；走构造器注入需要确认 Bean 初始化顺序无副作用，这里按调用期解析最稳。
     * confirmReview 调用时上下文必然就绪。</p>
     */
    protected org.xianshen.mumirrorb.service.TodoRegistryService todoRegistryService() {
        return owner.applicationContext.getBean(org.xianshen.mumirrorb.service.TodoRegistryService.class);
    }


    @Transactional
    public RecordVO create(RecordDTO dto, UUID userId) {
        owner.log.info("============ 创建记录开始 ============");
        owner.log.info("用户ID: {}, 内容长度: {}", userId, dto.getContent() != null ? dto.getContent().length() : 0);

        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        }
        if (dto.isRequestAiProcessing()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "AI 整理必须先准备可审核的操作卡，请调用 /assistant/actions/prepare");
        }

        if (dto.getCorrectionOfRecordId() != null) {
            Record source = owner.recordMapper.selectOne(new LambdaQueryWrapper<Record>()
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
        owner.recordMapper.insert(record);
        owner.log.info("记录已入库，ID: {}, 状态: {}", record.getId(), record.getStatus().getValue());

        if (dto.isRequestAiProcessing()) {
            owner.eventPublisher.publishEvent(new RecordCreatedEvent(owner, record.getId(), userId,
                    record.getProcessingVersion()));
            owner.log.info("用户请求本次 AI 整理，已发布 RecordCreatedEvent，记录ID: {}", record.getId());
        }

        owner.log.info("============ 创建记录结束 ============");
        return owner.toVO(record);
    }


    @Transactional
    public RecordVO organize(Long recordId, UUID userId) {
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "旧的直接整理入口已关闭，请先通过操作卡准备候选并由用户确认");
    }


    @Transactional
    public RecordVO updateRaw(Long recordId, String content, UUID userId) {
        if (content == null || content.isBlank())
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        Record record = owner.recordMapper.selectOwnedForUpdate(userId, recordId);
        if (record == null) throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        if (record.getStatus() != RecordStatus.RECORD_ONLY || Boolean.TRUE.equals(record.getAiProcessingAuthorized())
                || (record.getMemoryState() != null && record.getMemoryState() != RecordMemoryState.NONE))
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有未授权 AI 的仅记录可以编辑原文");
        record.setContent(content);
        record.setUpdatedAt(OffsetDateTime.now());
        owner.recordMapper.updateById(record);
        return owner.toVO(record);
    }


    @Transactional
    public void softDelete(Long recordId, UUID userId) {
        Record record = owner.recordMapper.selectOne(
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
        owner.recordMapper.updateById(record);
        if (owner.growthEntries != null) owner.growthEntries.delete(new LambdaQueryWrapper<org.xianshen.mumirrorb.pojo.DO.GrowthEntry>()
                .eq(org.xianshen.mumirrorb.pojo.DO.GrowthEntry::getUserId, userId)
                .eq(org.xianshen.mumirrorb.pojo.DO.GrowthEntry::getSourceRecordId, recordId));
        owner.snapshotLineageService.invalidateSource(userId, "record", recordId, "record_deleted");
        owner.log.info("记录已软删除，ID: {}, 用户: {}", recordId, userId);
    }


    @Transactional
    public RecordVO retry(Long recordId, UUID userId) {
        throw new BusinessException(ResultCode.PARAM_ERROR, "请通过记录重试操作卡准备候选，并由用户确认后执行");
    }
}
