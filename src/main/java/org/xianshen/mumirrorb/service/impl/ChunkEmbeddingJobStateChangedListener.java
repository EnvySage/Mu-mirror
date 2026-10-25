package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.RecordMemoryLifecycleService;

@Component
@RequiredArgsConstructor
class ChunkEmbeddingJobStateChangedListener {
    private final ChunkMapper chunks;
    private final RecordMapper records;
    private final RecordMemoryLifecycleService lifecycle;

    @TransactionalEventListener
    public void process(ChunkEmbeddingJobStateChangedEvent event) {
        Chunk chunk = chunks.selectById(event.chunkId());
        if (chunk == null || !event.userId().equals(chunk.getUserId())) return;
        Record record = records.selectById(chunk.getRecordId());
        if (record == null || record.getProcessingVersion() == null) return;
        if ("READY".equals(event.state()))
            lifecycle.onInitialIndexComplete(event.userId(), record.getId(), record.getProcessingVersion());
        else if ("FAILED".equals(event.state()))
            lifecycle.onInitialIndexExhausted(event.userId(), record.getId(), record.getProcessingVersion(), event.reason());
    }
}
