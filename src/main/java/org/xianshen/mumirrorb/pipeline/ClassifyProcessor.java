package org.xianshen.mumirrorb.pipeline;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Record;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 2 层：AI 分类（生成标题、摘要、标签 + 拆分）
 *
 * <p>调用 Python gRPC AI 服务，一次 LLM 调用同时完成拆分 + 分类：</p>
 * <ul>
 *   <li>AI 判断用户输入是否包含多件事</li>
 *   <li>如果是，拆分成多条 ClassifyItem 返回</li>
 *   <li>每条 ClassifyItem 独立生成标题/摘要/标签</li>
 * </ul>
 *
 * <p><strong>新设计：</strong></p>
 * <p>不再创建多条 Record。一条 Record 对应一条用户输入，segment 存拆分片段数组，
 * chunkMetadataList 存每个片段的 AI 元数据，由 EventListener 创建 Chunk。</p>
 *
 * <p><strong>拆分示例：</strong></p>
 * <pre>
 * 输入: "今天上午学了 Spring Boot，下午去健身"
 * Record.segment = ["今天上午学了 Spring Boot", "下午去健身"]
 * Record.chunkMetadataList = [
 *   {title: "学Spring Boot", contentType: "learning", ...},
 *   {title: "下午健身", contentType: "health", ...}
 * ]
 * </pre>
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class ClassifyProcessor implements RecordProcessor {

    private final AiGrpcClient aiGrpcClient;

    @Override
    public List<Record> process(List<Record> records) {
        List<Record> result = new ArrayList<>();

        for (Record record : records) {
            Record classified = classifyOne(record);
            result.add(classified);
        }

        return result;
    }

    /**
     * 分类单条记录（生成 segment 数组 + chunk 元数据）
     */
    private Record classifyOne(Record record) {
        String content = record.getContent();
        log.info("ClassifyProcessor 开始处理，记录ID: {}, 内容长度: {}", record.getId(), content.length());

        try {
            // 1. 调用 gRPC 分类服务
            RecordProcessorProto.ClassifyResponse response = aiGrpcClient.classify(record.getUserId(), content);

            // 2. 检查是否跳过
            if (response.getSkip()) {
                log.info("AI 判定跳过，原因: {}", response.getSkipReason());
                throw new IllegalArgumentException("AI 判定内容无意义: " + response.getSkipReason());
            }

            // 3. 遍历拆分结果，构建 segment 数组和 chunkMetadataList
            List<RecordProcessorProto.ClassifyItem> items = response.getItemsList();
            log.info("ClassifyProcessor 拆分结果: {} 条", items.size());

            if (items.isEmpty()) {
                throw new IllegalArgumentException("AI 返回的分类结果为空");
            }

            List<String> segments = new ArrayList<>();
            List<Map<String, Object>> chunkMetadataList = new ArrayList<>();

            for (int i = 0; i < items.size(); i++) {
                RecordProcessorProto.ClassifyItem item = items.get(i);

                // segment 片段（原文）
                segments.add(item.getContent());

                // chunk 元数据
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("title", item.getTitle());
                metadata.put("summary", item.getSummary());
                metadata.put("contentType", convertContentType(item.getContentType()));
                metadata.put("mood", convertMoods(item.getMoodsList()));
                metadata.put("keywords", item.getKeywordsList());
                chunkMetadataList.add(metadata);

                log.info("ClassifyProcessor 片段 [{}]: title={}, contentType={}, segment={}",
                        i + 1, item.getTitle(), convertContentType(item.getContentType()),
                        item.getContent());
            }

            // 4. 更新原 Record（不创建新 Record）
            record.setSegment(segments);
            record.setChunkMetadataList(chunkMetadataList);
            record.setStatus(RecordStatus.PROCESSING);
            record.setUpdatedAt(OffsetDateTime.now());

            return record;

        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 调用失败: {}", e.getStatus(), e);
            throw new RuntimeException("AI 分类服务调用失败: " + e.getStatus().getDescription(), e);
        }
    }

    /**
     * Proto ContentType → 字符串
     */
    private String convertContentType(CommonProto.ContentType protoType) {
        if (protoType == CommonProto.ContentType.CONTENT_UNKNOWN) {
            return null;
        }
        return protoType.name().toLowerCase();
    }

    /**
     * Proto MoodType 列表 → 小写字符串列表
     */
    private List<String> convertMoods(List<CommonProto.MoodType> moodsList) {
        return moodsList.stream()
                .filter(m -> m != CommonProto.MoodType.MOOD_UNKNOWN)
                .map(Enum::name)
                .map(String::toLowerCase)
                .toList();
    }
}
