package org.xianshen.mumirrorb.agent;

import java.util.List;
import java.util.Map;

/** Normalized final value shared by legacy and graph_v2 before one persistence exit. */
public record ChatFinalResult(
        String answerText,
        List<Map<String, Object>> sources,
        List<Map<String, Object>> toolsUsed,
        List<Map<String, Object>> vaultRefs,
        List<Map<String, Object>> createdRecords,
        boolean fallback,
        boolean partial,
        String status,
        String outcome,
        String route,
        String stopReason,
        String errorCode,
        int planRounds,
        int toolCallCount,
        long lastEventSeq,
        String metricsJson,
        List<Long> glossaryHitIds
) {
    public ChatFinalResult {
        answerText = answerText == null ? "" : answerText;
        sources = sources == null ? List.of() : List.copyOf(sources);
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
        vaultRefs = vaultRefs == null ? List.of() : List.copyOf(vaultRefs);
        createdRecords = createdRecords == null ? List.of() : List.copyOf(createdRecords);
        glossaryHitIds = glossaryHitIds == null ? List.of() : List.copyOf(glossaryHitIds);
        status = status == null ? "completed" : status;
        outcome = outcome == null ? "answer" : outcome;
        route = route == null ? "" : route;
        metricsJson = metricsJson == null ? "{}" : metricsJson;
    }

    public static ChatFinalResult legacyAnswer(String answer, List<Map<String, Object>> sources,
                                               List<Map<String, Object>> tools,
                                               List<Map<String, Object>> vaultRefs, String route) {
        return new ChatFinalResult(answer, sources, tools, vaultRefs, List.of(), false,
                false, "completed", "answer", route, "done", null, 0, 0, 0, "{}", List.of());
    }

    public static ChatFinalResult fallback(String answer, String reason, String route) {
        return new ChatFinalResult(answer, List.of(), List.of(), List.of(), List.of(), true,
                false, "completed", "fallback", route, reason, null, 0, 0, 0, "{}", List.of());
    }

    public static ChatFinalResult failed(String answer, String code, String reason, String route) {
        return new ChatFinalResult(answer, List.of(), List.of(), List.of(), List.of(),
                true, false, "failed", "answer", route, reason,
                code, 0, 0, 0, "{}", List.of());
    }
}
