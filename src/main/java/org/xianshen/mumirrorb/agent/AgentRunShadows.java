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

/** AgentRunShadows keeps the service entry points and transaction boundaries in AgentRunService. */
final class AgentRunShadows {
    private final AgentRunService owner;
    AgentRunShadows(AgentRunService owner) { this.owner=owner; }


    public void cancelRun(UUID runId, UUID userId, String reason) {
        if (owner.runMapper.requestCancel(runId, userId) != 1) return;
        owner.lifecycle.stopLease(runId);
        ActiveExecution execution = owner.activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        owner.log.info("Agent run cancellation requested: runId={}, reason={}", runId, reason);
    }



    public void cancelActiveForSession(UUID sessionId, UUID userId) {
        AgentRun active = owner.runMapper.findActiveBySession(sessionId);
        if (active == null || !userId.equals(active.getUserId())) return;
        if (owner.runMapper.requestCancelForSession(sessionId, userId) == 1) {
            owner.lifecycle.stopLease(active.getRunId());
            ActiveExecution execution = owner.activeExecutions.get(active.getRunId());
            if (execution != null) execution.cancel();
            owner.runMapper.markCancelled(active.getRunId(), "session_deleted");
        }
    }



    public RunStatusVO getRun(UUID runId, UUID userId) {
        AgentRun run = owner.runMapper.findByRunId(runId);
        if (run == null || !userId.equals(run.getUserId())) throw new RunNotFoundException();
        if (run.getUserMessageId() == null || owner.sessionMapper.selectById(run.getSessionId()) == null) {
            throw new RunGoneException("该请求对应的会话已删除");
        }
        ConversationHistory assistant = run.getAssistantMessageId() == null
                ? null : owner.historyMapper.selectById(run.getAssistantMessageId());
        java.util.Optional<AgentCommandResponse> receipt = owner.commandService.findReceiptForRun(userId, runId);
        if (receipt != null && receipt.isPresent()) {
            AgentCommandResponse value = receipt.get();
            ChatSessionVO.MessageVO receiptMessage = ChatSessionVO.MessageVO.builder()
                    .id(assistant == null ? null : assistant.getId()).role("assistant")
                    .content("已保存这条日记；目前是仅记录状态")
                    .createdRecords(List.of(owner.createdRecordMap(value)))
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



    protected void onLeaseLost(UUID runId) {
        owner.runMapper.markAbandoned(runId);
        ActiveExecution execution = owner.activeExecutions.get(runId);
        if (execution != null) execution.cancel();
        owner.log.warn("Agent run lease lost and execution cancelled: runId={}", runId);
    }



    protected void runShadow(AgentRun parent, ChatInvocation parentInvocation) {
        boolean foregroundGraphActive;
        try {
            foregroundGraphActive = !owner.activeExecutions.isEmpty() || Boolean.TRUE.equals(owner.runMapper.hasActiveForegroundGraph());
        } catch (Exception error) {
            owner.log.info("Skipping sampled shadow run because foreground capacity could not be checked: parentRunId={}",
                    parent.getRunId());
            return;
        }
        if (foregroundGraphActive) {
            owner.log.info("Skipping sampled shadow run while a foreground graph run is active: parentRunId={}",
                    parent.getRunId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(owner.ZONE);
        UUID shadowRunId = UUID.randomUUID();
        UUID shadowRequestId = UUID.randomUUID();
        AgentRun shadow = AgentRun.builder().runId(shadowRunId).userId(parent.getUserId())
                .sessionId(parent.getSessionId()).clientRequestId(shadowRequestId)
                .requestHash(owner.sha256(parent.getRequestHash() + ":shadow:" + shadowRunId))
                .questionHash(parent.getQuestionHash()).engine("graph_v2").mode("shadow")
                .parentRunId(parent.getRunId()).status("running").startedAt(now)
                .leaseExpiresAt(now.plusNanos((owner.agentProperties.getTotalBudgetMs() + 10_000L) * 1_000_000L))
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
        if (!owner.activeShadow.compareAndSet(null, state)) return;

        try {
            owner.runMapper.insertRun(shadow);
        } catch (Exception error) {
            owner.activeShadow.compareAndSet(state, null);
            owner.log.warn("Could not create sampled shadow run for parent {}: {}", parent.getRunId(), error.getMessage());
            return;
        }
        boolean foregroundArrived;
        try {
            foregroundArrived = !owner.activeExecutions.isEmpty() || Boolean.TRUE.equals(owner.runMapper.hasActiveForegroundGraph());
        } catch (Exception error) {
            foregroundArrived = true;
        }
        if (cancelPending.get() || foregroundArrived) {
            try {
                owner.runMapper.requestShadowCancel(shadowRunId, "foreground_priority");
            } catch (Exception error) {
                owner.log.debug("Could not mark shadow skip before gRPC dispatch: runId={}", shadowRunId);
            }
            owner.finishShadow(state, owner.cancelledShadowResult());
            return;
        }

        AgentEventAdapter adapter = new AgentEventAdapter(shadow, invocation, null, owner.auditService,
                owner.toolOrchestrator, owner.objectMapper, true);
        AtomicBoolean finalized = new AtomicBoolean(false);
        try {
            AgentProto.AgentRunRequest request = owner.agentGrpcClient.buildRequest(invocation, shadow);
            if (cancelPending.get()) {
                owner.finishShadow(state, owner.cancelledShadowResult());
                return;
            }
            AgentGrpcClient.CancellableCall call = owner.agentGrpcClient.run(request, new StreamObserver<>() {
                @Override
                public void onNext(AgentProto.AgentEvent event) {
                    try {
                        adapter.accept(event);
                    } catch (Exception error) {
                        adapter.protocolFailure("AGENT_PROTOCOL_ERROR");
                        execution.cancel();
                        if (finalized.compareAndSet(false, true)) owner.finishShadow(state, adapter.result());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (finalized.compareAndSet(false, true)) owner.finishShadow(state, adapter.failTransport(error));
                }

                @Override
                public void onCompleted() {
                    if (finalized.compareAndSet(false, true)) owner.finishShadow(state, adapter.finishTransport());
                }
            });
            callRef.set(call);
            if (cancelPending.get()) call.cancel();
        } catch (Exception error) {
            owner.log.info("Sampled shadow run could not start: runId={}, cause={}", shadowRunId, error.getMessage());
            if (finalized.compareAndSet(false, true)) {
                owner.finishShadow(state, ChatFinalResult.failed("", "AGENT_UNAVAILABLE", "agent_unavailable", ""));
            }
        }
    }



    protected void finishShadow(ShadowExecution execution, ChatFinalResult result) {
        ChatFinalResult finalResult = result == null
                ? ChatFinalResult.failed("", "EMPTY_RESULT", "empty_result", "") : result;
        if (!finalResult.createdRecords().isEmpty()
                || (finalResult.outcome() != null && finalResult.outcome().startsWith("action_"))) {
            finalResult = ChatFinalResult.failed("", "SHADOW_WRITE_BLOCKED", "shadow_write_blocked", "");
        }
        try {
            String shadowMetrics = owner.metricsWithRoute(finalResult.metricsJson(), finalResult.route());
            boolean persisted = owner.runMapper.completeShadow(execution.run().getRunId(), finalResult.status(),
                    finalResult.outcome(), finalResult.stopReason(), finalResult.errorCode(),
                    Math.max(0, finalResult.planRounds()), Math.max(0, finalResult.toolCallCount()),
                    Math.max(0, finalResult.lastEventSeq()),
                    shadowMetrics == null ? "{}" : shadowMetrics) == 1;
            if (!persisted) {
                owner.log.info("Sampled shadow run finished after its state was no longer active: runId={}",
                        execution.run().getRunId());
            }
        } catch (Exception error) {
            owner.log.warn("Could not persist sampled shadow metrics: runId={}, cause={}",
                    execution.run().getRunId(), error.getMessage());
        } finally {
            owner.activeShadow.compareAndSet(execution, null);
            owner.log.info("Sampled shadow run finished: runId={}, outcome={}, stopReason={}",
                    execution.run().getRunId(), finalResult.outcome(), finalResult.stopReason());
        }
    }



    protected boolean shadowSampled(String metricsJson) {
        try {
            Map<String, Object> metrics = owner.objectMapper.readValue(metricsJson == null ? "{}" : metricsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return Boolean.TRUE.equals(metrics.get("shadow_sampled"));
        } catch (Exception ignored) {
            return false;
        }
    }



    @PreDestroy
    public void closeShadowExecutor() {
        ShadowExecution shadow = owner.activeShadow.get();
        if (shadow != null) {
            try {
                owner.runMapper.requestShadowCancel(shadow.run().getRunId(), "service_shutdown");
            } catch (Exception error) {
                owner.log.debug("Could not mark shadow cancellation during shutdown: runId={}", shadow.run().getRunId());
            }
            shadow.execution().cancel();
        }
        owner.shadowExecutor.shutdownNow();
    }
}
