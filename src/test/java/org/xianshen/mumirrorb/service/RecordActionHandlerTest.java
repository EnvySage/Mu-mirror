package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.RecordActionHandler;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import org.xianshen.mumirrorb.service.impl.RecordDraftServiceImpl;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordActionHandlerTest {
    private final UUID userId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preparingRecordPersistsCandidateWithoutCreatingRecordOrChangingAuthorization() {
        RecordDraftService drafts = mock(RecordDraftService.class);
        var expected = new RecordDraftDTO();
        expected.setRawContent("原始内容");
        expected.setFinalContent("原始内容");
        expected.setMode("ONLY_SAVE");
        expected.setPreparationStatus("READY");
        var segment = new RecordDraftDTO.Segment();
        segment.setText("原始内容");
        expected.setSegments(java.util.List.of(segment));
        when(drafts.prepare(eq(userId), any())).thenReturn(expected);
        var records = mock(org.xianshen.mumirrorb.service.RecordService.class);
        var mapper = mock(RecordMapper.class);
        var handler = new RecordActionHandler(drafts, records, mapper, mock(ChunkMapper.class), json, mock(SettingsService.class));

        var preview = handler.validateAndPreview(userId, "record.create", json.valueToTree(java.util.Map.of(
                "rawContent", "原始内容", "mode", "ONLY_SAVE")));

        assertEquals("PENDING_USER_CONFIRMATION", preview.path("after").asText());
        assertFalse(preview.path("businessWrites").asBoolean());
        assertFalse(preview.path("authorizationGranted").asBoolean());
        assertEquals("原始内容", preview.path("recordDraft").path("rawContent").asText());
        verifyNoInteractions(records, mapper);
    }

    @Test
    void editedFinalContentBecomesTheSinglePersistedSegmentWhileRawSourceStaysSeparate() {
        RecordDraftService drafts = mock(RecordDraftService.class);
        var records = mock(org.xianshen.mumirrorb.service.RecordService.class);
        var handler = new RecordActionHandler(drafts, records, mock(RecordMapper.class), mock(ChunkMapper.class), json, mock(SettingsService.class));
        var draft = new RecordDraftDTO();
        draft.setRawContent("原始文字");
        draft.setFinalContent("审核后的文字");
        draft.setMode("AI_ASSISTED");
        draft.setPreparationStatus("READY");
        var segment = new RecordDraftDTO.Segment();
        segment.setText("原始文字");
        draft.setSegments(java.util.List.of(segment));
        var payload = json.createObjectNode().put("rawContent", "原始文字");
        payload.set("recordDraft", json.valueToTree(draft));

        var preview = handler.validateAndPreview(userId, "record.create", payload);

        assertEquals("原始文字", preview.path("recordDraft").path("rawContent").asText());
        assertEquals("审核后的文字", preview.path("recordDraft").path("segments").get(0).path("text").asText());
    }

    @Test
    void nestedDraftCannotBypassRawContentLimit() {
        var handler = new RecordActionHandler(mock(RecordDraftService.class), mock(org.xianshen.mumirrorb.service.RecordService.class),
                mock(RecordMapper.class), mock(ChunkMapper.class), json, mock(SettingsService.class));
        var draft = new RecordDraftDTO();
        draft.setRawContent("x".repeat(2001));
        draft.setFinalContent(draft.getRawContent());
        var payload = json.createObjectNode();
        payload.set("recordDraft", json.valueToTree(draft));

        var error = assertThrows(RuntimeException.class,
                () -> handler.validatePreparation("record.create", payload, json.createObjectNode()));
        assertTrue(error.getMessage().contains("2000"));
    }

    @Test
    void failedAuthorizedRecordWithoutChunksCanPrepareAReviewCard() {
        RecordDraftService drafts = mock(RecordDraftService.class);
        var expected = new RecordDraftDTO();
        expected.setRawContent("失败后重整"); expected.setFinalContent("失败后重整");
        expected.setMode("AI_ASSISTED"); expected.setPreparationStatus("READY");
        var segment = new RecordDraftDTO.Segment(); segment.setText("失败后重整");
        expected.setSegments(java.util.List.of(segment));
        when(drafts.prepare(eq(userId), any())).thenReturn(expected);
        var mapper = mock(RecordMapper.class);
        var chunkMapper = mock(ChunkMapper.class);
        var records = mock(org.xianshen.mumirrorb.service.RecordService.class);
        Record failed = Record.builder().id(12L).userId(userId).content("失败后重整").source("user")
                .status(RecordStatus.FAILED).aiProcessingAuthorized(true).processingVersion(2)
                .updatedAt(java.time.OffsetDateTime.now()).build();
        when(mapper.selectOne(any())).thenReturn(failed);
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        var handler = new RecordActionHandler(drafts, records, mapper, chunkMapper, json, mock(SettingsService.class));

        var preview = handler.validateAndPreview(userId, "record.organize", json.valueToTree(java.util.Map.of("sourceRecordId", 12L, "mode", "AI_ASSISTED")));

        assertEquals("FAILED", preview.path("before").asText());
        assertEquals("PENDING_USER_CONFIRMATION", preview.path("after").asText());
        verifyNoInteractions(records);
    }

    @Test
    void failedRecordWithPartialChunksIsRejectedForTask5Cleanup() {
        var mapper = mock(RecordMapper.class);
        var chunkMapper = mock(ChunkMapper.class);
        Record failed = Record.builder().id(12L).userId(userId).content("失败后重整").source("user")
                .status(RecordStatus.FAILED).aiProcessingAuthorized(true).processingVersion(2)
                .updatedAt(java.time.OffsetDateTime.now()).build();
        when(mapper.selectOne(any())).thenReturn(failed);
        when(chunkMapper.selectCount(any())).thenReturn(1L);
        var handler = new RecordActionHandler(mock(RecordDraftService.class), mock(org.xianshen.mumirrorb.service.RecordService.class),
                mapper, chunkMapper, json, mock(SettingsService.class));

        var error = assertThrows(RuntimeException.class, () -> handler.validateAndPreview(userId, "record.organize",
                json.valueToTree(java.util.Map.of("sourceRecordId", 12L, "mode", "AI_ASSISTED"))));

        assertTrue(error.getMessage().contains("部分片段"));
    }

    @Test
    void degradedOnlySaveRecordCanBeResubmittedForAiPreparation() {
        RecordDraftService drafts = mock(RecordDraftService.class);
        var draft = new RecordDraftDTO();
        draft.setRawContent("可重新整理"); draft.setFinalContent("可重新整理");
        draft.setMode("AI_ASSISTED"); draft.setPreparationStatus("READY");
        var segment = new RecordDraftDTO.Segment(); segment.setText("可重新整理");
        draft.setSegments(java.util.List.of(segment));
        when(drafts.prepare(eq(userId), any())).thenReturn(draft);
        var mapper = mock(RecordMapper.class);
        Record degraded = Record.builder().id(12L).userId(userId).content("可重新整理").source("user")
                .status(RecordStatus.RECORD_ONLY).memoryState(RecordMemoryState.NONE)
                .aiProcessingAuthorized(false).processingVersion(4).updatedAt(java.time.OffsetDateTime.now()).build();
        when(mapper.selectOne(any())).thenReturn(degraded);
        var handler = new RecordActionHandler(drafts, mock(RecordService.class), mapper, mock(ChunkMapper.class), json,
                mock(SettingsService.class));

        var preview = handler.validateAndPreview(userId, "record.organize",
                json.valueToTree(java.util.Map.of("sourceRecordId", 12L, "mode", "AI_ASSISTED")));

        assertEquals("PENDING_USER_CONFIRMATION", preview.path("after").asText());
        assertEquals(12L, preview.path("recordDraft").path("sourceRecordId").asLong());
        verify(drafts).prepare(eq(userId), any());
    }
}
