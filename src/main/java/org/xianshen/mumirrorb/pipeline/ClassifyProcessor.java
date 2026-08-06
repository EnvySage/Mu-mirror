package org.xianshen.mumirrorb.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DO.Record;

/**
 * 第 2 层：AI 分类（生成标题、摘要、标签）
 *
 * TODO: 接入 gRPC → Python AI 服务
 * 当前为桩实现，直接跳过，后续替换为真实 AI 调用
 *
 * 接入后实现思路：
 *   1. 注入 AiGrpcClient
 *   2. 调用 aiClient.classify(record.getContent())
 *   3. 将返回的 title/summary/contentType/mood/keywords 写入 record
 */
@Slf4j
@Component
@Order(2)
public class ClassifyProcessor implements RecordProcessor {

    // TODO: 接入时取消注释
    // private final AiGrpcClient aiClient;

    @Override
    public Record process(Record record) {
        // 桩实现：暂时跳过 AI 分类
        log.debug("ClassifyProcessor（桩）：跳过，记录ID: {}", record.getId());

        // TODO: 接入时替换为以下代码
        // ClassifyResponse result = aiClient.classify(record.getContent());
        //
        // if (result.getSkip()) {
        //     log.info("AI 判定跳过: {}", result.getSkipReason());
        //     return record;
        // }
        //
        // record.setTitle(result.getTitle());
        // record.setSummary(result.getSummary());
        // record.setContentType(ContentType.valueOf(result.getContentType().name()));
        // record.setMood(result.getMoodsList().stream()
        //         .map(Enum::name).map(String::toLowerCase).toList());
        // record.setKeywords(result.getKeywordsList());

        return record;
    }
}
