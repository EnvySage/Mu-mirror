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
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.TagMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.Tag;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordQueryDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collections;
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
    private final TagMapper tagMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AiGrpcClient aiGrpcClient;

    @Override
    @Transactional
    public RecordVO create(RecordDTO dto, UUID userId) {
        // 1. 校验内容非空
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "内容不能为空");
        }

        // 2. 存原始内容入库（status=processing，数据不丢）
        Record record = Record.builder()
                .userId(userId)
                .content(dto.getContent())
                .status(RecordStatus.PROCESSING)
                .userReviewed(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        recordMapper.insert(record);
        log.info("记录已入库，ID: {}", record.getId());

        // 3. 发布事件（事务提交后，监听器异步执行管道处理）
        eventPublisher.publishEvent(new RecordCreatedEvent(this, record.getId(), userId));

        // 4. 立即返回（status=processing，前端显示转圈动画）
        return toVO(record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordVO> list(RecordQueryDTO queryDTO, UUID userId) {
        // 1. 处理日期范围（默认查询今天的记录）
        LocalDate startDate = queryDTO.getStartDate();
        LocalDate endDate = queryDTO.getEndDate();

        if (startDate == null && endDate == null) {
            // 都不传，默认查询今天
            startDate = LocalDate.now();
            endDate = startDate;
        } else if (startDate == null) {
            // 只传了 endDate，startDate 默认为 endDate
            startDate = endDate;
        } else if (endDate == null) {
            // 只传了 startDate，endDate 默认为 startDate
            endDate = startDate;
        }

        // 2. 转换为 OffsetDateTime 范围（UTC）
        OffsetDateTime startDateTime = startDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endDateTime = endDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        // 3. 构建查询条件（只查询未删除的记录）
        LambdaQueryWrapper<Record> wrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .isNull(Record::getDeletedAt)  // 软删除过滤
                .ge(Record::getCreatedAt, startDateTime)
                .lt(Record::getCreatedAt, endDateTime)
                .orderByDesc(Record::getCreatedAt);

        // 4. 执行查询
        List<Record> records = recordMapper.selectList(wrapper);
        log.info("查询到 {} 条记录，用户: {}, 日期范围: {} ~ {}", records.size(), userId, startDate, endDate);

        // 5. 转换为 VO 列表
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecordVO getById(Long recordId, UUID userId) {
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

        return toVO(record);
    }

    @Override
    @Transactional
    public RecordVO update(Long recordId, RecordDTO dto, UUID userId) {
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

        // 2. 检查状态：只有 REVIEWING 状态（人工审查中）才允许更新
        if (record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能修改");
        }

        // 3. 更新允许修改的字段（content 不能修改，是原始输入）
        if (dto.getTitle() != null) {
            record.setTitle(dto.getTitle());
        }
        if (dto.getSummary() != null) {
            record.setSummary(dto.getSummary());
        }
        if (dto.getContentType() != null) {
            record.setContentType(dto.getContentType());
        }
        if (dto.getMood() != null) {
            record.setMood(dto.getMood());
        }
        if (dto.getKeywords() != null) {
            record.setKeywords(dto.getKeywords());
        }

        // 4. 标记用户已审核
        record.setUserReviewed(true);
        record.setUpdatedAt(OffsetDateTime.now());

        // 5. 保存更新
        recordMapper.updateById(record);
        log.info("记录已更新，ID: {}, 用户: {}", recordId, userId);

        // 6. 如果有新的关键词，更新标签表
        if (dto.getKeywords() != null) {
            updateTags(recordId, dto.getKeywords());
        }

        return toVO(record);
    }

    @Override
    @Transactional
    public void softDelete(Long recordId, UUID userId) {
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

        // 2. 检查状态：REVIEWING 和 FAILED 状态允许删除
        if (record.getStatus() != RecordStatus.REVIEWING && record.getStatus() != RecordStatus.FAILED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查或失败的记录才能删除");
        }

        // 3. 设置软删除时间
        record.setDeletedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        log.info("记录已软删除，ID: {}, 用户: {}", recordId, userId);
    }

    @Override
    @Transactional
    public RecordVO confirmReview(Long recordId, UUID userId) {
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

        // 2. 检查状态：只有 REVIEWING 状态才能确认完成
        if (record.getStatus() != RecordStatus.REVIEWING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只有待审查的记录才能确认完成");
        }

        // 3. 标记已审核
        record.setUserReviewed(true);

        // 4. Embedding：将用户确认后的最终版本转向量
        //    TODO: 向量存入 chunks 表（Chunk 实体 + ChunkMapper 待实现）
        try {
            EmbeddingProto.EmbedResponse embedResult = aiGrpcClient.embed(userId, record.getContent());
            log.info("Embedding 完成，记录ID: {}, 维度: {}", recordId, embedResult.getDimension());
            // TODO: 将 embedResult.getVectorList() 存入 chunks 表
            // Chunk chunk = Chunk.builder()
            //         .userId(userId)
            //         .recordId(recordId)
            //         .content(record.getContent())
            //         .embedding(embedResult.getVectorList())
            //         .build();
            // chunkMapper.insert(chunk);
        } catch (Exception e) {
            // Embedding 失败不影响确认，记录保持 DONE 状态，后续可重试
            log.error("Embedding 失败，记录ID: {}，原因: {}", recordId, e.getMessage(), e);
        }

        // 5. 更新状态为 DONE，记录锁定
        record.setStatus(RecordStatus.DONE);
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);
        log.info("记录审查已确认完成，ID: {}, 用户: {}", recordId, userId);

        return toVO(record);
    }

    /**
     * 更新标签表（先删后插）
     */
    private void updateTags(Long recordId, List<String> keywords) {
        // 删除旧标签
        tagMapper.delete(
                new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getRecordId, recordId)
        );

        // 插入新标签
        if (keywords != null && !keywords.isEmpty()) {
            List<Tag> tags = keywords.stream()
                    .map(keyword -> Tag.builder()
                            .recordId(recordId)
                            .keyword(keyword)
                            .createdAt(OffsetDateTime.now())
                            .build())
                    .toList();
            tags.forEach(tagMapper::insert);
        }
    }

    /**
     * DO → VO 转换
     */
    private RecordVO toVO(Record record) {
        List<Tag> tags = tagMapper.selectList(
                new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getRecordId, record.getId())
        );
        List<String> keywords = tags.stream().map(Tag::getKeyword).toList();

        return RecordVO.builder()
                .id(record.getId())
                .content(record.getContent())
                .title(record.getTitle())
                .summary(record.getSummary())
                .contentType(record.getContentType())
                .mood(record.getMood() != null ? record.getMood() : Collections.emptyList())
                .status(record.getStatus())
                .userReviewed(record.getUserReviewed())
                .keywords(keywords)
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> getCalendarDates(String month, UUID userId) {
        // 解析月份 "2026-08" → YearMonth
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.plusMonths(1).atDay(1);

        OffsetDateTime monthStart = startDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();
        OffsetDateTime monthEnd = endDate.atStartOfDay(ZoneOffset.ofHours(8)).toOffsetDateTime();

        List<Map<String, Object>> rows = recordMapper.countByDay(userId, monthStart, monthEnd);

        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String date = (String) row.get("date");
            Number cnt = (Number) row.get("cnt");
            result.put(date, cnt.intValue());
        }
        return result;
    }
}
