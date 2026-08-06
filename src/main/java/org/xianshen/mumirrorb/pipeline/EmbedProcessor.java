package org.xianshen.mumirrorb.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DO.Record;

/**
 * 第 3 层：向量化（文本转向量，存 chunks 表）
 *
 * TODO: 接入 gRPC → Python AI 服务
 * 当前为桩实现，直接跳过，后续替换为真实 AI 调用
 *
 * 接入后实现思路：
 *   1. 注入 AiGrpcClient + ChunkMapper
 *   2. 调用 aiClient.embed(record.getContent())
 *   3. 将向量存入 chunks 表
 */
@Slf4j
@Component
@Order(3)
public class EmbedProcessor implements RecordProcessor {

    // TODO: 接入时取消注释
    // private final AiGrpcClient aiClient;
    // private final ChunkMapper chunkMapper;

    @Override
    public Record process(Record record) {
        // 桩实现：暂时跳过向量化
        log.debug("EmbedProcessor（桩）：跳过，记录ID: {}", record.getId());

        // TODO: 接入时替换为以下代码
        // EmbedResponse result = aiClient.embed(record.getContent());
        //
        // Chunk chunk = Chunk.builder()
        //         .recordId(record.getId())
        //         .content(record.getContent())
        //         .embedding(result.getVectorList())
        //         .build();
        // chunkMapper.insert(chunk);

        return record;
    }
}
