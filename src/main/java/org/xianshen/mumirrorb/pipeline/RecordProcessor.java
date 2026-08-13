package org.xianshen.mumirrorb.pipeline;

import org.xianshen.mumirrorb.pojo.DO.Record;

import java.util.List;

/**
 * 记录处理器接口（数据管道的每一层实现它）
 *
 * 实现类加上 @Order 注解控制执行顺序：
 *   @Order(1) CleanProcessor    — 文本清洗
 *   @Order(2) ClassifyProcessor — AI 分类（标题/摘要/标签 + 拆分）
 *
 * <p>注意：ClassifyProcessor 支持拆分，一条记录可能变成多条</p>
 */
public interface RecordProcessor {

    /**
     * 处理记录列表
     *
     * <p>输入和输出都是列表，支持一条变多条（拆分场景）。</p>
     *
     * @param records 当前记录列表（上一层处理后的结果）
     * @return 处理后的记录列表（传给下一层）
     */
    List<Record> process(List<Record> records);
}
