package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.tools.AuditService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates Agent events, forwards streaming fields, and produces one normalized final value. */
@RequiredArgsConstructor
public class AgentEventAdapter {

    private final AgentRun run;
    private final ChatInvocation invocation;
    private final SseEmitter emitter;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final boolean shadow;

    private final StringBuilder answer = new StringBuilder();
    private final List<Map<String, Object>> sources = new ArrayList<>();
    private final List<Map<String, Object>> vaultRefs = new ArrayList<>();
    private final List<Map<String, Object>> createdRecords = new ArrayList<>();
    private final List<Map<String, Object>> toolsUsed = new ArrayList<>();
    private final Set<Integer> sourceNumbers = new HashSet<>();
    private final Set<Integer> vaultNumbers = new HashSet<>();
    private final Set<String> toolCallIds = new HashSet<>();
    private long lastSequence;
    private boolean terminal;
    private String route = "";
    private ChatFinalResult result;

    public synchronized void accept(AgentProto.AgentEvent event) {
        if (terminal) {
            throw new AgentProtocolException("event received after terminal event");
        }
        if (!invocation.runId().toString().equals(event.getRunId())) {
            throw new AgentProtocolException("Agent event run_id mismatch");
        }
        if (event.getSeq() != lastSequence + 1) {
            throw new AgentProtocolException("Agent event sequence is duplicate, skipped, or out of order");
        }
        lastSequence = event.getSeq();
        switch (event.getEventCase()) {
            case META -> onMeta(event.getMeta());
            case THINKING -> send("thinking", Map.of("content", event.getThinking().getContent()));
            case DELTA -> {
                String delta = event.getDelta().getContent();
                answer.append(delta);
                send("delta", Map.of("content", delta));
            }
            case TOOL_FINISHED -> onToolFinished(event.getToolFinished());
            case COMPLETED -> onCompleted(event.getCompleted());
            case FAILED -> onFailed(event.getFailed());
            case EVENT_NOT_SET -> throw new AgentProtocolException("Agent event has no payload");
        }
    }

    public synchronized ChatFinalResult finishTransport() {
        if (!terminal) {
            return finishFailure("MISSING_TERMINAL", "Agent stream ended without a terminal event");
        }
        return result;
    }

    public synchronized ChatFinalResult failTransport(Throwable error) {
        if (terminal) {
            return result;
        }
        return finishFailure("AGENT_TRANSPORT_ERROR", error == null ? "Agent stream failed" : error.getMessage());
    }

    public synchronized ChatFinalResult result() {
        return result;
    }

    public synchronized ChatFinalResult protocolFailure(String code) {
        terminal = true;
        result = new ChatFinalResult(answer.toString(), List.of(), toolsUsed, List.of(), List.of(),
                false, !answer.isEmpty(), "failed", "answer", route,
                answer.isEmpty() ? "agent_protocol_error" : "partial_stream",
                code, 0, toolsUsed.size(), lastSequence, "{}", List.of());
        return result;
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }

    private void onMeta(AgentProto.AgentMeta meta) {
        if (!meta.getRoute().isBlank()) route = meta.getRoute();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", invocation.sessionId().toString());
        payload.put("runId", invocation.runId().toString());
        payload.put("requestId", invocation.clientRequestId().toString());
        payload.put("engine", run.getEngine());
        if (!meta.getRoute().isBlank()) {
            payload.put("route", meta.getRoute());
        }
        if (!meta.getToolsUsedList().isEmpty()) {
            payload.put("tools_used", List.copyOf(meta.getToolsUsedList()));
        }
        send("meta", payload);
    }

