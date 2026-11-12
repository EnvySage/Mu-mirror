package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.config.ChatEngineProperties;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AgentRunAchievementContextTest {
    @Test void completedStreamExposesPersistedAssistantIdentityForSourceOpens() throws Exception {
        UUID user = UUID.randomUUID(), session = UUID.randomUUID(), runId = UUID.randomUUID();
        var runs = mock(AgentRunMapper.class);
        var commands = mock(AgentCommandService.class);
        var sessions = mock(ChatSessionMapper.class);
        var history = mock(ConversationHistoryMapper.class);
        var service = new AgentRunService(runs, commands, sessions, history, null, null, null,
                null, null, null, new ChatEngineProperties(), new AgentProperties(), null, null,
                new ObjectMapper(), mock(PlatformTransactionManager.class));
        var run = AgentRun.builder().runId(runId).userId(user).sessionId(session)
                .clientRequestId(UUID.randomUUID()).engine("legacy").status("completed")
                .userMessageId(41L).assistantMessageId(42L).build();
        when(commands.findReceiptForRun(user, runId)).thenReturn(Optional.empty());
        when(history.selectById(42L)).thenReturn(ConversationHistory.builder().id(42L)
                .role("assistant").content("这是已保存的回复").isFallback(false).build());
        var emitter = mock(SseEmitter.class);
        List<Object> emitted = new ArrayList<>();
        doAnswer(call -> {
            SseEmitter.SseEventBuilder event = call.getArgument(0);
            event.build().forEach(part -> emitted.add(part.getData()));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.dispatch(new StartRunResult(run, null, false), emitter);

        Map<?,?> done = null;
        for (Object part : emitted) {
            if (part instanceof String data && data.startsWith("{")) {
                Map<?,?> payload = new ObjectMapper().readValue(data, Map.class);
                if (payload.containsKey("status")) done = payload;
            }
        }
        assertNotNull(done, "The stream must send a done payload");
        assertEquals("completed", done.get("status"));
        assertEquals(42, done.get("assistantMessageId"));
        assertNotEquals(run.getUserMessageId(), done.get("assistantMessageId"));
    }
}
