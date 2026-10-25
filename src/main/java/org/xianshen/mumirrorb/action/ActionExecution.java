package org.xianshen.mumirrorb.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;

/** A handler reports actual effects, or durable job references while still executing. */
public record ActionExecution(String state, JsonNode result, String errorCode) {
    public ActionExecution {
        if (!Set.of("EXECUTING", "SUCCEEDED", "FAILED").contains(state))
            throw new IllegalArgumentException("Invalid execution state");
        Objects.requireNonNull(result, "execution result");
        if (!result.isObject()) throw new IllegalArgumentException("Execution result must be an object");
        if ("SUCCEEDED".equals(state)) validateOutcome(result);
        if ("EXECUTING".equals(state)) {
            JsonNode jobs = result.path("jobIds");
            if (!jobs.isArray() || jobs.isEmpty())
                throw new IllegalArgumentException("Async execution requires durable jobIds");
            for (JsonNode job : jobs) {
                if (!(job.isIntegralNumber() && job.asLong() > 0)
                        && !(job.isTextual() && !job.asText().isBlank()))
                    throw new IllegalArgumentException("Invalid durable job ID");
            }
        }
        if ("FAILED".equals(state) && (errorCode == null || errorCode.isBlank()))
            throw new IllegalArgumentException("Failed execution requires an errorCode");
        result = result.deepCopy();
    }

    public static ActionExecution applied(JsonNode result) { return success(result, "APPLIED"); }
    public static ActionExecution savedOnlyFallback(JsonNode result) { return success(result, "SAVED_ONLY_FALLBACK"); }
    public static ActionExecution executing(JsonNode durableJobs) { return new ActionExecution("EXECUTING", durableJobs, null); }
    public static ActionExecution failed(String errorCode, JsonNode result) { return new ActionExecution("FAILED", result, errorCode); }

    public static void validateOutcome(JsonNode result) {
        String outcome = result.path("outcome").asText();
        if (!Set.of("APPLIED", "SAVED_ONLY_FALLBACK").contains(outcome))
            throw new IllegalArgumentException("Successful result requires actual outcome");
    }

    private static ActionExecution success(JsonNode result, String outcome) {
        if (!(result instanceof ObjectNode)) throw new IllegalArgumentException("Execution result must be an object");
        ObjectNode actual = result.deepCopy();
        actual.put("outcome", outcome);
        if ("SAVED_ONLY_FALLBACK".equals(outcome)) actual.put("message", "已仅保存，未加入记忆");
        return new ActionExecution("SUCCEEDED", actual, null);
    }
}
