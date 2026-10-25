package org.xianshen.mumirrorb.service.impl;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DailyChatSessionTest {
    @Test void sameDayTwoClientsResolveSameSession() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        Map<LocalDate, ChatSession> byDate = new ConcurrentHashMap<>();
        Map<UUID, ChatSession> byId = new ConcurrentHashMap<>();
        when(mapper.findDaily(any(), any())).thenAnswer(i -> byDate.get(i.getArgument(1)));
        when(mapper.insertDaily(any())).thenAnswer(i -> {
            ChatSession proposed = i.getArgument(0);
            ChatSession winner = byDate.putIfAbsent(proposed.getDayKey(), proposed);
            ChatSession resolved = winner == null ? proposed : winner;
            byId.putIfAbsent(resolved.getId(), resolved);
            return winner == null ? 1 : 0;
        });
        when(mapper.selectById(any())).thenAnswer(i -> byId.get(i.getArgument(0)));
        DailyChatSessionResolver resolver = new DailyChatSessionResolver(mapper);
        UUID user = UUID.randomUUID(); LocalDate day = LocalDate.of(2026, 10, 19);
        ChatSession clientA = resolver.getOrCreate(user, day, "A");
        ChatSession clientB = resolver.getOrCreate(user, day, "B");
        assertEquals(clientA.getId(), clientB.getId());
        assertEquals("Asia/Shanghai", clientA.getTimezone());
        verify(mapper, times(1)).insertDaily(any());
    }

    @Test void resolverRoutesNextDaySendToNewSession() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        Map<LocalDate, ChatSession> byDate = new ConcurrentHashMap<>();
        Map<UUID, ChatSession> byId = new ConcurrentHashMap<>();
        when(mapper.findDaily(any(), any())).thenAnswer(i -> byDate.get(i.getArgument(1)));
        when(mapper.insertDaily(any())).thenAnswer(i -> {
            ChatSession proposed = i.getArgument(0); byDate.putIfAbsent(proposed.getDayKey(), proposed);
            byId.putIfAbsent(proposed.getId(), proposed); return 1;
        });
        when(mapper.selectById(any())).thenAnswer(i -> byId.get(i.getArgument(0)));
        DailyChatSessionResolver resolver = new DailyChatSessionResolver(mapper);
        UUID user = UUID.randomUUID(); LocalDate day1 = LocalDate.of(2026, 10, 19);
        ChatSession original = resolver.getOrCreate(user, day1, "before midnight");
        UUID streamSessionId = resolver.resolveSend(user, original.getId(), "stream", day1).getId();
        ChatSession nextSend = resolver.resolveSend(user, original.getId(), "after midnight", day1.plusDays(1));
        assertEquals(original.getId(), streamSessionId);
        assertNotEquals(original.getId(), nextSend.getId());
        assertEquals(day1, original.getDayKey());
        assertEquals(day1.plusDays(1), nextSend.getDayKey());
    }
}
