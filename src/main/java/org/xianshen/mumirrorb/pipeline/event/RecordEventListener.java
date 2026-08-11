package org.xianshen.mumirrorb.pipeline.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.TagMapper;
import org.xianshen.mumirrorb.pipeline.RecordPipeline;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.Tag;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记录事件监听器
 *
 * 监听 RecordCreatedEvent，在新线程中执行管道处理。
 * 使用 @TransactionalEventListener 保证在事务提交后再处理（记录已入库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordEventListener {

    private final RecordPipeline pipeline;
    private final RecordMapper recordMapper;
    private final TagMapper tagMapper;

    /**
     * 记录创建后，异步执行管道处理
     *
     * @param event 记录创建事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecordCreated(RecordCreatedEvent event) {
        Long recordId = event.getRecordId();
        log.info("异步管道开始，记录ID: {}", recordId);

        // 1. 查出记录
        Record record = recordMapper.selectById(recordId);
        if (record == null) {
            log.error("记录不存在，ID: {}", recordId);
            return;
        }

        // 2. 跑管道（清洗 → 分类）
        try {
            record = pipeline.execute(record);
            record.setStatus(RecordStatus.REVIEWING);
            log.info("异步管道完成，记录ID: {}, 等待用户审核", recordId);
        } catch (Exception e) {
            record.setStatus(RecordStatus.FAILED);
            log.error("异步管道失败，记录ID: {}，原因: {}", recordId, e.getMessage());
        }

        // 3. 更新记录
        record.setUpdatedAt(OffsetDateTime.now());
        recordMapper.updateById(record);

        // 4. 保存关键词标签
        if (record.getKeywords() != null && !record.getKeywords().isEmpty()) {
            saveTags(recordId, record.getKeywords());
        }
    }

    /**
     * 保存关键词标签（先删后插）
     */
    private void saveTags(Long recordId, List<String> keywords) {
        tagMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tag>()
                        .eq(Tag::getRecordId, recordId)
        );
        for (String keyword : keywords) {
            Tag tag = Tag.builder()
                    .recordId(recordId)
                    .keyword(keyword)
                    .createdAt(OffsetDateTime.now())
                    .build();
            tagMapper.insert(tag);
        }
    }
}
