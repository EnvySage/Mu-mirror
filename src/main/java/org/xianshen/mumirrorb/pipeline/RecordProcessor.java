package org.xianshen.mumirrorb.pipeline;

import org.xianshen.mumirrorb.pojo.DO.Record;

/**
 * 记录处理器接口（数据管道的每一层实现它）
 *
 * 实现类加上 @Order 注解控制执行顺序：
 *   @Order(1) CleanProcessor    — 文本清洗
 *   @Order(2) ClassifyProcessor — AI 分类（标题/摘要/标签）
 *   @Order(3) EmbedProcessor    — 向量化
 */
public interface RecordProcessor {

    /**
     * 处理记录
     *
     * @param record 当前记录（上一层处理后的结果）
     * @return 处理后的记录（传给下一层）
     */
    Record process(Record record);
}
