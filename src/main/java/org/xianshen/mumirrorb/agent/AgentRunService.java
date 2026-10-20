package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;
import org.xianshen.mumirrorb.pojo.VO.RunStatusVO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.tools.AuditService;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns request acceptance, per-turn engine selection and the only assistant persistence exit. */
@Slf4j
@Service
public class AgentRunService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int TITLE_MAX_LEN = 50;

    private final AgentRunRepository repository;
    private final AgentCommandService commandService;
    private final ChatSessionMapper sessionMapper;
    private final ConversationHistoryMapper historyMapper;
    private final ChatService chatService;
    private final AgentGrpcClient agentGrpcClient;
    private final AgentLifecycleService lifecycle;
    private final GlossaryService glossaryService;
    private final ChatEngineProperties engineProperties;
    private final AgentProperties agentProperties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    public AgentRunService(AgentRunRepository repository,
                          AgentCommandService commandService,
                          ChatSessionMapper sessionMapper,
                          ConversationHistoryMapper historyMapper,
                          ChatService chatService,
                          AgentGrpcClient agentGrpcClient,
                          AgentLifecycleService lifecycle,
                          GlossaryService glossaryService,
                          ChatEngineProperties engineProperties,
                          AgentProperties agentProperties,
                          AuditService auditService,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.commandService = commandService;
        this.sessionMapper = sessionMapper;
        this.historyMapper = historyMapper;
        this.chatService = chatService;
        this.agentGrpcClient = agentGrpcClient;
        this.lifecycle = lifecycle;
        this.glossaryService = glossaryService;
        this.engineProperties = engineProperties;
        this.agentProperties = agentProperties;
        this.auditService = auditService;
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
            AgentRun sameRequest = repository.findByRequest(userId, requestId);
            if (sameRequest != null) return existingRequest(sameRequest, requestHash);
            if (externalSessionId != null && repository.findActiveBySession(externalSessionId) != null) {
                throw new AgentRunConflictException("该会话已有一轮对话正在运行");
            }
            throw conflict;
        }
    }

    private StartRunResult acceptInTransaction(UUID userId, UUID requestId, UUID externalSessionId,
                                               String question, String requestHash) {
        AgentRun existing = repository.findByRequest(userId, requestId);
        if (existing != null) return existingRequest(existing, requestHash);

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        ChatSession session;
        if (externalSessionId == null) {
            session = ChatSession.builder().id(UUID.randomUUID()).userId(userId)
                    .title(question.length() > TITLE_MAX_LEN ? question.substring(0, TITLE_MAX_LEN) : question)
                    .createdAt(now).updatedAt(now).build();
            sessionMapper.insert(session);
        } else {
            session = sessionMapper.selectById(externalSessionId);
            if (session == null || !userId.equals(session.getUserId())) {
                throw new org.xianshen.mumirrorb.common.exception.BusinessException(
                        org.xianshen.mumirrorb.common.enums.ResultCode.RECORD_NOT_FOUND, "会话不存在");
            }
            session.setUpdatedAt(now);
            sessionMapper.updateById(session);
        }

        UUID runId = UUID.randomUUID();
        String engine = engineProperties.graphEnabled(userId) ? "graph_v2" : "legacy";
        AgentRun run = AgentRun.builder().runId(runId).userId(userId).sessionId(session.getId())
                .clientRequestId(requestId).requestHash(requestHash).questionHash(sha256(question))
                .engine(engine).mode("live").status("running").startedAt(now)
                .leaseExpiresAt(now.plusSeconds(agentProperties.getLeaseSeconds()))
                .planRounds(0).toolCallCount(0).lastEventSeq(0L).glossaryHitsApplied(false).build();
        repository.insert(run);

        ConversationHistory userMessage = ConversationHistory.builder().runId(runId)
                .sessionId(session.getId()).userId(userId).role("user").content(question)
                .createdAt(now).isFallback(false).build();
        historyMapper.insert(userMessage);
        repository.linkUserMessage(runId, userMessage.getId());
        run.setUserMessageId(userMessage.getId());
        ChatInvocation invocation = new ChatInvocation(userId, session.getId(), runId,
                requestId, userMessage.getId(), question, engine);
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
        AgentRun run = repository.findById(start.run().getRunId());
        if (run == null || !"running".equals(run.getStatus())
                || run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            if (run != null && "running".equals(run.getStatus())) repository.markAbandoned(run.getRunId());
            completeEmitter(emitter);
            return;
        }
        ChatInvocation invocation = start.invocation();
        sendMeta(emitter, invocation);
        if ("graph_v2".equals(run.getEngine())) {
            runGraph(run, invocation, emitter);
            return;
        }
        try {
            finish(run, invocation, chatService.runLegacy(invocation, emitter), emitter);
        } catch (Exception error) {
            log.error("Legacy chat failed for run {}", run.getRunId(), error);
            ChatFinalResult result = ChatFinalResult.fallback("暂时无法回答", "legacy_error", "");
            sendEvent(emitter, "delta", Map.of("content", result.answerText()));
            finish(run, invocation, result, emitter);
        }
    }

    private void runGraph(AgentRun run, ChatInvocation invocation, SseEmitter emitter) {
        AgentEventAdapter adapter = new AgentEventAdapter(run, invocation, emitter, auditService, objectMapper, false);
        AtomicReference<AgentGrpcClient.CancellableCall> callRef = new AtomicReference<>();
        AtomicBoolean cancelPending = new AtomicBoolean(false);
        ActiveExecution execution = new ActiveExecution(() -> {
            cancelPending.set(true);
            AgentGrpcClient.CancellableCall call = callRef.get();
            if (call != null) call.cancel();
        });
        activeExecutions.put(run.getRunId(), execution);
        AgentRun current = repository.findById(run.getRunId());
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
                        if (!repository.recordEventSequence(run.getRunId(), adapter.lastSequence())) {
                            throw new AgentEventAdapter.AgentProtocolException("Agent event sequence was rejected");
                        }
                    } catch (Exception error) {
                        adapter.protocolFailure("AGENT_PROTOCOL_ERROR");
                        if (clientDisconnected(error)) {
                            repository.requestCancel(run.getRunId(), invocation.userId());
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
        if (!repository.requestCancel(runId, userId)) return;
        lifecycle.stopLease(runId);
        ActiveExecution execution = activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        log.info("Agent run cancellation requested: runId={}, reason={}", runId, reason);
    }

    public void cancelActiveForSession(UUID sessionId, UUID userId) {
        AgentRun active = repository.findActiveBySession(sessionId);
        if (active == null || !userId.equals(active.getUserId())) return;
        if (repository.requestCancelForSession(sessionId, userId)) {
            lifecycle.stopLease(active.getRunId());
            ActiveExecution execution = activeExecutions.get(active.getRunId());
            if (execution != null) execution.cancel();
            repository.markCancelled(active.getRunId(), "session_deleted");
        }
    }

    public RunStatusVO getRun(UUID runId, UUID userId) {
        AgentRun run = repository.findById(runId);
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
        repository.markAbandoned(runId);
        ActiveExecution execution = activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        log.warn("Agent run lease lost and execution cancelled: runId={}", runId);
    }

    private void finish(AgentRun originalRun, ChatInvocation invocation,
                        ChatFinalResult result, SseEmitter emitter) {
        if (result == null) result = ChatFinalResult.failed("暂时无法回答", "EMPTY_RESULT", "empty_result", "");
        final ChatFinalResult finalResult = result;
        try {
            CompletionCommit commit = transactions.execute(status -> completeRun(originalRun.getRunId(), finalResult));
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

    private CompletionCommit completeRun(UUID runId, ChatFinalResult result) {
        AgentRun run = repository.lockRun(runId);
        if (run == null || isTerminal(run.getStatus())) return new CompletionCommit(false, run, null, null, "", false);
        if (run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            repository.markAbandoned(runId);
            return new CompletionCommit(false, run, null, null, "", false);
        }
        if (sessionMapper.selectById(run.getSessionId()) == null) {
            repository.markAbandoned(runId);
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
                .isFallback(result.fallback()).createdAt(OffsetDateTime.now(ZONE)).build();
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
        boolean updated = repository.complete(run, status, result.outcome(), stopReason, errorCode,
                result.planRounds(), result.toolCallCount(), result.lastEventSeq(), assistant.getId(),
                metricsWithRoute(result.metricsJson(), result.route()));
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
                "engine", invocation.engine()));
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

    private static final class ActiveExecution {
        private final Runnable cancelAction;
        private ActiveExecution(Runnable cancelAction) { this.cancelAction = cancelAction; }
        private void cancel() { if (cancelAction != null) cancelAction.run(); }
    }
}
