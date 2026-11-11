package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

@Component
public class AchievementCatalog {
    private final List<JsonNode> definitions;
    private final Set<String> metrics;
    private final String revision;

    public AchievementCatalog(ObjectMapper json, AchievementMetrics registry) throws IOException {
        byte[] bytes = new ClassPathResource("achievements/catalog.json").getContentAsByteArray();
        JsonNode catalog = json.readTree(bytes);
        if (!catalog.isArray() || catalog.isEmpty()) throw new IllegalArgumentException("Empty achievement catalog");
        Set<String> codes = new HashSet<>();
        Set<String> dependencies = new HashSet<>();
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode entry : catalog) {
            String code = entry.path("code").asText();
            if (!code.matches("[A-Z]+-[0-9]{2,}") || !codes.add(code)
                    || entry.path("difficulty").asInt() < 1 || entry.path("difficulty").asInt() > 3
                    || entry.path("ruleVersion").asInt() < 1 || entry.path("name").asText().isBlank()
                    || entry.path("group").path("id").asText().isBlank())
                throw new IllegalArgumentException("Invalid achievement definition: " + code);
            Set<String> used = AchievementRules.metrics(entry.path("rule"));
            for (String metric : used) if (!registry.supports(metric)) throw new IllegalArgumentException("Unknown achievement metric: " + metric);
            dependencies.addAll(used);
            items.add(entry);
        }
        definitions = List.copyOf(items);
        metrics = Set.copyOf(dependencies);
        revision = UUID.nameUUIDFromBytes(bytes).toString();
    }

    public List<JsonNode> definitions() { return definitions; }
    public Set<String> metrics() { return metrics; }
    public String revision() { return revision; }
    public boolean publicCode(String code) {
        return definitions.stream().anyMatch(item -> code.equals(item.path("code").asText()) && item.path("enabled").asBoolean(true));
    }
    public ObjectNode display(JsonNode definition, boolean earned) {
        ObjectNode item = definition.deepCopy();
        item.remove(List.of("rule","ruleVersion","enabled"));
        if (item.path("hidden").asBoolean() && !earned) {
            item.put("name","隐藏成就").put("english","CLASSIFIED").put("condition","获得后揭晓章面和解锁条件。");
            item.putNull("motif").putNull("image");
        }
        return item;
    }
}
