package org.xianshen.mumirrorb.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DO.Record;

/**
 * 第 1 层：文本清洗（纯代码，不调 AI）
 *
 * 职责：
 * - 去除首尾空白
 * - 合并多余换行
 * - 去除控制字符
 * - 空内容检测
 */
@Slf4j
@Component
@Order(1)
public class CleanProcessor implements RecordProcessor {

    @Override
    public Record process(Record record) {
        String content = record.getContent();

        // 1. 去除首尾空白
        content = content.trim();

        // 2. 合并多余换行（连续空行 → 单个换行）
        content = content.replaceAll("\\n{3,}", "\n\n");

        // 3. 去除不可见控制字符（保留换行 \n 和 tab \t）
        content = content.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 4. 空内容检测
        if (content.isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }

        record.setContent(content);
        return record;
    }
}
