package org.xianshen.mumirrorb.pipeline;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Record;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
 * <p>如果 AI 判定内容无意义（skip=true），抛出异常，管道中断，record 标记为 FAILED。</p>
 *
 * <p><strong>拆分示例：</strong></p>
 * <pre>
 * 输入: "今天上午学了 Spring Boot，下午去健身"
 * 输出: [
 *   ClassifyItem { title: "学Spring Boot", type: "learning", ... },
 *   ClassifyItem { title: "下午健身", type: "health", ... }
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
            List<Record> classified = classifyOne(record);
            result.addAll(classified);
        }

        return result;
    }

    /**
     * 分类单条记录（可能拆分成多条）
     */
    private List<Record> classifyOne(Record record) {
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

            // 3. 遍历拆分结果，每条 ClassifyItem 创建一个 Record
            List<RecordProcessorProto.ClassifyItem> items = response.getItemsList();
            log.info("ClassifyProcessor 拆分结果: {} 条", items.size());

            if (items.isEmpty()) {
                throw new IllegalArgumentException("AI 返回的分类结果为空");
            }

            List<Record> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                RecordProcessorProto.ClassifyItem item = items.get(i);

                // 复用原记录的基础信息，填充 AI 生成的字段
                Record newRecord = Record.builder()
                        .userId(record.getUserId())
                        .content(record.getContent())  // 保留原始完整内容
                        .title(item.getTitle())
                        .summary(item.getSummary())
                        .contentType(convertContentType(item.getContentType()))
                        .mood(convertMoods(item.getMoodsList()))
                        .keywords(item.getKeywordsList())
                        .status(RecordStatus.PROCESSING)  // 后续 EventListener 会改为 REVIEWING
                        .userReviewed(false)
                        .createdAt(record.getCreatedAt())
                        .updatedAt(OffsetDateTime.now())
                        .build();

                // 第一条复用原记录 ID（更新原记录），后续插入新记录
                if (i == 0) {
                    newRecord.setId(record.getId());
                }

                result.add(newRecord);

                log.info("ClassifyProcessor 完成 [{}]: title={}, contentType={}, moods={}, keywords={}",
                        i + 1, newRecord.getTitle(), newRecord.getContentType(),
                        newRecord.getMood(), newRecord.getKeywords());
            }

            return result;

        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 调用失败: {}", e.getStatus(), e);
            throw new RuntimeException("AI 分类服务调用失败: " + e.getStatus().getDescription(), e);
        }
    }

    /**
     * Proto ContentType → Java ContentType
     */
    private ContentType convertContentType(CommonProto.ContentType protoType) {
        if (protoType == CommonProto.ContentType.CONTENT_UNKNOWN) {
            return null;
        }
        try {
            return ContentType.valueOf(protoType.name());
        } catch (IllegalArgumentException e) {
            log.warn("未知的内容类型: {}, 返回 null", protoType);
            return null;
        }
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
