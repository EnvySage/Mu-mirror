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
import java.util.UUID;

/**
 * 记录事件监听器
 *
 * <p>监听 RecordCreatedEvent，在新线程中执行管道处理。</p>
 * <p>使用 @TransactionalEventListener 保证在事务提交后再处理（记录已入库）。</p>
 *
 * <p><strong>拆分处理逻辑：</strong></p>
 * <ul>
 *   <li>管道返回 List<Record>，第一条复用原记录 ID（更新），后续插入新记录</li>
 *   <li>拆分后的每条记录都设为 REVIEWING 状态，等待用户审核</li>
 *   <li>每条记录的关键词标签独立保存</li>
 * </ul>
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
        UUID userId = event.getUserId();
        log.info("============ 异步管道开始 ============");
        log.info("记录ID: {}, 用户ID: {}", recordId, userId);

        // 1. 查出记录
        Record originalRecord = recordMapper.selectById(recordId);
        if (originalRecord == null) {
            log.error("记录不存在，ID: {}", recordId);
            return;
        }
        log.debug("原始记录内容: {}", originalRecord.getContent().substring(0, Math.min(100, originalRecord.getContent().length())));

        // 2. 跑管道（清洗 → 分类 + 拆分）
        try {
            List<Record> records = pipeline.execute(originalRecord);
            log.info("管道返回 {} 条记录", records.size());

            if (records.isEmpty()) {
                log.error("管道返回空结果，记录ID: {}", recordId);
                originalRecord.setStatus(RecordStatus.FAILED);
                originalRecord.setUpdatedAt(OffsetDateTime.now());
                recordMapper.updateById(originalRecord);
                return;
            }

            // 3. 处理拆分结果
            if (records.size() == 1) {
                // 无拆分：更新原记录
                Record record = records.get(0);
                record.setId(recordId);
                record.setStatus(RecordStatus.REVIEWING);
                record.setUpdatedAt(OffsetDateTime.now());
                recordMapper.updateById(record);
                saveTags(recordId, record.getKeywords());
                log.info("无拆分，记录ID: {}, 等待用户审核", recordId);
            } else {
                // 有拆分：第一条更新原记录，后续插入新记录
                log.info("开始拆分处理，共 {} 条记录", records.size());
                for (int i = 0; i < records.size(); i++) {
                    Record record = records.get(i);
                    record.setStatus(RecordStatus.REVIEWING);
                    record.setUpdatedAt(OffsetDateTime.now());

                    if (i == 0) {
                        // 第一条：更新原记录
                        record.setId(recordId);
                        recordMapper.updateById(record);
                        log.info("拆分 [{}]: 更新原记录 ID={}, title={}", i + 1, recordId, record.getTitle());
                    } else {
                        // 后续：插入新记录
                        record.setCreatedAt(OffsetDateTime.now());
                        recordMapper.insert(record);
                        log.info("拆分 [{}]: 新记录 ID={}, title={}", i + 1, record.getId(), record.getTitle());
                    }

                    // 保存关键词标签
                    saveTags(record.getId(), record.getKeywords());
                }
                log.info("拆分完成，原记录 {} 条，共 {} 条记录等待审核", recordId, records.size());
            }

            log.info("============ 异步管道结束 ============");

        } catch (Exception e) {
            originalRecord.setStatus(RecordStatus.FAILED);
            originalRecord.setUpdatedAt(OffsetDateTime.now());
            recordMapper.updateById(originalRecord);
            log.error("异步管道失败，记录ID: {}，原因: {}", recordId, e.getMessage(), e);
            log.info("============ 异步管道结束(失败) ============");
        }
    }

    /**
     * 保存关键词标签（先删后插）
     */
    private void saveTags(Long recordId, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return;
        }

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
