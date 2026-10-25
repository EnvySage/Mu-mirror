package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordAuthorizationGateTest {
    @Test
    void legacyCreateCannotStartClassificationWithoutAProposal() {
        var mapper = mock(RecordMapper.class);
        var events = mock(ApplicationEventPublisher.class);
        RecordService service = new RecordServiceImpl(mapper, mock(ChunkMapper.class), mock(SnapshotLineageService.class),
                events, mock(AiGrpcClient.class), mock(ApplicationContext.class), mock(ChunkEmbeddingJobService.class));
        RecordDTO dto = new RecordDTO();
        dto.setContent("先创建卡片");
        dto.setRequestAiProcessing(true);

        var error = assertThrows(BusinessException.class, () -> service.create(dto, UUID.randomUUID()));

        assertTrue(error.getMessage().contains("操作卡"));
        verifyNoInteractions(mapper, events);
    }

    @Test
    void legacyOrganizeServiceCannotGrantAuthorizationOrLaunchClassification() {
        var mapper = mock(RecordMapper.class);
        var events = mock(ApplicationEventPublisher.class);
        RecordService service = new RecordServiceImpl(mapper, mock(ChunkMapper.class), mock(SnapshotLineageService.class),
                events, mock(AiGrpcClient.class), mock(ApplicationContext.class), mock(ChunkEmbeddingJobService.class));

        assertThrows(BusinessException.class, () -> service.organize(19L, UUID.randomUUID()));

        verifyNoInteractions(mapper, events);
    }

    @Test
    void legacyRetryCannotLaunchClassificationWithoutAReviewCard() {
        var mapper = mock(RecordMapper.class);
        var events = mock(ApplicationEventPublisher.class);
        RecordService service = new RecordServiceImpl(mapper, mock(ChunkMapper.class), mock(SnapshotLineageService.class),
                events, mock(AiGrpcClient.class), mock(ApplicationContext.class), mock(ChunkEmbeddingJobService.class));

        var error = assertThrows(BusinessException.class, () -> service.retry(19L, UUID.randomUUID()));

        assertTrue(error.getMessage().contains("操作卡"));
        verifyNoInteractions(mapper, events);
    }
}
