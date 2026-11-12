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

/** AgentRunCompletion keeps the service entry points and transaction boundaries in AgentRunService. */
final class AgentRunCompletion {
    private final AgentRunService owner;
    AgentRunCompletion(AgentRunService owner) { this.owner=owner; }


    void scheduleShadow(AgentRun parent, ChatInvocation parentInvocation) {
        if (!"legacy".equals(parent.getEngine()) || !"live".equals(parent.getMode())
                || !owner.shadowSampled(parent.getMetricsJson())
                || AgentWriteGrantService.authorizedContent(parentInvocation.question()) != null) {
            return;
        }
        AgentRun latest;
        try {
            latest = owner.runMapper.findByRunId(parent.getRunId());
        } catch (Exception error) {
            owner.log.info("Skipping sampled shadow run because the parent state could not be checked: parentRunId={}",
                    parent.getRunId());
            return;
        }
        if (latest == null || !("completed".equals(latest.getStatus()) || "failed".equals(latest.getStatus()))) {
            return;
        }
        try {
            owner.shadowExecutor.execute(() -> owner.runShadow(parent, parentInvocation));
        } catch (RejectedExecutionException saturated) {
            owner.log.info("Skipping sampled shadow run because its single execution slot is busy: parentRunId={}",
                    parent.getRunId());
        }
    }



    void cancelShadowForForeground() {
        ShadowExecution shadow = owner.activeShadow.get();
        if (shadow != null) {
            try {
                owner.runMapper.requestShadowCancel(shadow.run().getRunId(), "foreground_priority");
            } catch (Exception error) {
                owner.log.info("Could not mark shadow cancellation before foreground dispatch: runId={}",
                        shadow.run().getRunId());
            }
            shadow.execution().cancel();
        }
    }



    protected void finish(AgentRun originalRun, ChatInvocation invocation,
                        ChatFinalResult result, SseEmitter emitter) {
        if (result == null) result = ChatFinalResult.failed("暂时无法回答", "EMPTY_RESULT", "empty_result", "");
        final ChatFinalResult finalResult = result;
        try {
            CompletionCommit commit = owner.transactions.execute(status -> owner.completeRun(originalRun.getRunId(), finalResult,
                    invocation == null ? List.of() : invocation.contextSourceMessageIds()));
            owner.lifecycle.stopLease(originalRun.getRunId());
            owner.activeExecutions.remove(originalRun.getRunId());
            if (commit == null || !commit.persisted()) return;
            if (commit.assistant() != null
                    && !java.util.Objects.equals(commit.assistant().getContent(), finalResult.answerText())) {
                owner.sendEvent(emitter, "delta", Map.of("content", commit.assistant().getContent()));
            } else if ("action_unknown".equals(finalResult.outcome()) && commit.assistant() != null) {
                owner.sendEvent(emitter, "delta", Map.of("content", commit.assistant().getContent()));
            }
            if (commit.assistant() != null) {
                if (commit.assistant().getSources() != null && !commit.assistant().getSources().isEmpty()) {
                    owner.sendEvent(emitter, "sources", commit.assistant().getSources());
                }
                if (commit.assistant().getVaultRefs() != null && !commit.assistant().getVaultRefs().isEmpty()) {
                    owner.sendEvent(emitter, "vault_refs", commit.assistant().getVaultRefs());
                }
            }
            if ("failed".equals(commit.status()) || "cancelled".equals(commit.status())) {
                owner.sendEvent(emitter, "error", Map.of("code", owner.safe(commit.errorCode(), "RUN_FAILED"),
                        "partial", finalResult.partial(), "message", "本轮对话未能完整完成"));
            }
            owner.sendDone(emitter, commit.run(), commit.assistant(), finalResult.route(), commit.status());
            owner.completeEmitter(emitter);
        } catch (Exception error) {
            owner.lifecycle.stopLease(originalRun.getRunId());
            owner.activeExecutions.remove(originalRun.getRunId());
            owner.log.error("Could not persist final result for run {}", originalRun.getRunId(), error);
            owner.sendEvent(emitter, "error", Map.of("code", "PERSISTENCE_ERROR", "partial", finalResult.partial()));
            owner.sendEvent(emitter, "done", Map.of("runId", originalRun.getRunId().toString(),
                    "status", "failed", "outcome", "answer", "errorCode", "PERSISTENCE_ERROR"));
            owner.completeEmitter(emitter);
        }
    }



