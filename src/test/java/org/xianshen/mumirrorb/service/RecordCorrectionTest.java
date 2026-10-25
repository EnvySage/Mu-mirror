package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;
import org.xianshen.mumirrorb.service.ChunkEmbeddingJobService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecordCorrectionTest {
    @Mock private RecordMapper records;
    @Mock private ChunkMapper chunks;
    @Mock private SnapshotLineageService lineage;
    @Mock private ApplicationEventPublisher events;
    @Mock private AiGrpcClient ai;
    @Mock private ApplicationContext applicationContext;
    @Mock private ChunkEmbeddingJobService embeddingJobs;
    @InjectMocks private RecordServiceImpl service;

    @Test
    void correctionCreatesLinkedRecordWithoutChangingReadonlySource() {
        UUID userId = UUID.randomUUID();
        Record source = Record.builder().id(9L).userId(userId).content("原始来源")
                .memoryState(RecordMemoryState.READY).build();
        when(records.selectOne(any())).thenReturn(source);
        when(chunks.selectList(any())).thenReturn(List.of());
        RecordDTO dto = new RecordDTO();
        dto.setContent("补充说明");
        dto.setCorrectionOfRecordId(9L);

        service.create(dto, userId);

        ArgumentCaptor<Record> inserted = ArgumentCaptor.forClass(Record.class);
        verify(records).insert(inserted.capture());
        assertEquals("补充说明", inserted.getValue().getContent());
        assertEquals(9L, inserted.getValue().getCorrectionOfRecordId());
        assertEquals("原始来源", source.getContent());
    }

    @Test
    void correctionRejectsUnreadyOrForeignSource() {
        UUID userId = UUID.randomUUID();
        when(records.selectOne(any())).thenReturn(Record.builder().id(9L).userId(userId)
                .memoryState(RecordMemoryState.NONE).build());
        RecordDTO dto = new RecordDTO();
        dto.setContent("补充说明");
        dto.setCorrectionOfRecordId(9L);

        assertThrows(RuntimeException.class, () -> service.create(dto, userId));
        verify(records, never()).insert(any(Record.class));
    }
}
