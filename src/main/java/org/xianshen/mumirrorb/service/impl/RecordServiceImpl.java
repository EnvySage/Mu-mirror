package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.TagMapper;
import org.xianshen.mumirrorb.pipeline.event.RecordCreatedEvent;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.Tag;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

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

    @Override
    @Transactional
    public RecordVO create(RecordDTO dto, String userId) {
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
//        eventPublisher.publishEvent(new RecordCreatedEvent(this, record.getId(), userId));

        // 4. 立即返回（status=processing，前端显示转圈动画）
        return toVO(record);
    }

    /**
     * DO → VO 转换
     */
    private RecordVO toVO(Record record) {
        List<Tag> tags = tagMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tag>()
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
}
