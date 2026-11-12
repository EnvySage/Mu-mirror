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

import static org.xianshen.mumirrorb.agent.AgentRunService.*;

/** AgentRunRequests keeps the service entry points and transaction boundaries in AgentRunService. */
final class AgentRunRequests {
    private final AgentRunService owner;
    AgentRunRequests(AgentRunService owner) { this.owner=owner; }


    /** Atomically accepts one request, its run row and exactly one user message. */
    public StartRunResult startOrFindRun(UUID userId, ChatRequestDTO dto) {
        UUID requestId = dto.getRequestId() == null ? UUID.randomUUID() : dto.getRequestId();
        dto.setRequestId(requestId);
        UUID externalSessionId = dto.getSessionId();
        String question = dto.getQuestion();
        String requestHash = owner.sha256(question.length() + ":" + question + ":"
                + (externalSessionId == null ? "<null>" : externalSessionId));
        try {
            StartRunResult result = owner.transactions.execute(status -> owner.acceptInTransaction(
                    userId, requestId, externalSessionId, question, requestHash));
            if (result != null && result.created()) {
                owner.lifecycle.startLease(result.run().getRunId(), () -> owner.onLeaseLost(result.run().getRunId()));
            }
            return result;
        } catch (DataIntegrityViolationException conflict) {
            AgentRun sameRequest = owner.runMapper.findByClientRequestId(userId, requestId);
            if (sameRequest != null) return owner.existingRequest(sameRequest, requestHash);
            if (externalSessionId != null && owner.runMapper.findActiveBySession(externalSessionId) != null) {
                throw new AgentRunConflictException("该会话已有一轮对话正在运行");
            }
            throw conflict;
        }
    }



