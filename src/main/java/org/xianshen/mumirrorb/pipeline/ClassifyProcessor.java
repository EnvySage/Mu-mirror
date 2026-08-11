package org.xianshen.mumirrorb.pipeline;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Record;

import java.util.List;

/**
 * 第 2 层：AI 分类（生成标题、摘要、标签）
 *
 * 调用 Python gRPC AI 服务，将返回的 title/summary/contentType/mood/keywords 写入 record。
 * 如果 AI 判定内容无意义（skip=true），抛出异常，管道中断，record 标记为 FAILED。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class ClassifyProcessor implements RecordProcessor {

    private final AiGrpcClient aiGrpcClient;

    @Override
    public Record process(Record record) {
        String content = record.getContent();
        log.info("ClassifyProcessor 开始处理，记录ID: {}, 内容长度: {}", record.getId(), content.length());

        try {
            // 1. 调用 gRPC 分类服务（携带用户的模型配置）
            RecordProcessorProto.ClassifyResponse response = aiGrpcClient.classify(record.getUserId(), content);

            // 2. 检查是否跳过（无意义内容）
            if (response.getSkip()) {
                log.info("AI 判定跳过，原因: {}", response.getSkipReason());
                throw new IllegalArgumentException("AI 判定内容无意义: " + response.getSkipReason());
            }

            // 3. 写入标题和摘要
            record.setTitle(response.getTitle());
            record.setSummary(response.getSummary());

            // 4. 写入内容类型（proto 枚举名 → Java 枚举）
            if (response.getContentType() != CommonProto.ContentType.CONTENT_UNKNOWN) {
                try {
                    ContentType contentType = ContentType.valueOf(response.getContentType().name());
                    record.setContentType(contentType);
                } catch (IllegalArgumentException e) {
                    log.warn("未知的内容类型: {}, 跳过", response.getContentType());
                }
            }

            // 5. 写入情绪标签（proto 枚举 → 小写字符串列表）
            List<String> moods = response.getMoodsList().stream()
                    .filter(m -> m != CommonProto.MoodType.MOOD_UNKNOWN)
                    .map(Enum::name)
                    .map(String::toLowerCase)
                    .toList();
            record.setMood(moods);

            // 6. 写入关键词（临时字段，由 EventListener 存入 tags 表）
            List<String> keywords = response.getKeywordsList();
            record.setKeywords(keywords);

            log.info("ClassifyProcessor 完成: title={}, contentType={}, moods={}, keywords={}",
                    record.getTitle(), record.getContentType(), moods, keywords);

        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 调用失败: {}", e.getStatus(), e);
            throw new RuntimeException("AI 分类服务调用失败: " + e.getStatus().getDescription(), e);
        }

        return record;
    }
}
