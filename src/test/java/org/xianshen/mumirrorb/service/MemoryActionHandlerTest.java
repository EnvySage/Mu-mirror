package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.AuthorizedAction;
import org.xianshen.mumirrorb.action.MemoryActionHandler;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import org.xianshen.mumirrorb.mapper.ChatMemoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemoryActionHandlerTest {

    @Test
    void memoryChangesArePreparedThroughActionCards() {
        var handler = new org.xianshen.mumirrorb.action.MemoryActionHandler(
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.ChatMemoryMapper.class),
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.ChatMemoryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(java.util.Set.of("memory.accept", "memory.edit", "memory.forget"), handler.kinds());
    }
    private final ObjectMapper json = new ObjectMapper();

    @Test void confirmedExpiryAcceptsDateAndOffsetDateTime() throws Exception {
        UUID user = UUID.randomUUID();
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        when(memories.insertConfirmed(any())).thenReturn(1);
        MemoryActionHandler handler = new MemoryActionHandler(memories, mock(ChatMemoryService.class), json);

        ChatMemory dateExpiry = execute(handler, memories, user, "2026-10-25");
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                dateExpiry.getExpiresAt());

        ChatMemory offsetExpiry = execute(handler, memories, user, "2026-10-26T08:15:00-04:00");
        assertEquals(OffsetDateTime.parse("2026-10-26T08:15:00-04:00"), offsetExpiry.getExpiresAt());
    }

    @Test void dateOnlyExpiryRemainsActiveThroughTheSelectedCalendarDay() throws Exception {
        UUID user = UUID.randomUUID();
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        when(memories.insertConfirmed(any())).thenReturn(1);
        MemoryActionHandler handler = new MemoryActionHandler(memories, mock(ChatMemoryService.class), json);

        ChatMemory memory = execute(handler, memories, user, "2026-10-25");

        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime(),
                memory.getExpiresAt());
    }

    @Test void forgetPreviewExplainsIndependentRecordsRemain() {
        UUID user = UUID.randomUUID(); UUID id = UUID.randomUUID();
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        ChatMemory memory = new ChatMemory(); memory.setId(id); memory.setUserId(user); memory.setState("ACTIVE");
        memory.setVersion(4); memory.setContent("偏好晨间阅读"); memory.setKind("preference");
        memory.setSourceSessionId(UUID.randomUUID()); memory.setSourceMessageIds("[12]");
        when(memories.findOwned(user, id)).thenReturn(memory);
        MemoryActionHandler handler = new MemoryActionHandler(memories, mock(ChatMemoryService.class), json);
        ObjectNode payload = json.createObjectNode().put("memoryId", id.toString()).put("expectedVersion", 4);

        var preview = handler.validateAndPreview(user, "memory.forget", payload);

        String explanation = preview.path("explanation").asText();
        assertTrue(explanation.contains("聊天内容"));
        assertTrue(explanation.contains("每日总结"));
        assertTrue(explanation.contains("画像"));
        assertTrue(explanation.contains("记录、待办和成长贡献会保留"));
        String description = preview.path("description").asText();
        assertTrue(description.contains("聊天内容"));
        assertTrue(description.contains("记录、待办和成长贡献会保留"));
    }

    @Test void confirmedEditInvalidatesSnapshotsForTheMemorySources() throws Exception {
        UUID user = UUID.randomUUID(); UUID id = UUID.randomUUID();
        ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        when(memories.updateActive(eq(user), eq(id), eq(4L), eq("更稳定的偏好"), eq("preference"),
                eq(null), eq(null))).thenReturn(1);
        MemoryActionHandler handler = new MemoryActionHandler(memories, chatMemory, json);
        ObjectNode payload = json.createObjectNode().put("memoryId", id.toString()).put("expectedVersion", 4)
                .put("content", "更稳定的偏好").put("kind", "preference");
        ActionProposal proposal = new ActionProposal();
        proposal.setId(UUID.randomUUID()); proposal.setUserId(user); proposal.setKind("memory.edit");
        proposal.setPayload(payload); proposal.setTargetVersions(json.createObjectNode()); proposal.setExecutionVersion(2);
        var permitConstructor = ActionProposalServiceImpl.ConfirmationPermit.class.getDeclaredConstructor();
        permitConstructor.setAccessible(true);
        AuthorizedAction authorized = AuthorizedAction.issue(permitConstructor.newInstance(), proposal);

        handler.execute(authorized);

        verify(chatMemory).invalidateEditedMemorySources(user, id, 5);
    }

    private ChatMemory execute(MemoryActionHandler handler, ChatMemoryMapper memories, UUID user, String expiry) throws Exception {
        UUID memoryId = UUID.randomUUID();
        ObjectNode draft = json.createObjectNode().put("memoryId", memoryId.toString())
                .put("content", "旅行安排").put("kind", "event").put("sourceDate", "2026-10-18").put("version", 1)
                .put("expiresAt", expiry);
        draft.putArray("sourceMessageIds").add(12L);
        ObjectNode payload = json.createObjectNode().set("memoryDraft", draft);
        ActionProposal proposal = new ActionProposal();
        proposal.setId(UUID.randomUUID()); proposal.setUserId(user); proposal.setKind("memory.accept");
        proposal.setPayload(payload); proposal.setTargetVersions(json.createObjectNode()); proposal.setExecutionVersion(2);
        var permitConstructor = ActionProposalServiceImpl.ConfirmationPermit.class.getDeclaredConstructor();
        permitConstructor.setAccessible(true);
        AuthorizedAction authorized = AuthorizedAction.issue(permitConstructor.newInstance(), proposal);

        handler.execute(authorized);

        var captured = org.mockito.ArgumentCaptor.forClass(ChatMemory.class);
        verify(memories, atLeastOnce()).insertConfirmed(captured.capture());
        return captured.getAllValues().get(captured.getAllValues().size() - 1);
    }
}