    protected CompletionCommit completeRun(UUID runId, ChatFinalResult result, List<Long> contextSourceMessageIds) {
        AgentRun run = owner.runMapper.lockByRunId(runId);
        if (run == null || owner.isTerminal(run.getStatus())) return new CompletionCommit(false, run, null, null, "", false);
        if (run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(OffsetDateTime.now())) {
            owner.runMapper.markAbandoned(runId);
            return new CompletionCommit(false, run, null, null, "", false);
        }
        if (owner.sessionMapper.selectById(run.getSessionId()) == null) {
            owner.runMapper.markAbandoned(runId);
            return new CompletionCommit(false, run, null, null, "", false);
        }
        java.util.Optional<AgentCommandResponse> receipt = owner.commandService.findReceiptForRun(run.getUserId(), runId);
        boolean receiptConfirmed = receipt != null && receipt.isPresent();
        if (receiptConfirmed) {
            result = owner.actionSuccessFromReceipt(result, receipt.get());
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
                .isFallback(result.fallback()).contextSourceMessageIds(owner.objectMapper.valueToTree(contextSourceMessageIds))
                .createdAt(OffsetDateTime.now(owner.ZONE)).build();
        owner.historyMapper.insert(assistant);
        ChatSession session = owner.sessionMapper.selectById(run.getSessionId());
        if (session == null || !run.getUserId().equals(session.getUserId())) {
            throw new RunGoneException("会话已删除，无法保存本轮回答");
        }
        session.setUpdatedAt(OffsetDateTime.now(owner.ZONE));
        owner.sessionMapper.updateById(session);
        if ("graph_v2".equals(run.getEngine()) && "live".equals(run.getMode())
                && "completed".equals(status) && !result.glossaryHitIds().isEmpty()) {
            owner.glossaryService.recordQueryHits(run.getUserId(), runId, result.glossaryHitIds());
        }
        String runMetrics = owner.metricsWithRoute(result.metricsJson(), result.route());
        if (owner.shadowSampled(run.getMetricsJson())) runMetrics = owner.withShadowSampled(runMetrics);
        boolean updated = owner.runMapper.complete(run.getRunId(), run.getUserId(), status, result.outcome(),
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



    protected ChatFinalResult actionSuccessFromReceipt(ChatFinalResult original, AgentCommandResponse receipt) {
        return new ChatFinalResult("已保存这条日记；目前是仅记录状态", List.of(),
                original.toolsUsed(), List.of(), List.of(owner.createdRecordMap(receipt)), false, false,
                "completed", "action_success", "structured", "write_completed", null,
                original.planRounds(), original.toolCallCount(), original.lastEventSeq(),
                original.metricsJson(), original.glossaryHitIds());
    }



    protected Map<String, Object> createdRecordMap(AgentCommandResponse receipt) {
        return Map.of("record_id", receipt.record().id(), "status", receipt.record().status(),
                "created_at", receipt.record().createdAt().toString());
    }



    protected List<Long> contextSourceMessageIds(org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext context) {
        if (context == null || context.getMemoriesCount() == 0) return List.of();
        return context.getMemoriesList().stream()
                .flatMap(memory -> memory.getSourceMessageIdsList().stream())
                .distinct().toList();
    }



    protected void sendReceiptSuccess(AgentRun run, SseEmitter emitter, AgentCommandResponse receipt) {
        owner.sendEvent(emitter, "delta", Map.of("content", "已保存这条日记；目前是仅记录状态"));
        owner.sendEvent(emitter, "done", Map.of(
                "sessionId", run.getSessionId().toString(),
                "runId", run.getRunId().toString(),
                "requestId", run.getClientRequestId().toString(),
                "engine", run.getEngine(), "status", "completed", "outcome", "action_success",
                "route", "STRUCTURED", "fallback", false,
                "created_records", List.of(owner.createdRecordMap(receipt)), "stop_reason", "write_completed"));
        owner.completeEmitter(emitter);
    }



    protected void sendMeta(SseEmitter emitter, ChatInvocation invocation) {
        owner.sendEvent(emitter, "meta", Map.of("sessionId", invocation.sessionId().toString(),
                "runId", invocation.runId().toString(), "requestId", invocation.clientRequestId().toString(),
                "sourceMessageId", invocation.userMessageId(), "engine", invocation.engine()));
    }



    protected void sendDone(SseEmitter emitter, AgentRun run, ConversationHistory assistant,
                          String route, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", run.getSessionId().toString());
        payload.put("runId", run.getRunId().toString());
        payload.put("requestId", run.getClientRequestId().toString());
        payload.put("assistantMessageId", assistant == null ? null : assistant.getId());
        payload.put("engine", run.getEngine());
        payload.put("status", status);
        payload.put("outcome", run.getOutcome() == null ? "answer" : run.getOutcome());
        payload.put("route", route == null ? "" : route);
        payload.put("fallback", assistant != null && Boolean.TRUE.equals(assistant.getIsFallback()));
        payload.put("created_records", assistant == null || assistant.getCreatedRecords() == null
                ? List.of() : assistant.getCreatedRecords());
        if (run.getStopReason() != null) payload.put("stop_reason", run.getStopReason());
        if (run.getErrorCode() != null) payload.put("error_code", run.getErrorCode());
        owner.sendEvent(emitter, "done", payload);
    }



    protected String metricsWithRoute(String metricsJson, String route) {
        try {
            Map<String, Object> metrics = owner.objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            if (route != null && !route.isBlank()) metrics.put("route", route);
            return owner.objectMapper.writeValueAsString(metrics);
        } catch (Exception ignored) {
            return "{}";
        }
    }



    protected String withShadowSampled(String metricsJson) {
        try {
            Map<String, Object> metrics = owner.objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            metrics.put("shadow_sampled", true);
            return owner.objectMapper.writeValueAsString(metrics);
        } catch (Exception ignored) {
            return "{\"shadow_sampled\":true}";
        }
    }



    protected ChatFinalResult cancelledShadowResult() {
        return new ChatFinalResult("", List.of(), List.of(), List.of(), List.of(), false, false,
                "cancelled", "answer", "", "foreground_priority", "CANCELLED",
                0, 0, 0L, "{}", List.of());
    }



    protected String routeFrom(String metricsJson) {
        try {
            Map<String, Object> metrics = owner.objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return String.valueOf(metrics.getOrDefault("route", ""));
        } catch (Exception ignored) {
            return "";
        }
    }



    protected void sendEvent(SseEmitter emitter, String name, Object data) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(owner.objectMapper.writeValueAsString(data)));
        } catch (Exception error) {
            owner.log.debug("SSE {} send failed: {}", name, error.getMessage());
        }
    }



    protected void completeEmitter(SseEmitter emitter) {
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) { }
        }
    }
}
