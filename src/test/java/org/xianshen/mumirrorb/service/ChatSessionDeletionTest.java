package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.service.impl.ChatServiceImpl;
import org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSessionDeletionTest {
    @Test void pendingActionCancellationFailureKeepsSessionAndCardRecoverable() {
        UUID user = UUID.randomUUID(); UUID sessionId = UUID.randomUUID(); UUID actionId = UUID.randomUUID();
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        var actions = mock(ActionProposalService.class);
        ChatSession session = ChatSession.builder().id(sessionId).userId(user)
                .dayKey(LocalDate.now(ZoneId.of("Asia/Shanghai"))).build();
        when(sessions.selectById(sessionId)).thenReturn(session);
        ActionProposalVO pending = ActionProposalVO.builder().id(actionId).kind("memory.accept")
                .state("PENDING").version(3).build();
        when(actions.listForSession(user, sessionId)).thenReturn(List.of(pending));
        when(actions.cancel(user, actionId, 3)).thenThrow(new IllegalStateException("database unavailable"));
        ChatServiceImpl service = new ChatServiceImpl(sessions, mock(ConversationHistoryMapper.class),
                mock(ChatSearchMapper.class), mock(SettingsMapper.class), mock(org.xianshen.mumirrorb.grpc.AiGrpcClient.class),
                mock(GlossaryService.class), mock(org.xianshen.mumirrorb.tools.ToolOrchestrator.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), new org.xianshen.mumirrorb.config.MirrorProperties(),
                new org.xianshen.mumirrorb.config.VaultProperties(), mock(ProfileStatsMapper.class),
                mock(ChatMemoryService.class), new DailyChatSessionResolver(sessions));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "actionProposalService", actions);

        assertThrows(RuntimeException.class, () -> service.deleteSession(sessionId, user));

        verify(actions).cancel(user, actionId, 3);
        verify(sessions, never()).deleteById(sessionId);
        assertEquals("PENDING", pending.getState());
    }
}
