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

@Service
public class AgentRunService {
    protected static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentRunService.class);
    private final AgentRunRequests agentRunRequests = new AgentRunRequests(this);
    private final AgentRunShadows agentRunShadows = new AgentRunShadows(this);
    private final AgentRunCompletion agentRunCompletion = new AgentRunCompletion(this);



    protected static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    protected static final int TITLE_MAX_LEN = 50;

    protected final AgentRunMapper runMapper;
    protected final AgentCommandService commandService;
    protected final ChatSessionMapper sessionMapper;
    protected final ConversationHistoryMapper historyMapper;
    protected final ChatService chatService;
    protected final AssistantContextService assistantContextService;
    @org.springframework.beans.factory.annotation.Autowired
    protected org.xianshen.mumirrorb.service.TarotChatContextService tarotChatContext;
    protected final DailyChatSessionResolver dailySessionResolver;
    protected final AgentGrpcClient agentGrpcClient;
    protected final AgentLifecycleService lifecycle;
    protected final GlossaryService glossaryService;
    protected final ChatEngineProperties engineProperties;
    protected final AgentProperties agentProperties;
    protected final AuditService auditService;
    protected final ToolOrchestrator toolOrchestrator;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate transactions;
    protected final ConcurrentHashMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();
    protected final ThreadPoolExecutor shadowExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "agent-shadow-run");
                thread.setDaemon(true);
                return thread;
            });
    protected final AtomicReference<ShadowExecution> activeShadow = new AtomicReference<>();

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


    public StartRunResult startOrFindRun(UUID userId, ChatRequestDTO dto) {
        return agentRunRequests.startOrFindRun(userId, dto);
    }

    protected StartRunResult acceptInTransaction(UUID userId, UUID requestId, UUID externalSessionId,
                                               String question, String requestHash) {
        return agentRunRequests.acceptInTransaction(userId, requestId, externalSessionId, question, requestHash);
    }

    protected StartRunResult existingRequest(AgentRun existing, String requestHash) {
        return agentRunRequests.existingRequest(existing, requestHash);
    }


    @Async("agentRunExecutor")
    public void dispatch(StartRunResult start, SseEmitter emitter) {
        agentRunRequests.dispatch(start, emitter);
    }

    protected void runGraph(AgentRun run, ChatInvocation invocation, SseEmitter emitter) {
        agentRunRequests.runGraph(run, invocation, emitter);
    }


    protected boolean finishCancelledBeforeDispatch(AgentRun run, ChatInvocation invocation,
                                                  SseEmitter emitter, boolean includeMeta) {
        return agentRunRequests.finishCancelledBeforeDispatch(run, invocation, emitter, includeMeta);
    }

    protected void replayExisting(AgentRun run, SseEmitter emitter) {
        agentRunRequests.replayExisting(run, emitter);
    }

    public void cancelRun(UUID runId, UUID userId, String reason) {
        agentRunShadows.cancelRun(runId, userId, reason);
    }

    public void cancelActiveForSession(UUID sessionId, UUID userId) {
        agentRunShadows.cancelActiveForSession(sessionId, userId);
    }

    public RunStatusVO getRun(UUID runId, UUID userId) {
        return agentRunShadows.getRun(runId, userId);
    }

    protected void onLeaseLost(UUID runId) {
        agentRunShadows.onLeaseLost(runId);
    }

    void scheduleShadow(AgentRun parent, ChatInvocation parentInvocation) {
        agentRunCompletion.scheduleShadow(parent, parentInvocation);
    }

    protected void runShadow(AgentRun parent, ChatInvocation parentInvocation) {
        agentRunShadows.runShadow(parent, parentInvocation);
    }

    protected void finishShadow(ShadowExecution execution, ChatFinalResult result) {
        agentRunShadows.finishShadow(execution, result);
    }

    void cancelShadowForForeground() {
        agentRunCompletion.cancelShadowForForeground();
    }

    protected boolean shadowSampled(String metricsJson) {
        return agentRunShadows.shadowSampled(metricsJson);
    }

    @PreDestroy
    public void closeShadowExecutor() {
        agentRunShadows.closeShadowExecutor();
    }

    protected void finish(AgentRun originalRun, ChatInvocation invocation,
                        ChatFinalResult result, SseEmitter emitter) {
        agentRunCompletion.finish(originalRun, invocation, result, emitter);
    }

    protected CompletionCommit completeRun(UUID runId, ChatFinalResult result, List<Long> contextSourceMessageIds) {
        return agentRunCompletion.completeRun(runId, result, contextSourceMessageIds);
    }

    protected ChatFinalResult actionSuccessFromReceipt(ChatFinalResult original, AgentCommandResponse receipt) {
        return agentRunCompletion.actionSuccessFromReceipt(original, receipt);
    }

    protected Map<String, Object> createdRecordMap(AgentCommandResponse receipt) {
        return agentRunCompletion.createdRecordMap(receipt);
    }

    protected List<Long> contextSourceMessageIds(org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext context) {
        return agentRunCompletion.contextSourceMessageIds(context);
    }

    protected void sendReceiptSuccess(AgentRun run, SseEmitter emitter, AgentCommandResponse receipt) {
        agentRunCompletion.sendReceiptSuccess(run, emitter, receipt);
    }

    protected void sendMeta(SseEmitter emitter, ChatInvocation invocation) {
        agentRunCompletion.sendMeta(emitter, invocation);
    }

    protected void sendDone(SseEmitter emitter, AgentRun run, ConversationHistory assistant,
                          String route, String status) {
        agentRunCompletion.sendDone(emitter, run, assistant, route, status);
    }

    protected String metricsWithRoute(String metricsJson, String route) {
        return agentRunCompletion.metricsWithRoute(metricsJson, route);
    }

    protected String withShadowSampled(String metricsJson) {
        return agentRunCompletion.withShadowSampled(metricsJson);
    }

    protected ChatFinalResult cancelledShadowResult() {
        return agentRunCompletion.cancelledShadowResult();
    }

    protected String routeFrom(String metricsJson) {
        return agentRunCompletion.routeFrom(metricsJson);
    }

    protected void sendEvent(SseEmitter emitter, String name, Object data) {
        agentRunCompletion.sendEvent(emitter, name, data);
    }

    protected void completeEmitter(SseEmitter emitter) {
        agentRunCompletion.completeEmitter(emitter);
    }

    protected static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "abandoned".equals(status);
    }

    protected static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    protected static boolean clientDisconnected(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("SSE client disconnected")) return true;
        }
        return false;
    }

    protected static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    protected record CompletionCommit(boolean persisted, AgentRun run, ConversationHistory assistant,
                                    String status, String errorCode, boolean receiptConfirmed) { }

    protected record ShadowExecution(AgentRun run, ActiveExecution execution) { }

    protected static final class ActiveExecution {
        protected final Runnable cancelAction;
        protected ActiveExecution(Runnable cancelAction) { this.cancelAction = cancelAction; }
        protected void cancel() { if (cancelAction != null) cancelAction.run(); }
    }
}
