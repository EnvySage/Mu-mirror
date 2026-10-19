package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.mapper.ChatMemoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import org.xianshen.mumirrorb.service.impl.AssistantContextServiceImpl;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantContextServiceTest {
    @Test void injectsAtMostFiveRelevantAuthorizedMemoriesFromServerOnly() throws Exception {
        UUID user = UUID.randomUUID();
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        ActionProposalService actions = mock(ActionProposalService.class);
        List<ChatMemory> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(memory("关于跑步的记忆 " + i, "ACTIVE", null, i));
        rows.add(memory("过期的跑步记忆", "ACTIVE", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1), 8));
        rows.add(memory("忘记的跑步记忆", "FORGOTTEN", null, 9));
        when(mapper.selectAuthorizedForContext(eq(user), any())).thenReturn(rows);

        AssistantContextService service = new AssistantContextServiceImpl(mapper, actions);
        var context = service.build(user, null, "跑步安排");

        assertEquals(5, context.getMemoriesCount());
        assertTrue(context.getMemoriesList().stream().allMatch(m -> m.getContent().contains("跑步")));
        assertFalse(context.hasDivination());
        verify(mapper).selectAuthorizedForContext(eq(user), any());
        verifyNoInteractions(actions);
        assertEquals(3, AssistantContextService.class.getMethod("build", UUID.class, UUID.class, String.class)
                .getParameterCount());
    }

    @Test void unrelatedQuestionDoesNotForceMemoryIntoContext() {
        UUID user = UUID.randomUUID();
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        when(mapper.selectAuthorizedForContext(eq(user), any())).thenReturn(List.of(memory("我在准备马拉松", "ACTIVE", null, 1)));
        var service = new AssistantContextServiceImpl(mapper, mock(ActionProposalService.class));

        assertEquals(0, service.build(user, null, "喜欢什么电影").getMemoriesCount());
    }

    @Test void profileUsesRecentAuthorizedMemoriesWithoutAQuestionRelevanceGate() {
        UUID user = UUID.randomUUID();
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        when(mapper.selectAuthorizedForContext(eq(user), any())).thenReturn(List.of(memory("我喜欢科幻电影", "ACTIVE", null, 1)));
        var service = new AssistantContextServiceImpl(mapper, mock(ActionProposalService.class));

        assertEquals(1, service.buildForProfile(user, "生成画像").context().getMemoriesCount());
    }

    private static ChatMemory memory(String content, String state, OffsetDateTime expiry, int day) {
        ChatMemory m = new ChatMemory(); m.setId(UUID.randomUUID()); m.setContent(content); m.setKind("fact");
        m.setState(state); m.setSourceDate(LocalDate.of(2026, 10, 1).plusDays(day)); m.setExpiresAt(expiry);
        m.setSourceMessageIds("[" + (day + 1) + "]"); m.setCreatedAt(OffsetDateTime.now());
        return m;
    }
}
