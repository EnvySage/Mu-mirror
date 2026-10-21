package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;
import io.grpc.stub.StreamObserver;
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
import org.xianshen.mumirrorb.service.AssistantContextService;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver;
import org.xianshen.mumirrorb.tools.AuditService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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
    private final AssistantContextService assistantContextService = mock(AssistantContextService.class);
    private final DailyChatSessionResolver dailySessionResolver = new DailyChatSessionResolver(sessionMapper);
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
                .id(SESSION_ID).userId(USER_ID).title("test")
                .dayKey(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))).build());
        when(sessionMapper.findDaily(org.mockito.ArgumentMatchers.eq(USER_ID), any()))
                .thenReturn(ChatSession.builder().id(SESSION_ID).userId(USER_ID).title("test")
                        .dayKey(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))).build());
        when(assistantContextService.build(any(), any(), any()))
                .thenReturn(org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext.getDefaultInstance());
        when(commandService.findReceiptForRun(any(), any())).thenReturn(java.util.Optional.empty());
        service = new AgentRunService(repository, commandService, sessionMapper, historyMapper, chatService,
                assistantContextService, dailySessionResolver,
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
        verify(sessionMapper).findDaily(org.mockito.ArgumentMatchers.eq(USER_ID), any());
        verify(repository).insert(any(AgentRun.class));
        verify(repository).linkUserMessage(result.run().getRunId(), 71L);
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "user".equals(message.getRole()) && result.run().getRunId().equals(message.getRunId())));
        verify(lifecycle).startLease(org.mockito.ArgumentMatchers.eq(result.run().getRunId()),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void missingDailySessionIsCreatedWithShanghaiDayAndQuestionTitle() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        ChatSession daily = ChatSession.builder().id(UUID.randomUUID()).userId(USER_ID)
                .title("今天的会话").dayKey(today).timezone("Asia/Shanghai").build();
        when(sessionMapper.findDaily(USER_ID, today)).thenReturn(null, daily);

        StartRunResult accepted = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("今天的会话").build());

        assertEquals(daily.getId(), accepted.run().getSessionId());
        verify(sessionMapper).insertDaily(org.mockito.ArgumentMatchers.argThat(created ->
                USER_ID.equals(created.getUserId()) && today.equals(created.getDayKey())
                        && "Asia/Shanghai".equals(created.getTimezone())
                        && "今天的会话".equals(created.getTitle())));
    }

    @Test
    void shadowModeKeepsLegacyAsTheUserEngineAndFreezesTheSampleDecision() {
        engineProperties.setEngine("shadow");
        engineProperties.setShadowSampleRate(1.0);

        StartRunResult result = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("普通只读问题").requestId(UUID.randomUUID()).build());

        assertEquals("legacy", result.run().getEngine());
        assertEquals("live", result.run().getMode());
        assertTrue(result.run().getMetricsJson().contains("\"shadow_sampled\":true"));
    }

    @Test
    void sampledShadowNeverDispatchesAnExplicitOnlineWriteRequest() {
        AgentRun parent = AgentRun.builder().runId(UUID.randomUUID()).userId(USER_ID).sessionId(SESSION_ID)
                .engine("legacy").mode("live").status("completed").metricsJson("{\"shadow_sampled\":true}")
                .build();
        ChatInvocation invocation = new ChatInvocation(USER_ID, SESSION_ID, parent.getRunId(), UUID.randomUUID(),
                71L, "帮我记一篇日记：这条原文必须只写一次", "legacy");

        service.scheduleShadow(parent, invocation);

        verify(repository, never()).findById(parent.getRunId());
        verify(repository, never()).insert(any(AgentRun.class));
        verify(agentGrpcClient, never()).buildRequest(any(ChatInvocation.class), any(AgentRun.class));
    }

    @Test
    void shadowUsesOneNonQueuedSlotAndIsCancelledWhenForegroundGraphNeedsCapacity() throws Exception {
        AgentRun parent = AgentRun.builder().runId(UUID.randomUUID()).userId(USER_ID).sessionId(SESSION_ID)
                .engine("legacy").mode("live").status("completed").requestHash("parent-hash")
                .questionHash("question-hash").metricsJson("{\"shadow_sampled\":true}").build();
        AgentRun secondParent = AgentRun.builder().runId(UUID.randomUUID()).userId(USER_ID).sessionId(SESSION_ID)
                .engine("legacy").mode("live").status("completed").requestHash("parent-hash-2")
                .questionHash("question-hash-2").metricsJson("{\"shadow_sampled\":true}").build();
        when(repository.findById(parent.getRunId())).thenReturn(parent);
        when(repository.findById(secondParent.getRunId())).thenReturn(secondParent);
        when(repository.hasActiveForegroundGraph()).thenReturn(false);
        when(repository.requestShadowCancel(any(), any())).thenReturn(true);

        List<AgentRun> inserted = new ArrayList<>();
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch callCancelled = new CountDownLatch(1);
        CountDownLatch shadowFinished = new CountDownLatch(1);
        AtomicReference<StreamObserver<AgentProto.AgentEvent>> observer = new AtomicReference<>();
        doAnswer(call -> { inserted.add(call.getArgument(0)); return null; })
                .when(repository).insert(any(AgentRun.class));
        when(agentGrpcClient.buildRequest(any(ChatInvocation.class), any(AgentRun.class)))
                .thenReturn(AgentProto.AgentRunRequest.getDefaultInstance());
        when(agentGrpcClient.run(any(), any())).thenAnswer(call -> {
            observer.set(call.getArgument(1));
            callStarted.countDown();
            return (AgentGrpcClient.CancellableCall) callCancelled::countDown;
        });
        doAnswer(call -> { shadowFinished.countDown(); return true; })
                .when(repository).completeShadow(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any());

        try {
            service.scheduleShadow(parent, new ChatInvocation(USER_ID, SESSION_ID, parent.getRunId(),
                    UUID.randomUUID(), 71L, "只读评测问题", "legacy"));
            assertTrue(callStarted.await(2, TimeUnit.SECONDS));

            service.scheduleShadow(secondParent, new ChatInvocation(USER_ID, SESSION_ID, secondParent.getRunId(),
                    UUID.randomUUID(), 72L, "另一个只读问题", "legacy"));
            assertEquals(1, inserted.stream().filter(run -> "shadow".equals(run.getMode())).count());

            service.cancelShadowForForeground();
            assertTrue(callCancelled.await(2, TimeUnit.SECONDS));
            assertEquals("shadow", inserted.get(0).getMode());
            assertEquals(parent.getRunId(), inserted.get(0).getParentRunId());
            verify(repository).requestShadowCancel(inserted.get(0).getRunId(), "foreground_priority");

            observer.get().onError(new RuntimeException("cancelled by foreground"));
            assertTrue(shadowFinished.await(2, TimeUnit.SECONDS));
            verify(historyMapper, never()).insert(org.mockito.ArgumentMatchers.argThat(message ->
                    "assistant".equals(message.getRole()) && inserted.get(0).getRunId().equals(message.getRunId())));
            verify(glossaryService, never()).recordQueryHits(any(), any(), any());
            verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), anyMap(), any(),
                    anyBoolean(), anyLong(), any());
        } finally {
            service.closeShadowExecutor();
        }
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
        verify(sessionMapper, times(1)).findDaily(org.mockito.ArgumentMatchers.eq(USER_ID), any());
        verify(sessionMapper, never()).insert(any(ChatSession.class));
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
        var memory = org.xianshen.mumirrorb.grpc.gen.CommonProto.ChatMemoryContext.newBuilder()
                .setMemoryId(UUID.randomUUID().toString()).setContent("assistant provenance")
                .addSourceMessageIds(17L).addSourceMessageIds(21L).build();
        var context = org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext.newBuilder()
                .addMemories(memory).build();
        when(assistantContextService.build(org.mockito.ArgumentMatchers.eq(USER_ID), any(), any()))
                .thenReturn(context);
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
        assertEquals(context, accepted.invocation().assistantContext());
        assertEquals(java.util.List.of(17L, 21L), accepted.invocation().contextSourceMessageIds());

        service.dispatch(accepted, new SseEmitter());

        var assistantCaptor = org.mockito.ArgumentCaptor.forClass(ConversationHistory.class);
        verify(historyMapper, times(2)).insert(assistantCaptor.capture());
        ConversationHistory assistant = assistantCaptor.getAllValues().get(1);
        assertEquals("assistant", assistant.getRole());
        assertEquals(run.getRunId(), assistant.getRunId());
        assertEquals("旧版回答", assistant.getContent());
        assertEquals(new ObjectMapper().createArrayNode().add(17L).add(21L),
                assistant.getContextSourceMessageIds());
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

    @Test
    void committedCreateReceiptWinsWhenPythonStreamFailsAfterTheWrite() throws Exception {
        engineProperties.setEngine("graph_v2");
        engineProperties.setGraphEnabledUserIds(java.util.List.of(USER_ID.toString()));
        engineProperties.setCreateRecordEnabled(true);
        UUID requestId = UUID.randomUUID();
        StartRunResult accepted = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("帮我记一篇日记：隔离库回执测试")
                        .requestId(requestId).build());
        AgentRun run = accepted.run();
        when(repository.findById(run.getRunId())).thenReturn(run);
        when(repository.lockRun(run.getRunId())).thenReturn(run);
        when(repository.complete(any(AgentRun.class), any(), any(), any(), any(), anyInt(), anyInt(),
                anyLong(), anyLong(), any())).thenReturn(true);
        when(sessionMapper.selectById(run.getSessionId())).thenReturn(ChatSession.builder()
                .id(run.getSessionId()).userId(USER_ID).title("隔离库回执测试").build());
        AgentCommandResponse receipt = new AgentCommandResponse(UUID.randomUUID(), "success", false,
                new AgentCommandResponse.AgentCreatedRecord(731, "record_only",
                        java.time.OffsetDateTime.parse("2026-10-20T12:00:00+08:00")));
        when(commandService.findReceiptForRun(USER_ID, run.getRunId()))
                .thenReturn(java.util.Optional.of(receipt));
        when(agentGrpcClient.buildRequest(any(ChatInvocation.class), any(AgentRun.class)))
                .thenReturn(AgentProto.AgentRunRequest.newBuilder().build());
        when(agentGrpcClient.run(any(AgentProto.AgentRunRequest.class), any()))
                .thenAnswer(invocation -> {
                    StreamObserver<AgentProto.AgentEvent> observer = invocation.getArgument(1);
                    observer.onError(new RuntimeException("Python stream disconnected after command commit"));
                    return (AgentGrpcClient.CancellableCall) () -> { };
                });
        SseEmitter emitter = mock(SseEmitter.class);

        service.dispatch(accepted, emitter);

        assertEquals("completed", run.getStatus());
        assertEquals("action_success", run.getOutcome());
        assertEquals("write_completed", run.getStopReason());
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "assistant".equals(message.getRole())
                        && "已保存这条日记；目前是仅记录状态".equals(message.getContent())
                        && message.getCreatedRecords() != null && message.getCreatedRecords().size() == 1));
        // One meta, one receipt-backed delta, and one done; no contradictory error or duplicate delta.
        verify(emitter, times(3)).send(any(SseEventBuilder.class));
    }

    @Test
    void cancellationBeforeAgentDispatchBecomesTerminalWithoutStartingPython() throws Exception {
        engineProperties.setEngine("graph_v2");
        engineProperties.setGraphEnabledUserIds(java.util.List.of(USER_ID.toString()));
        UUID requestId = UUID.randomUUID();
        StartRunResult accepted = service.startOrFindRun(USER_ID,
                ChatRequestDTO.builder().question("取消前尚未进入Python").requestId(requestId).build());
        AgentRun run = accepted.run();
        run.setStatus("cancel_requested");
        when(repository.findById(run.getRunId())).thenReturn(run);
        when(repository.markCancelled(run.getRunId(), "cancelled_before_dispatch")).thenReturn(true);
        SseEmitter emitter = mock(SseEmitter.class);

        service.dispatch(accepted, emitter);

        assertEquals("cancelled", run.getStatus());
        assertEquals("cancelled_before_dispatch", run.getStopReason());
        verify(repository).markCancelled(run.getRunId(), "cancelled_before_dispatch");
        verify(agentGrpcClient, never()).buildRequest(any(ChatInvocation.class), any(AgentRun.class));
        verify(agentGrpcClient, never()).run(any(AgentProto.AgentRunRequest.class), any());
        verify(emitter, times(2)).send(any(SseEventBuilder.class));
    }
}
