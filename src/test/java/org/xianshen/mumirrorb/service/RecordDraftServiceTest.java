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

    @Test
    void selectivePreparationUsesOneSplitCallAndKeepsOptOutSeparateFromAuthorization() {
        var properties = new org.xianshen.mumirrorb.config.RecordMemoryProperties();
        properties.setSelectiveIndexEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "recordMemoryProperties", properties);
        var item = org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyItem.newBuilder()
                .setContent("ordinary day").setEmbeddingEnabled(false).setEmbeddingReason("routine").build();
        when(ai.classify(eq(userId), anyString(), eq(true), anyString())).thenReturn(
                org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse.newBuilder().addItems(item)
                        .addItems(org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyItem.newBuilder().setContent("named place")).build());
        var input = new RecordDraftDTO(); input.setRawContent("ordinary day\nnamed place"); input.setMode("AI_ASSISTED");
        var draft = drafts.prepare(userId, input);
        assertEquals(1, draft.getIndexPolicyVersion());
        assertTrue(draft.getSegments().get(0).isMemoryEnabled());
        assertFalse(draft.getSegments().get(0).getEmbeddingEnabled());
        assertEquals("routine", draft.getSegments().get(0).getEmbeddingReason());
        assertTrue(draft.getSegments().get(1).getEmbeddingEnabled(), "missing optional recommendation defaults to indexing");
        verify(ai, times(1)).classify(eq(userId), anyString(), eq(true), anyString());
        verify(ai, never()).classifySingle(any(), anyString(), any(), any());
    }

    @Test
    void disabledSelectivePolicyUsesLegacySingleClassifierAndCreatesVersionZeroCandidate() {
        var properties = new org.xianshen.mumirrorb.config.RecordMemoryProperties();
        properties.setSelectiveIndexEnabled(false);
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "recordMemoryProperties", properties);
        var item = org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyItem.newBuilder()
                .setContent("legacy classified content").build();
        when(ai.classifySingle(eq(userId), eq("old draft"), isNull(), anyString())).thenReturn(
                org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse.newBuilder().addItems(item).build());
        var input = new RecordDraftDTO(); input.setRawContent("old draft"); input.setMode("AI_ASSISTED");

        var draft = drafts.prepare(userId, input);

        assertEquals(0, draft.getIndexPolicyVersion());
        assertEquals("old draft", draft.getSegments().get(0).getText());
        assertTrue(draft.getSegments().get(0).isMemoryEnabled());
        assertTrue(draft.getSegments().get(0).getEmbeddingEnabled());
        verify(ai).classifySingle(eq(userId), eq("old draft"), isNull(), anyString());
        verify(ai, never()).classify(eq(userId), anyString(), anyBoolean(), anyString());
    }

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
        input.setIndexPolicyVersion(1);

        RecordDraftDTO result = drafts.prepare(userId, input);

        verifyNoInteractions(ai);
        assertEquals(input.getRawContent(), result.getRawContent());
        assertEquals(input.getRawContent(), result.getFinalContent());
        assertEquals(1, result.getSegments().size());
        assertFalse(result.getSegments().get(0).isMemoryEnabled());
        assertEquals("READY", result.getPreparationStatus());
        assertEquals(0,result.getIndexPolicyVersion(),"client cannot choose the server policy");
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
