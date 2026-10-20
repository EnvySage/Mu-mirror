package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.config.ChatEngineProperties;
import org.xianshen.mumirrorb.grpc.AgentGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.tools.AuditService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {

    private final AgentRunRepository repository = mock(AgentRunRepository.class);
    private final AgentCommandService commandService = mock(AgentCommandService.class);
    private final ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
    private final ConversationHistoryMapper historyMapper = mock(ConversationHistoryMapper.class);
    private final ChatService chatService = mock(ChatService.class);
    private final AgentGrpcClient agentGrpcClient = mock(AgentGrpcClient.class);
    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    private final GlossaryService glossaryService = mock(GlossaryService.class);
    private final ChatEngineProperties engineProperties = new ChatEngineProperties();
    private final AgentProperties agentProperties = new AgentProperties();
    private final AuditService auditService = mock(AuditService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private AgentRunService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        doAnswer(invocation -> {
            ConversationHistory message = invocation.getArgument(0);
            message.setId(71L);
            return 1;
        }).when(historyMapper).insert(any(ConversationHistory.class));
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(ChatSession.builder()
                .id(SESSION_ID).userId(USER_ID).title("test").build());
        when(commandService.findReceiptForRun(any(), any())).thenReturn(java.util.Optional.empty());
        service = new AgentRunService(repository, commandService, sessionMapper, historyMapper, chatService,
                agentGrpcClient, lifecycle, glossaryService, engineProperties, agentProperties,
                auditService, new ObjectMapper(), transactionManager);
    }

    @Test
    void firstRequestWithNullSessionPersistsOneStableRunAndUserMessage() {
        UUID requestId = UUID.randomUUID();
        ChatRequestDTO dto = ChatRequestDTO.builder().question("先确认 null 会话哈希").requestId(requestId).build();

        StartRunResult result = service.startOrFindRun(USER_ID, dto);

        assertTrue(result.created());
        assertEquals("legacy", result.run().getEngine());
        assertEquals(requestId, result.run().getClientRequestId());
        assertEquals(71L, result.invocation().userMessageId());
        assertEquals(result.run().getSessionId(), result.invocation().sessionId());
        assertEquals("先确认 null 会话哈希", result.invocation().question());
        verify(sessionMapper).insert(any(ChatSession.class));
        verify(repository).insert(any(AgentRun.class));
        verify(repository).linkUserMessage(result.run().getRunId(), 71L);
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "user".equals(message.getRole()) && result.run().getRunId().equals(message.getRunId())));
        verify(lifecycle).startLease(org.mockito.ArgumentMatchers.eq(result.run().getRunId()),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void sameRequestIdAndNullExternalSessionReturnsExistingRunWithoutReinsertingMessages() {
        UUID requestId = UUID.randomUUID();
        ChatRequestDTO first = ChatRequestDTO.builder().question("重复请求").requestId(requestId).build();
        StartRunResult created = service.startOrFindRun(USER_ID, first);
        AgentRun persisted = created.run();
        persisted.setUserMessageId(71L);
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(persisted);
        when(historyMapper.selectById(71L)).thenReturn(ConversationHistory.builder().id(71L).build());
        when(sessionMapper.selectById(persisted.getSessionId())).thenReturn(ChatSession.builder()
                .id(persisted.getSessionId()).userId(USER_ID).build());

        StartRunResult repeated = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("重复请求").requestId(requestId).build());

        assertFalse(repeated.created());
        assertEquals(persisted.getRunId(), repeated.run().getRunId());
        assertEquals(persisted.getSessionId(), repeated.run().getSessionId());
        verify(sessionMapper, times(1)).insert(any(ChatSession.class));
        verify(repository, times(1)).insert(any(AgentRun.class));
        verify(historyMapper, times(1)).insert(any(ConversationHistory.class));
    }

    @Test
    void reusingRequestIdForDifferentQuestionReturnsConflict() {
        UUID requestId = UUID.randomUUID();
        StartRunResult created = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("原问题").requestId(requestId).build());
        AgentRun persisted = created.run();
        persisted.setUserMessageId(71L);
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(persisted);

        assertThrows(AgentRunConflictException.class, () -> service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("改了问题").requestId(requestId).build()));
        verify(repository, times(1)).insert(any(AgentRun.class));
    }

    @Test
    void sameRequestAfterSessionDeletionReturnsGoneAndDoesNotRecreate() {
        UUID requestId = UUID.randomUUID();
        StartRunResult created = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("删除后重放").requestId(requestId).build());
        AgentRun persisted = created.run();
        persisted.setUserMessageId(null);
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(persisted);

        assertThrows(RunGoneException.class, () -> service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("删除后重放").requestId(requestId).build()));
        verify(repository, times(1)).insert(any(AgentRun.class));
        verify(historyMapper, times(1)).insert(any(ConversationHistory.class));
    }

    @Test
    void activeSessionUniqueConflictMapsToHttpConflict() {
        UUID requestId = UUID.randomUUID();
        when(repository.findActiveBySession(SESSION_ID)).thenReturn(AgentRun.builder()
                .runId(UUID.randomUUID()).userId(USER_ID).sessionId(SESSION_ID).status("running").build());
        when(repository.findByRequest(USER_ID, requestId)).thenReturn(null);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("partial unique index"))
                .when(repository).insert(any(AgentRun.class));

        assertThrows(AgentRunConflictException.class, () -> service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("并发请求").sessionId(SESSION_ID).requestId(requestId).build()));
        verify(historyMapper, never()).insert(any(ConversationHistory.class));
    }

    @Test
    void legacyResultUsesOneSharedAssistantPersistenceExit() {
        UUID requestId = UUID.randomUUID();
        StartRunResult accepted = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("回答问题").requestId(requestId).build());
        AgentRun run = accepted.run();
        when(repository.findById(run.getRunId())).thenReturn(run);
        when(repository.lockRun(run.getRunId())).thenReturn(run);
        when(repository.complete(any(AgentRun.class), any(), any(), any(), any(), anyInt(), anyInt(),
                anyLong(), anyLong(), any())).thenReturn(true);
        when(sessionMapper.selectById(run.getSessionId())).thenReturn(ChatSession.builder()
                .id(run.getSessionId()).userId(USER_ID).title("回答问题").build());
        doReturn(ChatFinalResult.legacyAnswer("旧版回答", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), "HYBRID"))
                .when(chatService).runLegacy(any(ChatInvocation.class), any(SseEmitter.class));

        service.dispatch(accepted, new SseEmitter());

        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "assistant".equals(message.getRole()) && run.getRunId().equals(message.getRunId())
                        && "旧版回答".equals(message.getContent())));
        verify(repository).complete(any(AgentRun.class), org.mockito.ArgumentMatchers.eq("completed"),
                org.mockito.ArgumentMatchers.eq("answer"), org.mockito.ArgumentMatchers.eq("done"),
                org.mockito.ArgumentMatchers.isNull(), anyInt(), anyInt(), anyLong(), anyLong(), any());
        assertEquals("completed", run.getStatus());
    }

    @Test
    void clientDisconnectCancelsActiveAgentGrpcCall() {
        engineProperties.setEngine("graph_v2");
        engineProperties.setGraphEnabledUserIds(java.util.List.of(USER_ID.toString()));
        UUID requestId = UUID.randomUUID();
        StartRunResult accepted = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("取消中的新版运行").requestId(requestId).build());
        AgentRun run = accepted.run();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(repository.findById(run.getRunId())).thenReturn(run);
        when(agentGrpcClient.buildRequest(any(ChatInvocation.class), any(AgentRun.class)))
                .thenReturn(AgentProto.AgentRunRequest.newBuilder().build());
        when(agentGrpcClient.run(any(AgentProto.AgentRunRequest.class), any()))
                .thenReturn(() -> cancelled.set(true));
        when(repository.requestCancel(run.getRunId(), USER_ID)).thenReturn(true);

        service.dispatch(accepted, new SseEmitter());
        service.cancelRun(run.getRunId(), USER_ID, "client_disconnect");

        assertTrue(cancelled.get());
        verify(lifecycle).stopLease(run.getRunId());
    }
}
