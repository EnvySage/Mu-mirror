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
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.VO.CalendarDayVO;
import org.xianshen.mumirrorb.pojo.VO.ChunkVO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
    private final ApplicationEventPublisher eventPublisher;
    private final AiGrpcClient aiGrpcClient;

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
                .status(RecordStatus.PROCESSING)
                .userReviewed(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        recordMapper.insert(record);
        log.info("记录已入库，ID: {}, 状态: processing", record.getId());

        eventPublisher.publishEvent(new RecordCreatedEvent(this, record.getId(), userId));
        log.info("已发布 RecordCreatedEvent，记录ID: {}", record.getId());

        log.info("============ 创建记录结束 ============");
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

        OffsetDateTime startDateTime = startDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endDateTime = endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        LambdaQueryWrapper<Record> wrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
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

        if (record.getStatus() != RecordStatus.REVIEWING && record.getStatus() != RecordStatus.FAILED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查或失败的记录才能删除");
        }

        record.setDeletedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        log.info("记录已软删除，ID: {}, 用户: {}", recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId) {
        log.info("============ confirmReview 开始 ============");
        log.info("记录ID: {}, 用户ID: {}", recordId, userId);

        // 1. 查询记录并验证所有权
        Record record = recordMapper.selectOne(
                new LambdaQueryWrapper<Record>()
                        .eq(Record::getId, recordId)
                        .eq(Record::getUserId, userId)
                        .isNull(Record::getDeletedAt)
        );

        if (record == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "记录不存在或已被删除");
        }

        if (record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能确认完成");
        }

        // 2. 标记已审核
        record.setUserReviewed(true);

        // 3. 遍历所有 Chunk，逐个做 embedding
        List<Chunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getRecordId, recordId)
        );
        log.info("开始 Embedding，记录ID: {}, Chunk 数量: {}", recordId, chunks.size());

        for (Chunk chunk : chunks) {
            String embeddingText = chunk.getSegment() != null ? chunk.getSegment() : chunk.getContent();
            try {
                EmbeddingProto.EmbedResponse embedResult = aiGrpcClient.embed(userId, embeddingText);
                chunk.setEmbedding(embedResult.getVectorList());
                chunkMapper.updateById(chunk);
                log.info("Chunk Embedding 完成，ChunkID: {}, 维度: {}", chunk.getId(), embedResult.getDimension());
            } catch (Exception e) {
                log.error("Chunk Embedding 失败，ChunkID: {}，原因: {}", chunk.getId(), e.getMessage(), e);
                // 单个 chunk 失败不影响其他 chunk
            }
        }

        // 4. 更新 Record 状态为 DONE
        record.setStatus(RecordStatus.DONE);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        log.info("记录审查已确认完成，ID: {}, 状态: DONE", recordId);

        log.info("============ confirmReview 结束 ============");
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
                .segment(record.getSegment())
                .status(record.getStatus())
                .userReviewed(record.getUserReviewed())
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
