package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xianshen.mumirrorb.action.RecordActionHandler;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.RecordMemoryState;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.action.ActionExecution;
import org.xianshen.mumirrorb.action.RecordActionHandler;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import org.xianshen.mumirrorb.service.impl.RecordDraftServiceImpl;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;

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
        segment.setContentType("plan");
        draft.setSegments(java.util.List.of(segment));
        var payload = json.createObjectNode().put("rawContent", "原始文字");
        payload.set("recordDraft", json.valueToTree(draft));

        var preview = handler.validateAndPreview(userId, "record.create", payload);

        assertEquals("原始文字", preview.path("recordDraft").path("rawContent").asText());
        assertEquals("审核后的文字", preview.path("recordDraft").path("segments").get(0).path("text").asText());
        assertEquals("todo", preview.path("recordDraft").path("segments").get(0).path("contentType").asText());
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

    @Test
    void correctionPreviewTracksSourceVersionAndConfirmRejectsChangedSource() {
        Record source = correctionSource();
        Record changed = correctionSource();
        changed.setProcessingVersion(8);
        ActionProposalMapper actions = mock(ActionProposalMapper.class);
        RecordMapper records = mock(RecordMapper.class);
        when(records.selectOne(any())).thenReturn(source);
        when(records.selectOwnedForUpdate(userId, source.getId())).thenReturn(changed);
        when(actions.insertIfAbsent(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(actions.findByPrepareKey(eq(userId), anyString())).thenAnswer(call -> stored.get());
        when(actions.lockOwned(eq(userId), any())).thenAnswer(call -> stored.get());
        when(actions.save(any(), anyInt())).thenReturn(1);
        RecordDraftService drafts = saveOnlyDraftService();
        RecordService recordService = mock(RecordService.class);
        var handler = new RecordActionHandler(drafts, recordService, records, mock(ChunkMapper.class), json,
                mock(SettingsService.class));
        var actionService = new ActionProposalServiceImpl(actions, java.util.List.of(handler));
        var card = actionService.prepare(userId, correctionRequest("correction-stale"));

        assertTrue(card.getTargetVersions().has(String.valueOf(source.getId())));
        BusinessException stale = assertThrows(BusinessException.class, () -> actionService.confirm(userId, card.getId(),
                new ActionConfirmDTO(card.getVersion(), "confirm-stale")));
        assertEquals(409, stale.getCode());
        verify(recordService, never()).create(any(), eq(userId));
    }

    @Test
    void correctionConfirmRejectsSourceDeletedAfterPreparation() {
        Record source = correctionSource();
        ActionProposalMapper actions = mock(ActionProposalMapper.class);
        RecordMapper records = mock(RecordMapper.class);
        when(records.selectOne(any())).thenReturn(source);
        when(records.selectOwnedForUpdate(userId, source.getId())).thenReturn(null);
        when(actions.insertIfAbsent(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(actions.findByPrepareKey(eq(userId), anyString())).thenAnswer(call -> stored.get());
        when(actions.lockOwned(eq(userId), any())).thenAnswer(call -> stored.get());
        when(actions.save(any(), anyInt())).thenReturn(1);
        RecordService recordService = mock(RecordService.class);
        var handler = new RecordActionHandler(saveOnlyDraftService(), recordService, records, mock(ChunkMapper.class), json,
                mock(SettingsService.class));
        var actionService = new ActionProposalServiceImpl(actions, java.util.List.of(handler));
        var card = actionService.prepare(userId, correctionRequest("correction-deleted"));

        BusinessException deleted = assertThrows(BusinessException.class, () -> actionService.confirm(userId, card.getId(),
                new ActionConfirmDTO(card.getVersion(), "confirm-deleted")));
        assertEquals(4041, deleted.getCode());
        verify(recordService, never()).create(any(), eq(userId));
    }

    @Test
    void deletePreparationDoesNotRequireRecordDraft() {
        RecordMapper mapper = mock(RecordMapper.class);
        Record target = Record.builder().id(31L).userId(userId).content("待删除记录").source("user")
                .status(RecordStatus.RECORD_ONLY).updatedAt(java.time.OffsetDateTime.now()).build();
        when(mapper.selectOne(any())).thenReturn(target);
        var handler = new RecordActionHandler(mock(RecordDraftService.class), mock(RecordService.class), mapper,
                mock(ChunkMapper.class), json, mock(SettingsService.class));
        var payload = json.valueToTree(java.util.Map.of("recordId", 31L));

        var preview = handler.validateAndPreview(userId, "record.delete", payload);
        handler.validatePreparation("record.delete", payload, preview);

        assertEquals(31L, preview.path("recordId").asLong());
        assertTrue(preview.path("targetVersions").has("31"));
    }

    private final java.util.concurrent.atomic.AtomicReference<ActionProposal> stored = new java.util.concurrent.atomic.AtomicReference<>();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void growthDefinitionCheckAppliesOnlyToAiScoringCandidates(boolean aiAssisted) {
        var proposals = mock(ActionProposalMapper.class);
        when(proposals.insertIfAbsent(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(proposals.lockOwned(eq(userId), any())).thenAnswer(call -> stored.get());
        when(proposals.save(any(), anyInt())).thenReturn(1);
        var records = mock(RecordService.class);
        when(records.create(any(), eq(userId))).thenReturn(org.xianshen.mumirrorb.pojo.VO.RecordVO.builder().id(42L).build());
        var drafts = saveOnlyDraftService();
        var oldDraft = drafts.prepare(userId, new RecordDraftDTO());
        oldDraft.setAxesVersion(0);
        oldDraft.setGrowthSuggestions(java.util.List.of(java.util.Map.of("dimensionId", "knowledge", "points", 0, "reason", "")));
        var handler = new RecordActionHandler(drafts, records, mock(RecordMapper.class), mock(ChunkMapper.class), json, mock(SettingsService.class));
        var growth = mock(GrowthService.class);
        when(growth.axesVersion(userId)).thenReturn(5L);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "growthService", growth);
        var service = new ActionProposalServiceImpl(proposals, java.util.List.of(handler));
        var card = service.prepare(userId, new ActionPrepareDTO("record.create", json.valueToTree(java.util.Map.of(
                "rawContent", "更正内容", "mode", "ONLY_SAVE")), "save-only-zero-scores", null, null));

        if (aiAssisted) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) stored.get().getPayload().path("recordDraft")).put("mode", "AI_ASSISTED");
            assertThrows(BusinessException.class, () -> service.confirm(userId, card.getId(),
                    new ActionConfirmDTO(card.getVersion(), "confirm-stale-ai")));
            verifyNoInteractions(records);
            return;
        }

        var confirmed = service.confirm(userId, card.getId(), new ActionConfirmDTO(card.getVersion(), "confirm-save-only"));

        assertEquals("SUCCEEDED", confirmed.getState());
        verify(records).create(any(), eq(userId));
        verifyNoInteractions(growth);
    }

    private Record correctionSource() {
        return Record.builder().id(22L).userId(userId).source("user").status(RecordStatus.DONE)
                .memoryState(RecordMemoryState.READY).processingVersion(7).updatedAt(java.time.OffsetDateTime.now()).build();
    }

    private RecordDraftService saveOnlyDraftService() {
        RecordDraftService drafts = mock(RecordDraftService.class);
        var draft = new RecordDraftDTO();
        draft.setRawContent("更正内容"); draft.setFinalContent("更正内容");
        draft.setMode("ONLY_SAVE"); draft.setPreparationStatus("READY");
        var segment = new RecordDraftDTO.Segment(); segment.setText("更正内容");
        draft.setSegments(java.util.List.of(segment));
        when(drafts.prepare(eq(userId), any())).thenReturn(draft);
        return drafts;
    }

    private ActionPrepareDTO correctionRequest(String key) {
        return new ActionPrepareDTO("record.create", json.valueToTree(java.util.Map.of(
                "rawContent", "更正内容", "mode", "ONLY_SAVE", "correctionOfRecordId", 22L)), key, null, null);
    }
}
