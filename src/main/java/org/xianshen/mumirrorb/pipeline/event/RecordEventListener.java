package org.xianshen.mumirrorb.pipeline.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pipeline.RecordPipeline;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.service.RecordService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录事件监听器
 *
 * <p>监听 RecordCreatedEvent，在新线程中执行管道处理。</p>
 *
 * <p><strong>新设计：</strong></p>
 * <ul>
 *   <li>管道返回单条 Record（segment 数组 + chunkMetadataList）</li>
 *   <li>保存 Record 后，根据 chunkMetadataList 创建 Chunk 记录（无 embedding）</li>
 *   <li>用户审核确认后，再对 Chunk 做 embedding</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordEventListener {

    private final RecordPipeline pipeline;
    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final RecordService recordService;
    private final SettingsMapper settingsMapper;

    /**
     * 记录创建后，异步执行管道处理
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecordCreated(RecordCreatedEvent event) {
        Long recordId = event.getRecordId();
        log.info("============ 异步管道开始 ============");
        log.info("记录ID: {}", recordId);

        // 1. 查出记录
        Record record = recordMapper.selectById(recordId);
        if (record == null) {
            log.error("记录不存在，ID: {}", recordId);
            return;
        }

        // 2. 跑管道（清洗 → 分类）
        try {
            List<Record> results = pipeline.execute(record);

            if (results.isEmpty()) {
                log.error("管道返回空结果，记录ID: {}", recordId);
                record.setStatus(RecordStatus.FAILED);
                record.setFailReason("AI 返回空结果");
                record.setUpdatedAt(OffsetDateTime.now());
                recordMapper.updateById(record);
                return;
            }

            // 3. 取出处理后的 Record（始终只有一条）
            Record processed = results.get(0);

            // 4. 保存 Record（segment 数组）
            processed.setId(recordId);
            processed.setStatus(RecordStatus.REVIEWING);
            processed.setUpdatedAt(OffsetDateTime.now());
            recordMapper.updateById(processed);
            log.info("Record 已更新，ID: {}, segment 数量: {}", recordId,
                    processed.getSegment() != null ? processed.getSegment().size() : 0);

            // 5. 创建 Chunk（每个 segment 一个 Chunk，无 embedding）
            List<Map<String, Object>> chunkMetadataList = processed.getChunkMetadataList();
            List<String> segments = processed.getSegment();

            if (chunkMetadataList != null && segments != null) {
                for (int i = 0; i < segments.size(); i++) {
                    Map<String, Object> metadata = chunkMetadataList.get(i);

                    Chunk chunk = Chunk.builder()
                            .userId(record.getUserId())
                            .recordId(recordId)
                            .content(record.getContent())
                            .segment(segments.get(i))
                            .metadata(metadata)
                            // AI 刚分类过：classified_segment = 当时文本；非用户编辑（设计文档 5.3）
                            .classifiedSegment(segments.get(i))
                            .userEdited(false)
                            .createdAt(OffsetDateTime.now())
                            .build();
                    chunkMapper.insert(chunk);
                    log.info("Chunk 已创建，记录ID: {}, 片段 [{}]: {}", recordId, i + 1, segments.get(i));
                }
            }

            // 6. auto 审核模式接线（设计文档 5.5）：无审核窗口，直接执行 confirm 流程
            String reviewMode = getReviewMode(record.getUserId());
            if ("auto".equals(reviewMode)) {
                log.info("review_mode=auto，记录ID: {} 直接确认", recordId);
                recordService.confirmReview(recordId, record.getUserId());
            }

            log.info("============ 异步管道结束 ============");

        } catch (Exception e) {
            record.setStatus(RecordStatus.FAILED);
            // fail_reason 透出（前端 failed 卡片显示具体原因）；截断防超长堆栈
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record.setFailReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            record.setUpdatedAt(OffsetDateTime.now());
            recordMapper.updateById(record);
            log.error("异步管道失败，记录ID: {}，原因: {}", recordId, e.getMessage(), e);
            log.info("============ 异步管道结束(失败) ============");
        }
    }

    /**
     * 读取用户审核模式（manual / auto；缺省 manual）
     */
    private String getReviewMode(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        return settings != null && settings.getReviewMode() != null
                ? settings.getReviewMode()
                : "manual";
    }
}
