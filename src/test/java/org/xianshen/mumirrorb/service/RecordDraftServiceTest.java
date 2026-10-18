package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;
import org.xianshen.mumirrorb.service.impl.RecordDraftServiceImpl;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordDraftServiceTest {
    private final UUID userId = UUID.randomUUID();
    private AiGrpcClient ai;
    private SettingsService settings;
    private RecordDraftService drafts;

    @BeforeEach
    void setUp() {
        ai = mock(AiGrpcClient.class);
        settings = mock(SettingsService.class);
        when(settings.getSettings(userId)).thenReturn(SettingsVO.builder().defaultRecordMode("ONLY_SAVE").build());
        drafts = new RecordDraftServiceImpl(ai, settings);
    }

    @Test
    void onlySaveNeverCallsAiAndKeepsOneUnmodifiedSegment() {
        RecordDraftDTO input = new RecordDraftDTO();
        input.setRawContent("原文保持不变");
        input.setMode("ONLY_SAVE");

        RecordDraftDTO result = drafts.prepare(userId, input);

        verifyNoInteractions(ai);
        assertEquals(input.getRawContent(), result.getRawContent());
        assertEquals(input.getRawContent(), result.getFinalContent());
        assertEquals(1, result.getSegments().size());
        assertFalse(result.getSegments().get(0).isMemoryEnabled());
        assertEquals("READY", result.getPreparationStatus());
    }

    @Test
    void privateInputBypassesAiEvenWhenRequestedModeIsAiAssisted() {
        RecordDraftDTO input = new RecordDraftDTO();
        input.setRawContent("绝不提交给模型");
        input.setMode("AI_ASSISTED");
        input.setPrivateInput(true);

        RecordDraftDTO result = drafts.prepare(userId, input);

        verifyNoInteractions(ai);
        assertEquals("ONLY_SAVE", result.getMode());
        assertEquals(input.getRawContent(), result.getSegments().get(0).getText());
    }

    @Test
    void classificationFailureReturnsPendingSaveOnlyCandidateWithoutLosingInput() {
        RecordDraftDTO input = new RecordDraftDTO();
        input.setRawContent("今天完成了项目复盘");
        input.setMode("AI_ASSISTED");
        when(ai.classifySingle(eq(userId), eq(input.getRawContent()), isNull(), anyString()))
                .thenThrow(new IllegalStateException("classifier unavailable"));

        RecordDraftDTO result = drafts.prepare(userId, input);

        assertEquals("SAVE_ONLY", result.getPreparationStatus());
        assertEquals(input.getRawContent(), result.getRawContent());
        assertEquals(input.getRawContent(), result.getSegments().get(0).getText());
        assertFalse(result.getSegments().get(0).isMemoryEnabled());
    }
}
