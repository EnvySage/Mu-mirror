package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.*;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.service.impl.ChatMemoryServiceImpl;
import org.xianshen.mumirrorb.service.impl.SnapshotLineageService;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatMemoryForgetTest {
    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID memoryId = UUID.randomUUID();

    @Test void staleVersionDoesNotWriteTombstonesOrHideSummary() {
        Fixture f = new Fixture();
        ChatMemory memory = f.memory(); memory.setVersion(3);
        when(f.memories.lockOwned(user, memoryId)).thenReturn(memory);

        assertThrows(BusinessException.class, () -> f.service.forget(user, memoryId, 2));

        verifyNoInteractions(f.exclusions, f.summaries, f.actions, f.lineage);
        verify(f.memories, never()).forgetOwned(any(), any(), anyLong());
    }

    @Test void forgetExcludesUserTurnAndAssistantReplyButKeepsHistoryReadable() {
        Fixture f = new Fixture();
        ChatMemory memory = f.memory();
        when(f.memories.lockOwned(user, memoryId)).thenReturn(memory);
        when(f.memories.forgetOwned(user, memoryId, 4)).thenReturn(1);
        when(f.exclusions.insertAll(anyList())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        when(f.history.selectBySession(session, user)).thenReturn(List.of(
                message(11L, "user", "FORGETTABLE_PRIVATE_TEXT"), message(12L, "assistant", "REPEATED_PRIVATE_TEXT"),
                message(13L, "user", "keep this turn"), message(14L, "assistant", "keep reply")));
        ActionProposalVO pending = ActionProposalVO.builder().id(UUID.randomUUID()).kind("memory.accept")
                .state("PENDING").version(2).sourceMessageId(99L)
                .payload(candidatePayload()).build();
        when(f.actions.listForSession(user, session)).thenReturn(List.of(pending));
        when(f.actions.cancel(user, pending.getId(), 2)).thenReturn(ActionProposalVO.builder().id(pending.getId()).state("CANCELLED").build());

        f.service.forget(user, memoryId, 4);

        var rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(f.exclusions).insertAll(rows.capture());
        List<ChatSourceExclusion> tombstones = (List<ChatSourceExclusion>) rows.getValue();
        assertEquals(Set.of(11L, 12L), tombstones.stream().map(ChatSourceExclusion::getSourceId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(tombstones.stream().allMatch(row -> row.getReason().equals("memory_forgotten")));
        assertTrue(tombstones.stream().allMatch(row -> row.getSourceFingerprint() == null));
        verify(f.summaries).invalidateForForgottenSource(user, LocalDate.of(2026, 10, 18));
        verify(f.actions).cancel(user, pending.getId(), 2);
        verify(f.lineage).invalidateForForgottenChatSources(user, List.of(11L, 12L));
    }

    @Test void failedRelatedCardCancellationAbortsForget() {
        Fixture f = new Fixture();
        ChatMemory memory = f.memory();
        when(f.memories.lockOwned(user, memoryId)).thenReturn(memory);
        when(f.memories.forgetOwned(user, memoryId, 4)).thenReturn(1);
        when(f.exclusions.insertAll(anyList())).thenReturn(2);
        when(f.history.selectBySession(session, user)).thenReturn(List.of(message(11L, "user", "private"), message(12L, "assistant", "echo")));
        ActionProposalVO pending = ActionProposalVO.builder().id(UUID.randomUUID()).kind("memory.accept").state("PENDING")
                .version(1).sourceMessageId(11L).build();
        when(f.actions.listForSession(user, session)).thenReturn(List.of(pending));
        when(f.actions.cancel(user, pending.getId(), 1)).thenThrow(new RuntimeException("cancel failed"));

        assertThrows(RuntimeException.class, () -> f.service.forget(user, memoryId, 4));
        // The user row is locked before cancellation; invalidation is after cancellation and must not run on failure.
        verify(f.lineage).lockUserLineage(user);
        verify(f.lineage, never()).invalidateForForgottenChatSources(any(), anyList());
    }

    @Test void editedMemoryInvalidatesItsProfileDependenciesWithoutForgettingSources() {
        Fixture f = new Fixture();
        ChatMemory edited = f.memory(); edited.setVersion(5);
        when(f.memories.findOwned(user, memoryId)).thenReturn(edited);

        f.service.invalidateEditedMemorySources(user, memoryId, 5);

        verify(f.lineage).invalidateForChangedChatSources(user, List.of(11L));
        verifyNoInteractions(f.exclusions, f.summaries);
    }

    @Test void forgetAlsoExcludesLaterAssistantTurnThatConsumedTheMemorySource() {
        Fixture f = new Fixture();
        ChatMemory memory = f.memory();
        when(f.memories.lockOwned(user, memoryId)).thenReturn(memory);
        when(f.memories.forgetOwned(user, memoryId, 4)).thenReturn(1);
        when(f.exclusions.insertAll(anyList())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        when(f.history.selectBySession(session, user)).thenReturn(List.of(
                message(11L, "user", "old source"), message(12L, "assistant", "old reply"),
                message(21L, "user", "new question"), message(22L, "assistant", "later paraphrase")));
        when(f.history.selectAssistantRepliesDependingOn(user, new Long[]{11L})).thenReturn(List.of(22L));

        f.service.forget(user, memoryId, 4);

        var rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(f.exclusions).insertAll(rows.capture());
        List<ChatSourceExclusion> tombstones = (List<ChatSourceExclusion>) rows.getValue();
        assertTrue(tombstones.stream().map(ChatSourceExclusion::getSourceId).toList().containsAll(List.of(11L, 12L, 22L)));
        assertFalse(tombstones.stream().anyMatch(row -> row.getSourceId().equals(21L)),
                "a later user's turn is retained even when its dependent assistant reply is excluded");
    }

    @Test void assistantReplyPersistenceSerializesOnTheSameUserLineageLock() {
        Fixture f = new Fixture();
        ConversationHistory reply = message(null, "assistant", "reply referencing authorized memory");
        reply.setContextSourceMessageIds(new ObjectMapper().createArrayNode().add(11L));

        f.service.persistAssistantReply(reply);

        var order = inOrder(f.lineage, f.history);
        order.verify(f.lineage).lockUserLineage(user);
        order.verify(f.history).insert(reply);
    }

    private ConversationHistory message(Long id, String role, String content) {
        return ConversationHistory.builder().id(id).userId(user).sessionId(session).role(role).content(content)
                .createdAt(OffsetDateTime.now()).build();
    }

    private static com.fasterxml.jackson.databind.JsonNode candidatePayload() {
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.createObjectNode();
        var draft = mapper.createObjectNode();
        draft.putArray("sourceMessageIds").add(11L).add(15L);
        payload.set("memoryDraft", draft);
        return payload;
    }

    private final class Fixture {
        final ChatDaySummaryMapper summaries = mock(ChatDaySummaryMapper.class);
        final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        final ConversationHistoryMapper history = mock(ConversationHistoryMapper.class);
        final ChatMemoryMapper memories = mock(ChatMemoryMapper.class);
        final AiGrpcClient ai = mock(AiGrpcClient.class);
        final ActionProposalService actions = mock(ActionProposalService.class);
        final ChatSourceExclusionMapper exclusions = mock(ChatSourceExclusionMapper.class);
        final SnapshotLineageService lineage = mock(SnapshotLineageService.class);
        final ChatMemoryServiceImpl service = new ChatMemoryServiceImpl(summaries, sessions, history, memories, ai, actions, new ObjectMapper());
        Fixture() { ReflectionTestUtils.setField(service, "exclusions", exclusions); ReflectionTestUtils.setField(service, "lineage", lineage); }
        ChatMemory memory() {
            ChatMemory m = new ChatMemory(); m.setId(memoryId); m.setUserId(user); m.setContent("FORGETTABLE_PRIVATE_TEXT");
            m.setState("ACTIVE"); m.setVersion(4); m.setSourceSessionId(session); m.setSourceDate(LocalDate.of(2026, 10, 18));
            m.setSourceMessageIds("[11]"); return m;
        }
    }
}