    protected StartRunResult acceptInTransaction(UUID userId, UUID requestId, UUID externalSessionId,
                                               String question, String requestHash) {
        AgentRun existing = owner.runMapper.findByClientRequestId(userId, requestId);
        if (existing != null) return owner.existingRequest(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(owner.ZONE);
        String title = question.length() > owner.TITLE_MAX_LEN ? question.substring(0, owner.TITLE_MAX_LEN) : question;
        ChatSession session = owner.dailySessionResolver.resolveSend(
                userId, externalSessionId, title, now.toLocalDate());

        UUID runId = UUID.randomUUID();
        String engine = owner.engineProperties.graphEnabled(userId) ? "graph_v2" : "legacy";
        boolean shadowSampled = "legacy".equals(engine) && owner.engineProperties.sampleShadowRun();
        AgentRun run = AgentRun.builder().runId(runId).userId(userId).sessionId(session.getId())
                .clientRequestId(requestId).requestHash(requestHash).questionHash(owner.sha256(question))
                .engine(engine).mode("live").status("running").startedAt(now)
                .leaseExpiresAt(now.plusSeconds(owner.agentProperties.getLeaseSeconds()))
                .planRounds(0).toolCallCount(0).lastEventSeq(0L).glossaryHitsApplied(false)
                .metricsJson(shadowSampled ? "{\"shadow_sampled\":true}" : "{}").build();
        owner.runMapper.insertRun(run);

        ConversationHistory userMessage = ConversationHistory.builder().runId(runId)
                .sessionId(session.getId()).userId(userId).role("user").content(question)
                .createdAt(now).isFallback(false).build();
        owner.historyMapper.insert(userMessage);
        owner.runMapper.linkUserMessage(runId, userMessage.getId());
        run.setUserMessageId(userMessage.getId());
        org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext assistantContext =
                owner.assistantContextService.build(userId, session.getId(), question);
        if (owner.tarotChatContext != null) assistantContext = owner.tarotChatContext.attach(userId, session.getId(), runId, question, assistantContext, false);
        ChatInvocation invocation = new ChatInvocation(userId, session.getId(), runId,
                requestId, userMessage.getId(), question, engine, assistantContext,
                owner.contextSourceMessageIds(assistantContext));
        return new StartRunResult(run, invocation, true);
    }



    protected StartRunResult existingRequest(AgentRun existing, String requestHash) {
        if (!existing.getRequestHash().trim().equals(requestHash)) {
            throw new AgentRunConflictException("requestId 已用于不同的问题或会话");
        }
        if (existing.getUserMessageId() == null
                || owner.historyMapper.selectById(existing.getUserMessageId()) == null
                || owner.sessionMapper.selectById(existing.getSessionId()) == null) {
            throw new RunGoneException("该请求对应的会话已删除，不能重新执行");
        }
        return new StartRunResult(existing, null, false);
    }



    /** Dispatches a newly accepted request exactly once; duplicates only replay stored state. */
    @Async("agentRunExecutor")
    public void dispatch(StartRunResult start, SseEmitter emitter) {
        if (!start.created()) {
            owner.replayExisting(start.run(), emitter);
            return;
        }
        AgentRun run = owner.runMapper.findByRunId(start.run().getRunId());
        if (run != null && owner.finishCancelledBeforeDispatch(run, start.invocation(), emitter, true)) return;
        if (run == null || !"running".equals(run.getStatus())
                || run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            if (run != null && "running".equals(run.getStatus())) owner.runMapper.markAbandoned(run.getRunId());
            owner.completeEmitter(emitter);
            return;
        }
        ChatInvocation invocation = start.invocation();
        owner.sendMeta(emitter, invocation);
        if ("graph_v2".equals(run.getEngine())) {
            owner.runGraph(run, invocation, emitter);
            return;
        }
        ChatFinalResult legacyResult;
        try {
            legacyResult = owner.chatService.runLegacy(invocation, emitter);
        } catch (Exception error) {
            owner.log.error("Legacy chat failed for run {}", run.getRunId(), error);
            legacyResult = ChatFinalResult.fallback("暂时无法回答", "legacy_error", "");
            owner.sendEvent(emitter, "delta", Map.of("content", legacyResult.answerText()));
        }
        owner.finish(run, invocation, legacyResult, emitter);
        owner.scheduleShadow(run, invocation);
    }



    protected void runGraph(AgentRun run, ChatInvocation invocation, SseEmitter emitter) {
        owner.cancelShadowForForeground();
        AgentEventAdapter adapter = new AgentEventAdapter(run, invocation, emitter, owner.auditService,
                owner.toolOrchestrator, owner.objectMapper, false);
        AtomicReference<AgentGrpcClient.CancellableCall> callRef = new AtomicReference<>();
        AtomicBoolean cancelPending = new AtomicBoolean(false);
        ActiveExecution execution = new ActiveExecution(() -> {
            cancelPending.set(true);
            AgentGrpcClient.CancellableCall call = callRef.get();
            if (call != null) call.cancel();
        });
        owner.activeExecutions.put(run.getRunId(), execution);
        AgentRun current = owner.runMapper.findByRunId(run.getRunId());
        if (current != null && owner.finishCancelledBeforeDispatch(current, invocation, emitter, false)) {
            owner.activeExecutions.remove(run.getRunId(), execution);
            return;
        }
        if (current == null || !"running".equals(current.getStatus())) {
            owner.activeExecutions.remove(run.getRunId(), execution);
            owner.completeEmitter(emitter);
            return;
        }
        AtomicBoolean finalized = new AtomicBoolean(false);
        try {
            AgentProto.AgentRunRequest request = owner.agentGrpcClient.buildRequest(invocation, run);
            AgentGrpcClient.CancellableCall call = owner.agentGrpcClient.run(request, new StreamObserver<>() {
                @Override
                public void onNext(AgentProto.AgentEvent event) {
                    try {
                        adapter.accept(event);
                        if (owner.runMapper.recordEventSequence(run.getRunId(), adapter.lastSequence()) != 1) {
                            throw new AgentEventAdapter.AgentProtocolException("Agent event sequence was rejected");
                        }
                    } catch (Exception error) {
                        adapter.protocolFailure("AGENT_PROTOCOL_ERROR");
                        if (owner.clientDisconnected(error)) {
                            owner.runMapper.requestCancel(run.getRunId(), invocation.userId());
                            owner.lifecycle.stopLease(run.getRunId());
                        }
                        execution.cancel();
                        if (finalized.compareAndSet(false, true)) owner.finish(run, invocation, adapter.result(), emitter);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (finalized.compareAndSet(false, true)) {
                        owner.finish(run, invocation, adapter.failTransport(error), emitter);
                    }
                }

                @Override
                public void onCompleted() {
                    if (finalized.compareAndSet(false, true)) {
                        owner.finish(run, invocation, adapter.finishTransport(), emitter);
                    }
                }
            });
            callRef.set(call);
            if (cancelPending.get()) call.cancel();
        } catch (Exception error) {
            owner.log.error("graph_v2 dispatch failed for run {}", run.getRunId(), error);
            if (finalized.compareAndSet(false, true)) {
                owner.finish(run, invocation, ChatFinalResult.failed(
                        "暂时无法回答", "AGENT_UNAVAILABLE", "agent_unavailable", ""), emitter);
            }
        }
    }



    /** Close the race where client cancellation arrives before the graph call is registered. */
    protected boolean finishCancelledBeforeDispatch(AgentRun run, ChatInvocation invocation,
                                                  SseEmitter emitter, boolean includeMeta) {
        if (!"cancel_requested".equals(run.getStatus())) return false;
        boolean marked = owner.runMapper.markCancelled(run.getRunId(), "cancelled_before_dispatch") == 1;
        if (marked) {
            run.setStatus("cancelled");
            run.setStopReason("cancelled_before_dispatch");
            if (includeMeta && invocation != null) owner.sendMeta(emitter, invocation);
            owner.sendDone(emitter, run, null, "", "cancelled");
            owner.completeEmitter(emitter);
            return true;
        }

        // Another terminal path won the race; replay its stored truth rather than dispatching again.
        AgentRun latest = owner.runMapper.findByRunId(run.getRunId());
        if (latest != null) owner.replayExisting(latest, emitter);
        else owner.completeEmitter(emitter);
        return true;
    }



    protected void replayExisting(AgentRun run, SseEmitter emitter) {
        ChatInvocation invocation = new ChatInvocation(run.getUserId(), run.getSessionId(), run.getRunId(),
                run.getClientRequestId(), run.getUserMessageId() == null ? 0 : run.getUserMessageId(),
                "", run.getEngine());
        owner.sendMeta(emitter, invocation);
        java.util.Optional<AgentCommandResponse> receipt = owner.commandService.findReceiptForRun(run.getUserId(), run.getRunId());
        if (receipt != null && receipt.isPresent()) {
            owner.sendReceiptSuccess(run, emitter, receipt.get());
            return;
        }
        if ("running".equals(run.getStatus()) || "cancel_requested".equals(run.getStatus())) {
            owner.sendEvent(emitter, "error", Map.of("code", "RUN_IN_PROGRESS", "runId", run.getRunId().toString()));
            owner.sendDone(emitter, run, null, "", run.getStatus());
            owner.completeEmitter(emitter);
            return;
        }
        ConversationHistory assistant = run.getAssistantMessageId() == null
                ? null : owner.historyMapper.selectById(run.getAssistantMessageId());
        if (assistant != null) {
            if (assistant.getContent() != null && !assistant.getContent().isEmpty()) {
                owner.sendEvent(emitter, "delta", Map.of("content", assistant.getContent()));
            }
            if (assistant.getSources() != null && !assistant.getSources().isEmpty()) owner.sendEvent(emitter, "sources", assistant.getSources());
            if (assistant.getVaultRefs() != null && !assistant.getVaultRefs().isEmpty()) owner.sendEvent(emitter, "vault_refs", assistant.getVaultRefs());
        }
        owner.sendDone(emitter, run, assistant, owner.routeFrom(run.getMetricsJson()), run.getStatus());
        owner.completeEmitter(emitter);
    }
}
