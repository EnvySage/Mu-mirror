package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.mapper.AgentCommandReceiptMapper;
import org.xianshen.mumirrorb.mapper.ToolCallMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentCommandReceipt;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandServiceTest {

    private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    private final AgentCommandReceiptMapper receiptMapper = mock(AgentCommandReceiptMapper.class);
    private final RecordService recordService = mock(RecordService.class);
    private final ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentCommandService service = new AgentCommandService(
            runRepository, receiptMapper, recordService, toolCallMapper, objectMapper);

    private UUID userId;
    private UUID sessionId;
    private UUID runId;
    private String content;
    private VerifiedGrant grant;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        runId = UUID.randomUUID();
        content = "原文内容：不做整理";
        grant = grant(userId, sessionId, runId, content);
        run = liveRun(userId, sessionId, runId);
        when(runRepository.lockRun(runId)).thenReturn(run);
        when(receiptMapper.findByUserAndCall(userId, AgentWriteGrantService.callId(runId))).thenReturn(null);
        when(receiptMapper.insert(any(AgentCommandReceipt.class))).thenReturn(1);
        when(toolCallMapper.insert(any())).thenReturn(1);
        when(recordService.create(any(RecordDTO.class), any())).thenReturn(RecordVO.builder()
                .id(77L).status(RecordStatus.RECORD_ONLY).createdAt(OffsetDateTime.now()).build());
    }

    @Test
    void createsOnlyTheAuthorizedRecordOnlyBodyAndAuditsOnce() {
        AgentCommandResponse response = service.createRecord(
                grant, runId, AgentWriteGrantService.callId(runId), content);

        assertEquals("succeeded", response.status());
        assertFalse(response.replayed());
        assertEquals(77L, response.record().id());
        org.mockito.ArgumentCaptor<RecordDTO> dto = org.mockito.ArgumentCaptor.forClass(RecordDTO.class);
        verify(recordService).create(dto.capture(), org.mockito.ArgumentMatchers.eq(userId));
        assertEquals(content, dto.getValue().getContent());
        assertFalse(dto.getValue().isRequestAiProcessing());
        assertEquals(java.util.Set.of("content", "requestAiProcessing"),
                java.util.Arrays.stream(dto.getValue().getClass().getDeclaredFields())
                        .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet()),
                "RecordDTO must not accept a title or date from the model");
        verify(receiptMapper).insert(any(AgentCommandReceipt.class));
        verify(toolCallMapper).insert(any());
    }

    @Test
    void sameCallAndSameContentReplaysStoredReceiptWithoutCreatingAgain() {
        UUID callId = AgentWriteGrantService.callId(runId);
        when(receiptMapper.findByUserAndCall(userId, callId)).thenReturn(AgentCommandReceipt.builder()
                .userId(userId).callId(callId).runId(runId).command("create_record")
                .requestHash(AgentWriteGrantService.sha256(content))
                .resultJson("{\"call_id\":\"" + callId + "\",\"status\":\"succeeded\","
                        + "\"replayed\":false,\"record\":{\"id\":77,\"status\":\"record_only\","
                        + "\"created_at\":\"2026-10-20T10:00:00Z\"}}")
                .createdAt(OffsetDateTime.now()).build());

        AgentCommandResponse response = service.createRecord(grant, runId, callId, content);

        assertTrue(response.replayed());
        assertEquals(77L, response.record().id());
        verify(recordService, never()).create(any(), any());
        verify(receiptMapper, never()).insert(any());
        verify(toolCallMapper, never()).insert(any());
    }

    @Test
    void changedContentForSameCallConflictsBeforeAnyWrite() {
        UUID callId = AgentWriteGrantService.callId(runId);
        when(receiptMapper.findByUserAndCall(userId, callId)).thenReturn(AgentCommandReceipt.builder()
                .userId(userId).callId(callId).runId(runId).command("create_record")
                .requestHash(AgentWriteGrantService.sha256(content)).resultJson("{}").build());

        AgentCommandException error = assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, runId, callId, content + "已被修改"));

        assertEquals("CALL_ID_CONTENT_CONFLICT", error.getCode());
        verify(recordService, never()).create(any(), any());
    }

    @Test
    void changedContentBeforeTheFirstWriteDoesNotMatchSignedGrant() {
        AgentCommandException error = assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, runId, AgentWriteGrantService.callId(runId), content + "改字"));
        assertEquals("COMMAND_NOT_AUTHORIZED", error.getCode());
        verify(recordService, never()).create(any(), any());
    }

    @Test
    void anotherUsersGrantCannotReadThisUsersReceipt() {
        UUID anotherUser = UUID.randomUUID();
        VerifiedGrant anotherUsersGrant = grant(anotherUser, sessionId, runId, content);

        assertTrue(service.getReceipt(anotherUsersGrant, AgentWriteGrantService.callId(runId)).isEmpty());

        verify(receiptMapper).findByUserAndCall(anotherUser, AgentWriteGrantService.callId(runId));
    }

    @Test
    void anotherUsersRunCannotBeWritten() {
        when(runRepository.lockRun(runId)).thenReturn(liveRun(UUID.randomUUID(), sessionId, runId));

        AgentCommandException error = assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, runId, AgentWriteGrantService.callId(runId), content));

        assertEquals("COMMAND_NOT_AUTHORIZED", error.getCode());
        verify(recordService, never()).create(any(), any());
    }

    @Test
    void shadowCancelledLeaseLostAndExpiredRunsCannotWrite() {
        for (AgentRun invalid : new AgentRun[]{
                copyRun("shadow", "running", run.getLeaseExpiresAt()),
                copyRun("live", "cancel_requested", run.getLeaseExpiresAt()),
                copyRun("live", "cancelled", run.getLeaseExpiresAt()),
                copyRun("live", "running", OffsetDateTime.now().minusSeconds(1))
        }) {
            when(runRepository.lockRun(runId)).thenReturn(invalid);
            AgentCommandException error = assertThrows(AgentCommandException.class,
                    () -> service.createRecord(grant, runId, AgentWriteGrantService.callId(runId), content));
            assertEquals("RUN_NOT_EXECUTABLE", error.getCode());
        }
        VerifiedGrant expired = new VerifiedGrant(userId, sessionId, runId, "create_record", "create_record:0",
                AgentWriteGrantService.sha256(content), false, OffsetDateTime.now().minusSeconds(1));
        assertEquals("INVALID_COMMAND_GRANT", assertThrows(AgentCommandException.class,
                () -> service.createRecord(expired, runId, AgentWriteGrantService.callId(runId), content)).getCode());
        verify(recordService, never()).create(any(), any());
    }

    @Test
    void grantCannotChangeRunOrCallSlot() {
        assertEquals("COMMAND_NOT_AUTHORIZED", assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, UUID.randomUUID(), AgentWriteGrantService.callId(runId), content)).getCode());
        assertEquals("COMMAND_NOT_AUTHORIZED", assertThrows(AgentCommandException.class,
                () -> service.createRecord(grant, runId, UUID.randomUUID(), content)).getCode());
    }

    private static VerifiedGrant grant(UUID userId, UUID sessionId, UUID runId, String content) {
        return new VerifiedGrant(userId, sessionId, runId, "create_record", "create_record:0",
                AgentWriteGrantService.sha256(content), false, OffsetDateTime.now().plusMinutes(2));
    }

    private static AgentRun liveRun(UUID userId, UUID sessionId, UUID runId) {
        return AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId)
                .engine("graph_v2").mode("live").status("running")
                .startedAt(OffsetDateTime.now().minusSeconds(2))
                .leaseExpiresAt(OffsetDateTime.now().plusMinutes(1)).build();
    }

    private AgentRun copyRun(String mode, String status, OffsetDateTime leaseExpiresAt) {
        return AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId).engine("graph_v2")
                .mode(mode).status(status).startedAt(run.getStartedAt()).leaseExpiresAt(leaseExpiresAt).build();
    }
}
