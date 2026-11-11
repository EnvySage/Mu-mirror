package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Small, validated rule vocabulary shared by live evaluation and backfill. */
public final class AchievementRules {
    private AchievementRules() {}

    public static Set<String> metrics(JsonNode rule) {
        Set<String> result = new HashSet<>();
        collect(rule, result, 0);
        return result;
    }

    private static void collect(JsonNode rule, Set<String> result, int depth) {
        if (rule == null || !rule.isObject() || depth > 4) throw new IllegalArgumentException("Invalid achievement rule");
        if (rule.has("metric")) {
            if (!"gte".equals(rule.path("operator").asText()) || !rule.path("value").canConvertToLong()
                    || rule.path("value").asLong() < 1 || rule.path("metric").asText().isBlank())
                throw new IllegalArgumentException("Invalid metric threshold");
            result.add(rule.get("metric").asText());
            return;
        }
        String operator = rule.has("all") ? "all" : "any";
        JsonNode children = rule.path(operator);
        if (!children.isArray() || children.isEmpty()) throw new IllegalArgumentException("Empty composite rule");
        children.forEach(child -> collect(child, result, depth + 1));
    }

    public static boolean matches(JsonNode rule, Map<String, Long> values) {
        if (rule.has("metric")) return values.getOrDefault(rule.get("metric").asText(), 0L) >= rule.get("value").asLong();
        if (rule.has("all")) {
            for (JsonNode child : rule.get("all")) if (!matches(child, values)) return false;
            return true;
        }
        for (JsonNode child : rule.get("any")) if (matches(child, values)) return true;
        return false;
    }
}
