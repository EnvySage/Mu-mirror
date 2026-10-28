package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.config.ChatEngineProperties;
import org.xianshen.mumirrorb.grpc.AgentGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;
import org.xianshen.mumirrorb.pojo.VO.RunStatusVO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.AssistantContextService;
import org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.tools.AuditService;
import org.xianshen.mumirrorb.tools.ToolOrchestrator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns request acceptance, per-turn engine selection and the only assistant persistence exit. */
@Slf4j
@Service
public class AgentRunService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int TITLE_MAX_LEN = 50;

    private final AgentRunMapper runMapper;
    private final AgentCommandService commandService;
    private final ChatSessionMapper sessionMapper;
    private final ConversationHistoryMapper historyMapper;
    private final ChatService chatService;
    private final AssistantContextService assistantContextService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.xianshen.mumirrorb.service.TarotChatContextService tarotChatContext;
    private final DailyChatSessionResolver dailySessionResolver;
    private final AgentGrpcClient agentGrpcClient;
    private final AgentLifecycleService lifecycle;
    private final GlossaryService glossaryService;
    private final ChatEngineProperties engineProperties;
    private final AgentProperties agentProperties;
    private final AuditService auditService;
    private final ToolOrchestrator toolOrchestrator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor shadowExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "agent-shadow-run");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicReference<ShadowExecution> activeShadow = new AtomicReference<>();

    public AgentRunService(AgentRunMapper runMapper,
                          AgentCommandService commandService,
                          ChatSessionMapper sessionMapper,
                          ConversationHistoryMapper historyMapper,
                          ChatService chatService,
                          AssistantContextService assistantContextService,
                          DailyChatSessionResolver dailySessionResolver,
                          AgentGrpcClient agentGrpcClient,
                          AgentLifecycleService lifecycle,
                          GlossaryService glossaryService,
                          ChatEngineProperties engineProperties,
                          AgentProperties agentProperties,
                          AuditService auditService,
                          ToolOrchestrator toolOrchestrator,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.runMapper = runMapper;
        this.commandService = commandService;
        this.sessionMapper = sessionMapper;
        this.historyMapper = historyMapper;
        this.chatService = chatService;
        this.assistantContextService = assistantContextService;
        this.dailySessionResolver = dailySessionResolver;
        this.agentGrpcClient = agentGrpcClient;
        this.lifecycle = lifecycle;
        this.glossaryService = glossaryService;
        this.engineProperties = engineProperties;
        this.agentProperties = agentProperties;
        this.auditService = auditService;
        this.toolOrchestrator = toolOrchestrator;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Atomically accepts one request, its run row and exactly one user message. */
    public StartRunResult startOrFindRun(UUID userId, ChatRequestDTO dto) {
        UUID requestId = dto.getRequestId() == null ? UUID.randomUUID() : dto.getRequestId();
        dto.setRequestId(requestId);
        UUID externalSessionId = dto.getSessionId();
        String question = dto.getQuestion();
        String requestHash = sha256(question.length() + ":" + question + ":"
                + (externalSessionId == null ? "<null>" : externalSessionId));
        try {
            StartRunResult result = transactions.execute(status -> acceptInTransaction(
                    userId, requestId, externalSessionId, question, requestHash));
            if (result != null && result.created()) {
                lifecycle.startLease(result.run().getRunId(), () -> onLeaseLost(result.run().getRunId()));
            }
            return result;
        } catch (DataIntegrityViolationException conflict) {
            AgentRun sameRequest = runMapper.findByClientRequestId(userId, requestId);
            if (sameRequest != null) return existingRequest(sameRequest, requestHash);
            if (externalSessionId != null && runMapper.findActiveBySession(externalSessionId) != null) {
                throw new AgentRunConflictException("该会话已有一轮对话正在运行");
            }
            throw conflict;
        }
    }

    private StartRunResult acceptInTransaction(UUID userId, UUID requestId, UUID externalSessionId,
                                               String question, String requestHash) {
        AgentRun existing = runMapper.findByClientRequestId(userId, requestId);
        if (existing != null) return existingRequest(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        String title = question.length() > TITLE_MAX_LEN ? question.substring(0, TITLE_MAX_LEN) : question;
        ChatSession session = dailySessionResolver.resolveSend(
                userId, externalSessionId, title, now.toLocalDate());

        UUID runId = UUID.randomUUID();
        String engine = engineProperties.graphEnabled(userId) ? "graph_v2" : "legacy";
        boolean shadowSampled = "legacy".equals(engine) && engineProperties.sampleShadowRun();
        AgentRun run = AgentRun.builder().runId(runId).userId(userId).sessionId(session.getId())
                .clientRequestId(requestId).requestHash(requestHash).questionHash(sha256(question))
                .engine(engine).mode("live").status("running").startedAt(now)
                .leaseExpiresAt(now.plusSeconds(agentProperties.getLeaseSeconds()))
                .planRounds(0).toolCallCount(0).lastEventSeq(0L).glossaryHitsApplied(false)
                .metricsJson(shadowSampled ? "{\"shadow_sampled\":true}" : "{}").build();
        runMapper.insertRun(run);

        ConversationHistory userMessage = ConversationHistory.builder().runId(runId)
                .sessionId(session.getId()).userId(userId).role("user").content(question)
                .createdAt(now).isFallback(false).build();
        historyMapper.insert(userMessage);
        runMapper.linkUserMessage(runId, userMessage.getId());
        run.setUserMessageId(userMessage.getId());
        org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext assistantContext =
                assistantContextService.build(userId, session.getId(), question);
        if (tarotChatContext != null) assistantContext = tarotChatContext.attach(userId, session.getId(), runId, question, assistantContext, false);
        ChatInvocation invocation = new ChatInvocation(userId, session.getId(), runId,
                requestId, userMessage.getId(), question, engine, assistantContext,
                contextSourceMessageIds(assistantContext));
        return new StartRunResult(run, invocation, true);
    }

    private StartRunResult existingRequest(AgentRun existing, String requestHash) {
        if (!existing.getRequestHash().trim().equals(requestHash)) {
            throw new AgentRunConflictException("requestId 已用于不同的问题或会话");
        }
        if (existing.getUserMessageId() == null
                || historyMapper.selectById(existing.getUserMessageId()) == null
                || sessionMapper.selectById(existing.getSessionId()) == null) {
            throw new RunGoneException("该请求对应的会话已删除，不能重新执行");
        }
        return new StartRunResult(existing, null, false);
    }

    /** Dispatches a newly accepted request exactly once; duplicates only replay stored state. */
    @Async("agentRunExecutor")
    public void dispatch(StartRunResult start, SseEmitter emitter) {
        if (!start.created()) {
            replayExisting(start.run(), emitter);
            return;
        }
        AgentRun run = runMapper.findByRunId(start.run().getRunId());
        if (run != null && finishCancelledBeforeDispatch(run, start.invocation(), emitter, true)) return;
        if (run == null || !"running".equals(run.getStatus())
                || run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            if (run != null && "running".equals(run.getStatus())) runMapper.markAbandoned(run.getRunId());
            completeEmitter(emitter);
            return;
        }
        ChatInvocation invocation = start.invocation();
        sendMeta(emitter, invocation);
        if ("graph_v2".equals(run.getEngine())) {
            runGraph(run, invocation, emitter);
            return;
        }
        ChatFinalResult legacyResult;
        try {
            legacyResult = chatService.runLegacy(invocation, emitter);
        } catch (Exception error) {
            log.error("Legacy chat failed for run {}", run.getRunId(), error);
            legacyResult = ChatFinalResult.fallback("暂时无法回答", "legacy_error", "");
            sendEvent(emitter, "delta", Map.of("content", legacyResult.answerText()));
        }
        finish(run, invocation, legacyResult, emitter);
        scheduleShadow(run, invocation);
    }

    private void runGraph(AgentRun run, ChatInvocation invocation, SseEmitter emitter) {
        cancelShadowForForeground();
        AgentEventAdapter adapter = new AgentEventAdapter(run, invocation, emitter, auditService,
                toolOrchestrator, objectMapper, false);
        AtomicReference<AgentGrpcClient.CancellableCall> callRef = new AtomicReference<>();
        AtomicBoolean cancelPending = new AtomicBoolean(false);
        ActiveExecution execution = new ActiveExecution(() -> {
            cancelPending.set(true);
            AgentGrpcClient.CancellableCall call = callRef.get();
            if (call != null) call.cancel();
        });
        activeExecutions.put(run.getRunId(), execution);
        AgentRun current = runMapper.findByRunId(run.getRunId());
        if (current != null && finishCancelledBeforeDispatch(current, invocation, emitter, false)) {
            activeExecutions.remove(run.getRunId(), execution);
            return;
        }
        if (current == null || !"running".equals(current.getStatus())) {
            activeExecutions.remove(run.getRunId(), execution);
            completeEmitter(emitter);
            return;
        }
        AtomicBoolean finalized = new AtomicBoolean(false);
        try {
            AgentProto.AgentRunRequest request = agentGrpcClient.buildRequest(invocation, run);
            AgentGrpcClient.CancellableCall call = agentGrpcClient.run(request, new StreamObserver<>() {
                @Override
                public void onNext(AgentProto.AgentEvent event) {
                    try {
                        adapter.accept(event);
                        if (runMapper.recordEventSequence(run.getRunId(), adapter.lastSequence()) != 1) {
                            throw new AgentEventAdapter.AgentProtocolException("Agent event sequence was rejected");
                        }
                    } catch (Exception error) {
                        adapter.protocolFailure("AGENT_PROTOCOL_ERROR");
                        if (clientDisconnected(error)) {
                            runMapper.requestCancel(run.getRunId(), invocation.userId());
                            lifecycle.stopLease(run.getRunId());
                        }
                        execution.cancel();
                        if (finalized.compareAndSet(false, true)) finish(run, invocation, adapter.result(), emitter);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (finalized.compareAndSet(false, true)) {
                        finish(run, invocation, adapter.failTransport(error), emitter);
                    }
                }

                @Override
                public void onCompleted() {
                    if (finalized.compareAndSet(false, true)) {
                        finish(run, invocation, adapter.finishTransport(), emitter);
                    }
                }
            });
            callRef.set(call);
            if (cancelPending.get()) call.cancel();
        } catch (Exception error) {
            log.error("graph_v2 dispatch failed for run {}", run.getRunId(), error);
            if (finalized.compareAndSet(false, true)) {
                finish(run, invocation, ChatFinalResult.failed(
                        "暂时无法回答", "AGENT_UNAVAILABLE", "agent_unavailable", ""), emitter);
            }
        }
    }

    /** Close the race where client cancellation arrives before the graph call is registered. */
    private boolean finishCancelledBeforeDispatch(AgentRun run, ChatInvocation invocation,
                                                  SseEmitter emitter, boolean includeMeta) {
        if (!"cancel_requested".equals(run.getStatus())) return false;
        boolean marked = runMapper.markCancelled(run.getRunId(), "cancelled_before_dispatch") == 1;
        if (marked) {
            run.setStatus("cancelled");
            run.setStopReason("cancelled_before_dispatch");
            if (includeMeta && invocation != null) sendMeta(emitter, invocation);
            sendDone(emitter, run, null, "", "cancelled");
            completeEmitter(emitter);
            return true;
        }

        // Another terminal path won the race; replay its stored truth rather than dispatching again.
        AgentRun latest = runMapper.findByRunId(run.getRunId());
        if (latest != null) replayExisting(latest, emitter);
        else completeEmitter(emitter);
        return true;
    }

    private void replayExisting(AgentRun run, SseEmitter emitter) {
        ChatInvocation invocation = new ChatInvocation(run.getUserId(), run.getSessionId(), run.getRunId(),
                run.getClientRequestId(), run.getUserMessageId() == null ? 0 : run.getUserMessageId(),
                "", run.getEngine());
        sendMeta(emitter, invocation);
        java.util.Optional<AgentCommandResponse> receipt = commandService.findReceiptForRun(run.getUserId(), run.getRunId());
        if (receipt != null && receipt.isPresent()) {
            sendReceiptSuccess(run, emitter, receipt.get());
            return;
        }
        if ("running".equals(run.getStatus()) || "cancel_requested".equals(run.getStatus())) {
            sendEvent(emitter, "error", Map.of("code", "RUN_IN_PROGRESS", "runId", run.getRunId().toString()));
            sendDone(emitter, run, null, "", run.getStatus());
            completeEmitter(emitter);
            return;
        }
        ConversationHistory assistant = run.getAssistantMessageId() == null
                ? null : historyMapper.selectById(run.getAssistantMessageId());
        if (assistant != null) {
            if (assistant.getContent() != null && !assistant.getContent().isEmpty()) {
                sendEvent(emitter, "delta", Map.of("content", assistant.getContent()));
            }
            if (assistant.getSources() != null && !assistant.getSources().isEmpty()) sendEvent(emitter, "sources", assistant.getSources());
            if (assistant.getVaultRefs() != null && !assistant.getVaultRefs().isEmpty()) sendEvent(emitter, "vault_refs", assistant.getVaultRefs());
        }
        sendDone(emitter, run, assistant, routeFrom(run.getMetricsJson()), run.getStatus());
        completeEmitter(emitter);
    }

    public void cancelRun(UUID runId, UUID userId, String reason) {
        if (runMapper.requestCancel(runId, userId) != 1) return;
        lifecycle.stopLease(runId);
        ActiveExecution execution = activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        log.info("Agent run cancellation requested: runId={}, reason={}", runId, reason);
    }

    public void cancelActiveForSession(UUID sessionId, UUID userId) {
        AgentRun active = runMapper.findActiveBySession(sessionId);
        if (active == null || !userId.equals(active.getUserId())) return;
        if (runMapper.requestCancelForSession(sessionId, userId) == 1) {
            lifecycle.stopLease(active.getRunId());
            ActiveExecution execution = activeExecutions.get(active.getRunId());
            if (execution != null) execution.cancel();
            runMapper.markCancelled(active.getRunId(), "session_deleted");
        }
    }

    public RunStatusVO getRun(UUID runId, UUID userId) {
        AgentRun run = runMapper.findByRunId(runId);
        if (run == null || !userId.equals(run.getUserId())) throw new RunNotFoundException();
        if (run.getUserMessageId() == null || sessionMapper.selectById(run.getSessionId()) == null) {
            throw new RunGoneException("该请求对应的会话已删除");
        }
        ConversationHistory assistant = run.getAssistantMessageId() == null
                ? null : historyMapper.selectById(run.getAssistantMessageId());
        java.util.Optional<AgentCommandResponse> receipt = commandService.findReceiptForRun(userId, runId);
        if (receipt != null && receipt.isPresent()) {
            AgentCommandResponse value = receipt.get();
            ChatSessionVO.MessageVO receiptMessage = ChatSessionVO.MessageVO.builder()
                    .id(assistant == null ? null : assistant.getId()).role("assistant")
                    .content("已保存这条日记；目前是仅记录状态")
                    .createdRecords(List.of(createdRecordMap(value)))
                    .createdAt(assistant == null ? value.record().createdAt() : assistant.getCreatedAt())
                    .build();
            return RunStatusVO.builder().runId(run.getRunId()).sessionId(run.getSessionId())
                    .status("completed").outcome("action_success").stopReason("write_completed")
                    .assistant(receiptMessage).build();
        }
        ChatSessionVO.MessageVO message = assistant == null ? null : ChatSessionVO.MessageVO.builder()
                .id(assistant.getId()).role(assistant.getRole()).content(assistant.getContent())
                .sources(assistant.getSources()).toolsUsed(assistant.getToolsUsed())
                .vaultRefs(assistant.getVaultRefs()).createdRecords(assistant.getCreatedRecords())
                .createdAt(assistant.getCreatedAt()).build();
        return RunStatusVO.builder().runId(run.getRunId()).sessionId(run.getSessionId())
                .status(run.getStatus()).outcome(run.getOutcome()).stopReason(run.getStopReason())
                .errorCode(run.getErrorCode()).assistant(message).build();
    }

    private void onLeaseLost(UUID runId) {
        runMapper.markAbandoned(runId);
        ActiveExecution execution = activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        log.warn("Agent run lease lost and execution cancelled: runId={}", runId);
    }

    void scheduleShadow(AgentRun parent, ChatInvocation parentInvocation) {
        if (!"legacy".equals(parent.getEngine()) || !"live".equals(parent.getMode())
                || !shadowSampled(parent.getMetricsJson())
                || AgentWriteGrantService.authorizedContent(parentInvocation.question()) != null) {
            return;
        }
        AgentRun latest;
        try {
            latest = runMapper.findByRunId(parent.getRunId());
        } catch (Exception error) {
            log.info("Skipping sampled shadow run because the parent state could not be checked: parentRunId={}",
                    parent.getRunId());
            return;
        }
        if (latest == null || !("completed".equals(latest.getStatus()) || "failed".equals(latest.getStatus()))) {
            return;
        }
        try {
            shadowExecutor.execute(() -> runShadow(parent, parentInvocation));
        } catch (RejectedExecutionException saturated) {
            log.info("Skipping sampled shadow run because its single execution slot is busy: parentRunId={}",
                    parent.getRunId());
        }
    }

    private void runShadow(AgentRun parent, ChatInvocation parentInvocation) {
        boolean foregroundGraphActive;
        try {
            foregroundGraphActive = !activeExecutions.isEmpty() || Boolean.TRUE.equals(runMapper.hasActiveForegroundGraph());
        } catch (Exception error) {
            log.info("Skipping sampled shadow run because foreground capacity could not be checked: parentRunId={}",
                    parent.getRunId());
            return;
        }
        if (foregroundGraphActive) {
            log.info("Skipping sampled shadow run while a foreground graph run is active: parentRunId={}",
                    parent.getRunId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        UUID shadowRunId = UUID.randomUUID();
        UUID shadowRequestId = UUID.randomUUID();
        AgentRun shadow = AgentRun.builder().runId(shadowRunId).userId(parent.getUserId())
                .sessionId(parent.getSessionId()).clientRequestId(shadowRequestId)
                .requestHash(sha256(parent.getRequestHash() + ":shadow:" + shadowRunId))
                .questionHash(parent.getQuestionHash()).engine("graph_v2").mode("shadow")
                .parentRunId(parent.getRunId()).status("running").startedAt(now)
                .leaseExpiresAt(now.plusNanos((agentProperties.getTotalBudgetMs() + 10_000L) * 1_000_000L))
                .userMessageId(parentInvocation.userMessageId()).planRounds(0).toolCallCount(0)
                .lastEventSeq(0L).glossaryHitsApplied(false).metricsJson("{}").build();
        ChatInvocation invocation = new ChatInvocation(parentInvocation.userId(), parentInvocation.sessionId(),
                shadowRunId, shadowRequestId, parentInvocation.userMessageId(), parentInvocation.question(),
                "graph_v2", parentInvocation.assistantContext().toBuilder().clearDivination().build(), parentInvocation.contextSourceMessageIds());

        AtomicReference<AgentGrpcClient.CancellableCall> callRef = new AtomicReference<>();
        AtomicBoolean cancelPending = new AtomicBoolean(false);
        ActiveExecution execution = new ActiveExecution(() -> {
            cancelPending.set(true);
            AgentGrpcClient.CancellableCall call = callRef.get();
            if (call != null) call.cancel();
        });
        ShadowExecution state = new ShadowExecution(shadow, execution);
        if (!activeShadow.compareAndSet(null, state)) return;

        try {
            runMapper.insertRun(shadow);
        } catch (Exception error) {
            activeShadow.compareAndSet(state, null);
            log.warn("Could not create sampled shadow run for parent {}: {}", parent.getRunId(), error.getMessage());
            return;
        }
        boolean foregroundArrived;
        try {
            foregroundArrived = !activeExecutions.isEmpty() || Boolean.TRUE.equals(runMapper.hasActiveForegroundGraph());
        } catch (Exception error) {
            foregroundArrived = true;
        }
        if (cancelPending.get() || foregroundArrived) {
            try {
                runMapper.requestShadowCancel(shadowRunId, "foreground_priority");
            } catch (Exception error) {
                log.debug("Could not mark shadow skip before gRPC dispatch: runId={}", shadowRunId);
            }
            finishShadow(state, cancelledShadowResult());
            return;
        }

        AgentEventAdapter adapter = new AgentEventAdapter(shadow, invocation, null, auditService,
                toolOrchestrator, objectMapper, true);
        AtomicBoolean finalized = new AtomicBoolean(false);
        try {
            AgentProto.AgentRunRequest request = agentGrpcClient.buildRequest(invocation, shadow);
            if (cancelPending.get()) {
                finishShadow(state, cancelledShadowResult());
                return;
            }
            AgentGrpcClient.CancellableCall call = agentGrpcClient.run(request, new StreamObserver<>() {
                @Override
                public void onNext(AgentProto.AgentEvent event) {
                    try {
                        adapter.accept(event);
                    } catch (Exception error) {
                        adapter.protocolFailure("AGENT_PROTOCOL_ERROR");
                        execution.cancel();
                        if (finalized.compareAndSet(false, true)) finishShadow(state, adapter.result());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (finalized.compareAndSet(false, true)) finishShadow(state, adapter.failTransport(error));
                }

                @Override
                public void onCompleted() {
                    if (finalized.compareAndSet(false, true)) finishShadow(state, adapter.finishTransport());
                }
            });
            callRef.set(call);
            if (cancelPending.get()) call.cancel();
        } catch (Exception error) {
            log.info("Sampled shadow run could not start: runId={}, cause={}", shadowRunId, error.getMessage());
            if (finalized.compareAndSet(false, true)) {
                finishShadow(state, ChatFinalResult.failed("", "AGENT_UNAVAILABLE", "agent_unavailable", ""));
            }
        }
    }

    private void finishShadow(ShadowExecution execution, ChatFinalResult result) {
        ChatFinalResult finalResult = result == null
                ? ChatFinalResult.failed("", "EMPTY_RESULT", "empty_result", "") : result;
        if (!finalResult.createdRecords().isEmpty()
                || (finalResult.outcome() != null && finalResult.outcome().startsWith("action_"))) {
            finalResult = ChatFinalResult.failed("", "SHADOW_WRITE_BLOCKED", "shadow_write_blocked", "");
        }
        try {
            String shadowMetrics = metricsWithRoute(finalResult.metricsJson(), finalResult.route());
            boolean persisted = runMapper.completeShadow(execution.run().getRunId(), finalResult.status(),
                    finalResult.outcome(), finalResult.stopReason(), finalResult.errorCode(),
                    Math.max(0, finalResult.planRounds()), Math.max(0, finalResult.toolCallCount()),
                    Math.max(0, finalResult.lastEventSeq()),
                    shadowMetrics == null ? "{}" : shadowMetrics) == 1;
            if (!persisted) {
                log.info("Sampled shadow run finished after its state was no longer active: runId={}",
                        execution.run().getRunId());
            }
        } catch (Exception error) {
            log.warn("Could not persist sampled shadow metrics: runId={}, cause={}",
                    execution.run().getRunId(), error.getMessage());
        } finally {
            activeShadow.compareAndSet(execution, null);
            log.info("Sampled shadow run finished: runId={}, outcome={}, stopReason={}",
                    execution.run().getRunId(), finalResult.outcome(), finalResult.stopReason());
        }
    }

    void cancelShadowForForeground() {
        ShadowExecution shadow = activeShadow.get();
        if (shadow != null) {
            try {
                runMapper.requestShadowCancel(shadow.run().getRunId(), "foreground_priority");
            } catch (Exception error) {
                log.info("Could not mark shadow cancellation before foreground dispatch: runId={}",
                        shadow.run().getRunId());
            }
            shadow.execution().cancel();
        }
    }

    private boolean shadowSampled(String metricsJson) {
        try {
            Map<String, Object> metrics = objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return Boolean.TRUE.equals(metrics.get("shadow_sampled"));
        } catch (Exception ignored) {
            return false;
        }
    }

    @PreDestroy
    public void closeShadowExecutor() {
        ShadowExecution shadow = activeShadow.get();
        if (shadow != null) {
            try {
                runMapper.requestShadowCancel(shadow.run().getRunId(), "service_shutdown");
            } catch (Exception error) {
                log.debug("Could not mark shadow cancellation during shutdown: runId={}", shadow.run().getRunId());
            }
            shadow.execution().cancel();
        }
        shadowExecutor.shutdownNow();
    }

    private void finish(AgentRun originalRun, ChatInvocation invocation,
                        ChatFinalResult result, SseEmitter emitter) {
        if (result == null) result = ChatFinalResult.failed("暂时无法回答", "EMPTY_RESULT", "empty_result", "");
        final ChatFinalResult finalResult = result;
        try {
            CompletionCommit commit = transactions.execute(status -> completeRun(originalRun.getRunId(), finalResult,
                    invocation == null ? List.of() : invocation.contextSourceMessageIds()));
            lifecycle.stopLease(originalRun.getRunId());
            activeExecutions.remove(originalRun.getRunId());
            if (commit == null || !commit.persisted()) return;
            if (commit.assistant() != null
                    && !java.util.Objects.equals(commit.assistant().getContent(), finalResult.answerText())) {
                sendEvent(emitter, "delta", Map.of("content", commit.assistant().getContent()));
            } else if ("action_unknown".equals(finalResult.outcome()) && commit.assistant() != null) {
                sendEvent(emitter, "delta", Map.of("content", commit.assistant().getContent()));
            }
            if (commit.assistant() != null) {
                if (commit.assistant().getSources() != null && !commit.assistant().getSources().isEmpty()) {
                    sendEvent(emitter, "sources", commit.assistant().getSources());
                }
                if (commit.assistant().getVaultRefs() != null && !commit.assistant().getVaultRefs().isEmpty()) {
                    sendEvent(emitter, "vault_refs", commit.assistant().getVaultRefs());
                }
            }
            if ("failed".equals(commit.status()) || "cancelled".equals(commit.status())) {
                sendEvent(emitter, "error", Map.of("code", safe(commit.errorCode(), "RUN_FAILED"),
                        "partial", finalResult.partial(), "message", "本轮对话未能完整完成"));
            }
            sendDone(emitter, commit.run(), commit.assistant(), finalResult.route(), commit.status());
            completeEmitter(emitter);
        } catch (Exception error) {
            lifecycle.stopLease(originalRun.getRunId());
            activeExecutions.remove(originalRun.getRunId());
            log.error("Could not persist final result for run {}", originalRun.getRunId(), error);
            sendEvent(emitter, "error", Map.of("code", "PERSISTENCE_ERROR", "partial", finalResult.partial()));
            sendEvent(emitter, "done", Map.of("runId", originalRun.getRunId().toString(),
                    "status", "failed", "outcome", "answer", "errorCode", "PERSISTENCE_ERROR"));
            completeEmitter(emitter);
        }
    }

    private CompletionCommit completeRun(UUID runId, ChatFinalResult result, List<Long> contextSourceMessageIds) {
        AgentRun run = runMapper.lockByRunId(runId);
        if (run == null || isTerminal(run.getStatus())) return new CompletionCommit(false, run, null, null, "", false);
        if (run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            runMapper.markAbandoned(runId);
            return new CompletionCommit(false, run, null, null, "", false);
        }
        if (sessionMapper.selectById(run.getSessionId()) == null) {
            runMapper.markAbandoned(runId);
            return new CompletionCommit(false, run, null, null, "", false);
        }
        java.util.Optional<AgentCommandResponse> receipt = commandService.findReceiptForRun(run.getUserId(), runId);
        boolean receiptConfirmed = receipt != null && receipt.isPresent();
        if (receiptConfirmed) {
            result = actionSuccessFromReceipt(result, receipt.get());
        } else if ("action_success".equals(result.outcome()) || !result.createdRecords().isEmpty()) {
            result = new ChatFinalResult(
                    "这次保存结果暂时无法核对，我不能确认是否已创建。请稍后查看本轮状态。",
                    List.of(), result.toolsUsed(), List.of(), List.of(), false, false,
                    "completed", "action_unknown", "structured", "write_unknown", null,
                    result.planRounds(), result.toolCallCount(), result.lastEventSeq(),
                    result.metricsJson(), result.glossaryHitIds());
        }
        String status = receiptConfirmed ? "completed"
                : ("cancel_requested".equals(run.getStatus()) ? "cancelled" : result.status());
        String stopReason = "cancelled".equals(status) ? "cancelled" : result.stopReason();
        String errorCode = result.errorCode();
        if ("cancelled".equals(status) && (errorCode == null || errorCode.isBlank())) errorCode = "CANCELLED";
        ConversationHistory assistant = ConversationHistory.builder().runId(runId)
                .sessionId(run.getSessionId()).userId(run.getUserId()).role("assistant")
                .content(result.answerText()).sources(result.sources()).toolsUsed(result.toolsUsed())
                .vaultRefs(result.vaultRefs()).createdRecords(result.createdRecords())
                .isFallback(result.fallback()).contextSourceMessageIds(objectMapper.valueToTree(contextSourceMessageIds))
                .createdAt(OffsetDateTime.now(ZONE)).build();
        historyMapper.insert(assistant);
        ChatSession session = sessionMapper.selectById(run.getSessionId());
        if (session == null || !run.getUserId().equals(session.getUserId())) {
            throw new RunGoneException("会话已删除，无法保存本轮回答");
        }
        session.setUpdatedAt(OffsetDateTime.now(ZONE));
        sessionMapper.updateById(session);
        if ("graph_v2".equals(run.getEngine()) && "live".equals(run.getMode())
                && "completed".equals(status) && !result.glossaryHitIds().isEmpty()) {
            glossaryService.recordQueryHits(run.getUserId(), runId, result.glossaryHitIds());
        }
        String runMetrics = metricsWithRoute(result.metricsJson(), result.route());
        if (shadowSampled(run.getMetricsJson())) runMetrics = withShadowSampled(runMetrics);
        boolean updated = runMapper.complete(run.getRunId(), run.getUserId(), status, result.outcome(),
                stopReason, errorCode, Math.max(0, result.planRounds()), Math.max(0, result.toolCallCount()),
                Math.max(0, result.lastEventSeq()), assistant.getId(),
                runMetrics == null ? "{}" : runMetrics) == 1;
        if (!updated) throw new IllegalStateException("Agent run left its active lease before completion");
        run.setStatus(status);
        run.setOutcome(result.outcome());
        run.setStopReason(stopReason);
        run.setErrorCode(errorCode);
        run.setAssistantMessageId(assistant.getId());
        return new CompletionCommit(true, run, assistant, status, errorCode, receiptConfirmed);
    }

    private ChatFinalResult actionSuccessFromReceipt(ChatFinalResult original, AgentCommandResponse receipt) {
        return new ChatFinalResult("已保存这条日记；目前是仅记录状态", List.of(),
                original.toolsUsed(), List.of(), List.of(createdRecordMap(receipt)), false, false,
                "completed", "action_success", "structured", "write_completed", null,
                original.planRounds(), original.toolCallCount(), original.lastEventSeq(),
                original.metricsJson(), original.glossaryHitIds());
    }

    private Map<String, Object> createdRecordMap(AgentCommandResponse receipt) {
        return Map.of("record_id", receipt.record().id(), "status", receipt.record().status(),
                "created_at", receipt.record().createdAt().toString());
    }

    private List<Long> contextSourceMessageIds(org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext context) {
        if (context == null || context.getMemoriesCount() == 0) return List.of();
        return context.getMemoriesList().stream()
                .flatMap(memory -> memory.getSourceMessageIdsList().stream())
                .distinct().toList();
    }

    private void sendReceiptSuccess(AgentRun run, SseEmitter emitter, AgentCommandResponse receipt) {
        sendEvent(emitter, "delta", Map.of("content", "已保存这条日记；目前是仅记录状态"));
        sendEvent(emitter, "done", Map.of(
                "sessionId", run.getSessionId().toString(),
                "runId", run.getRunId().toString(),
                "requestId", run.getClientRequestId().toString(),
                "engine", run.getEngine(), "status", "completed", "outcome", "action_success",
                "route", "STRUCTURED", "fallback", false,
                "created_records", List.of(createdRecordMap(receipt)), "stop_reason", "write_completed"));
        completeEmitter(emitter);
    }

    private void sendMeta(SseEmitter emitter, ChatInvocation invocation) {
        sendEvent(emitter, "meta", Map.of("sessionId", invocation.sessionId().toString(),
                "runId", invocation.runId().toString(), "requestId", invocation.clientRequestId().toString(),
                "sourceMessageId", invocation.userMessageId(), "engine", invocation.engine()));
    }

    private void sendDone(SseEmitter emitter, AgentRun run, ConversationHistory assistant,
                          String route, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", run.getSessionId().toString());
        payload.put("runId", run.getRunId().toString());
        payload.put("requestId", run.getClientRequestId().toString());
        payload.put("engine", run.getEngine());
        payload.put("status", status);
        payload.put("outcome", run.getOutcome() == null ? "answer" : run.getOutcome());
        payload.put("route", route == null ? "" : route);
        payload.put("fallback", assistant != null && Boolean.TRUE.equals(assistant.getIsFallback()));
        payload.put("created_records", assistant == null || assistant.getCreatedRecords() == null
                ? List.of() : assistant.getCreatedRecords());
        if (run.getStopReason() != null) payload.put("stop_reason", run.getStopReason());
        if (run.getErrorCode() != null) payload.put("error_code", run.getErrorCode());
        sendEvent(emitter, "done", payload);
    }

    private String metricsWithRoute(String metricsJson, String route) {
        try {
            Map<String, Object> metrics = objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            if (route != null && !route.isBlank()) metrics.put("route", route);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String withShadowSampled(String metricsJson) {
        try {
            Map<String, Object> metrics = objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            metrics.put("shadow_sampled", true);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception ignored) {
            return "{\"shadow_sampled\":true}";
        }
    }

    private ChatFinalResult cancelledShadowResult() {
        return new ChatFinalResult("", List.of(), List.of(), List.of(), List.of(), false, false,
                "cancelled", "answer", "", "foreground_priority", "CANCELLED",
                0, 0, 0L, "{}", List.of());
    }

    private String routeFrom(String metricsJson) {
        try {
            Map<String, Object> metrics = objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return String.valueOf(metrics.getOrDefault("route", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data)));
        } catch (Exception error) {
            log.debug("SSE {} send failed: {}", name, error.getMessage());
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) { }
        }
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "abandoned".equals(status);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean clientDisconnected(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("SSE client disconnected")) return true;
        }
        return false;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record CompletionCommit(boolean persisted, AgentRun run, ConversationHistory assistant,
                                    String status, String errorCode, boolean receiptConfirmed) { }

    private record ShadowExecution(AgentRun run, ActiveExecution execution) { }

    private static final class ActiveExecution {
        private final Runnable cancelAction;
        private ActiveExecution(Runnable cancelAction) { this.cancelAction = cancelAction; }
        private void cancel() { if (cancelAction != null) cancelAction.run(); }
    }
}
