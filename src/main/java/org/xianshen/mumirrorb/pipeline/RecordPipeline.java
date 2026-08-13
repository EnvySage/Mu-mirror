package org.xianshen.mumirrorb.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DO.Record;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录处理管道（编排器）
 *
 * <p>按 @Order 顺序依次执行所有 RecordProcessor 实现。</p>
 * <p>新增处理层：写一个类实现 RecordProcessor + @Order，自动注入，不用改这里。</p>
 *
 * <p><strong>拆分支持：</strong></p>
 * <p>ClassifyProcessor 可能将一条记录拆分成多条，因此管道的输入和输出都是 List。</p>
 */
@Slf4j
@Component
public class RecordPipeline {

    private final List<RecordProcessor> processors;

    /**
     * Spring 自动注入所有 RecordProcessor 实现，按 @Order 排序
     */
    public RecordPipeline(List<RecordProcessor> processors) {
        this.processors = processors;
        log.info("记录管道初始化，共 {} 个处理器", processors.size());
        for (RecordProcessor p : processors) {
            log.info("  → {}", p.getClass().getSimpleName());
        }
    }

    /**
     * 执行管道：依次调用每个处理器
     *
     * <p>输入单条记录，输出可能多条（ClassifyProcessor 拆分场景）。</p>
     *
     * @param record 原始记录（刚入库，status=processing）
     * @return 处理完成的记录列表（可能多条）
     * @throws RuntimeException 任何一层抛异常，整条管道中断
     */
    public List<Record> execute(Record record) {
        log.info("管道开始处理，记录ID: {}", record.getId());

        // 将单条记录包装成列表
        List<Record> records = new ArrayList<>();
        records.add(record);

        for (RecordProcessor processor : processors) {
            String name = processor.getClass().getSimpleName();
            log.info("  执行: {} (输入 {} 条)", name, records.size());
            try {
                records = processor.process(records);
            } catch (Exception e) {
                log.error("  失败: {} — {}", name, e.getMessage());
                throw e; // 向上抛，由调用方处理 status=failed
            }
        }

        log.info("管道处理完成，输出 {} 条记录", records.size());
        return records;
    }
}
