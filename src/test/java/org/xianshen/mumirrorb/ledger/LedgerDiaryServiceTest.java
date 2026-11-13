package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;
import org.xianshen.mumirrorb.service.RecordService;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerDiaryServiceTest {
    @Test
    void confirmedTemplateIsReadableWithoutVectorOrModelWork() {
        var db = mock(JdbcTemplate.class);
        var records = mock(RecordMapper.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Record.class).setId(91L);
            return 1;
        }).when(records).insert(any(Record.class));
        var chunks = mock(ChunkMapper.class);
        var embedding = mock(ChunkEmbeddingJobService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ActionProposalService> proposals = mock(ObjectProvider.class);
        var service = new LedgerDiaryService(db, records, chunks, mock(RecordService.class),
                embedding, mock(SnapshotLineageService.class), proposals, new ObjectMapper());
        var owner = UUID.randomUUID();
        var amount = new BigDecimal("39.80");
        var flow = new LedgerModels.Flow(UUID.randomUUID(), "EXPENSE", amount, "food", "餐饮",
                LedgerService.today(), "朋友聚餐", null, null);
        var entry = new LedgerModels.Entry(UUID.randomUUID(), "晚餐", "聊到很晚", LedgerService.today(),
                amount, BigDecimal.ZERO, BigDecimal.ZERO, amount, List.of(flow), List.of(), null);

        service.create(owner, entry, "TEMPLATE", "");

        var record = ArgumentCaptor.forClass(Record.class);
        verify(records).insert(record.capture());
        assertEquals(1, record.getValue().getIndexPolicyVersion());
        assertEquals(RecordMemoryState.READY, record.getValue().getMemoryState());
        assertTrue(record.getValue().getUserReviewed());
        assertTrue(record.getValue().getAiProcessingAuthorized());
        assertTrue(record.getValue().getContent().contains("39.80"));
        assertTrue(record.getValue().getContent().contains("聊到很晚"));
        var chunk = ArgumentCaptor.forClass(Chunk.class);
        verify(chunks).insert(chunk.capture());
        assertTrue(chunk.getValue().getAiMemoryApproved());
        assertFalse(chunk.getValue().getEmbeddingEnabled());
        assertEquals("not_requested", chunk.getValue().getEmbeddingStatus());
        assertEquals("note", chunk.getValue().getMetadata().get("contentType"));
        verifyNoInteractions(embedding, proposals);
    }
}