    private void onToolFinished(AgentProto.AgentToolTrace trace) {
        if (trace.getCallId().isBlank() || !toolCallIds.add(trace.getCallId())) {
            throw new AgentProtocolException("Agent tool call_id is blank or duplicated");
        }
        if (!trace.getAuditedByJava() && !shadow) {
            Map<String, Object> args = parseArgs(trace.getArgsJson());
            auditService.record(
                    invocation.userId(), invocation.sessionId(), invocation.runId(), trace.getCallId(),
                    run.getEngine(), trace.getTool(), args, trace.getSummary(), trace.getSuccess(),
                    trace.getLatencyMs(), trace.getCode());
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", trace.getTool());
        item.put("summary", trace.getSummary());
        item.put("success", trace.getSuccess());
        toolsUsed.add(item);
    }

    private void onCompleted(AgentProto.AgentCompleted completed) {
        if (!answer.toString().equals(completed.getAnswerText())) {
            throw new AgentProtocolException("completed.answer_text differs from streamed delta text");
        }
        for (org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.Source source : completed.getSourcesList()) {
            int n = source.getN();
            if (n != sourceNumbers.size() + 1 || !sourceNumbers.add(n) || source.getRecordId() <= 0) {
                throw new AgentProtocolException("Agent source numbering or record id is invalid");
            }
            sources.add(Map.of(
                    "n", n,
                    "record_id", source.getRecordId(),
                    "quote", source.getQuote(),
                    "date", source.getDate()));
        }
        for (AgentProto.AgentVaultRef reference : completed.getVaultRefsList()) {
            int n = reference.getN();
            if (n < 0 || (n > 0 && !vaultNumbers.add(n))) {
                throw new AgentProtocolException("Agent vault reference numbering is invalid");
            }
            if (n > 0 && n != vaultNumbers.size()) {
                throw new AgentProtocolException("Agent vault reference numbering is not consecutive");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("n", n == 0 ? null : n);
            item.put("vault_item_id", reference.getVaultItemId());
            item.put("display_name", reference.getDisplayName());
            item.put("file_type", reference.getFileType());
            item.put("size_bytes", reference.getSizeBytes());
            item.put("digest_status", reference.getDigestStatus());
            item.put("quote", reference.getQuote());
            vaultRefs.add(item);
        }
        for (AgentProto.AgentCreatedRecord created : completed.getCreatedRecordsList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("record_id", created.getRecordId());
            item.put("status", created.getStatus());
            item.put("created_at", created.getCreatedAt());
            createdRecords.add(item);
        }
        terminal = true;
        result = new ChatFinalResult(
                completed.getAnswerText(), sources, toolsUsed, vaultRefs, createdRecords,
                completed.getFallback(), false, "completed", completed.getOutcome(), route,
                completed.getStopReason(), "", completed.getPlanRounds(), completed.getToolCallCount(),
                lastSequence, metricsJson(completed.getMetrics()),
                List.copyOf(completed.getGlossaryHitIdsList()));
    }

    private void onFailed(AgentProto.AgentFailure failure) {
        terminal = true;
        String outcome = "write_failed".equals(failure.getStopReason()) ? "action_failed" : "answer";
        result = new ChatFinalResult(
                answer.toString(), List.of(), toolsUsed, List.of(), List.of(),
                false, failure.getHasPartialAnswer(), "failed", outcome, route,
                failure.getStopReason(), failure.getCode(), 0, toolsUsed.size(), lastSequence,
                metricsJson(failure.getMetrics()), List.of());
    }

    private ChatFinalResult finishFailure(String code, String message) {
        terminal = true;
        result = new ChatFinalResult(answer.toString(), List.of(), toolsUsed, List.of(), List.of(),
                false, !answer.isEmpty(), "failed", "answer", route,
                answer.isEmpty() ? "agent_error" : "partial_stream", code,
                0, toolsUsed.size(), lastSequence, "{}", List.of());
        return result;
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String metricsJson(AgentProto.AgentMetrics metrics) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("phase_latency_ms", metrics.getPhaseLatencyMsMap());
        values.put("degraded_reasons", metrics.getDegradedReasonsList());
        values.put("prompt_hashes", metrics.getPromptHashesMap());
        if (metrics.hasFirstThinkingMs()) values.put("first_thinking_ms", metrics.getFirstThinkingMs());
        if (metrics.hasFirstDeltaMs()) values.put("first_delta_ms", metrics.getFirstDeltaMs());
        if (metrics.hasInputTokens()) values.put("input_tokens", metrics.getInputTokens());
        if (metrics.hasOutputTokens()) values.put("output_tokens", metrics.getOutputTokens());
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void send(String name, Object payload) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(payload)));
        } catch (IOException error) {
            throw new AgentProtocolException("SSE client disconnected", error);
        }
    }

    public static class AgentProtocolException extends RuntimeException {
        public AgentProtocolException(String message) { super(message); }
        public AgentProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
