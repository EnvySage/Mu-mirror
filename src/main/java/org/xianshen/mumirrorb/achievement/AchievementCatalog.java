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
    private final Map<String,JsonNode> platingRules;

    public AchievementCatalog(ObjectMapper json, AchievementMetrics registry) throws IOException {
        byte[] bytes = new ClassPathResource("achievements/catalog.json").getContentAsByteArray();
        JsonNode catalog = json.readTree(bytes);
        StringBuilder identity=new StringBuilder(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        if (catalog.isObject() && catalog.path("files").isArray()) {
            var combined=json.createArrayNode();
            for (JsonNode file:catalog.get("files")) {
                String name=file.asText();
                if (!name.matches("catalog-[a-z]+\\.json")) throw new IllegalArgumentException("Invalid catalog shard");
                byte[] shard=new ClassPathResource("achievements/"+name).getContentAsByteArray();
                identity.append(new String(shard,java.nio.charset.StandardCharsets.UTF_8));
                JsonNode rows=json.readTree(shard);
                if (!rows.isArray()) throw new IllegalArgumentException("Invalid catalog shard");
                rows.forEach(combined::add);
            }
            catalog=combined;
        }
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
        byte[] plating=new ClassPathResource("achievements/plating-rules.json").getContentAsByteArray();
        identity.append(new String(plating,java.nio.charset.StandardCharsets.UTF_8));
        JsonNode challenges=json.readTree(plating);
        Map<String,JsonNode> hiddenRules=new LinkedHashMap<>();
        for (var fields=challenges.fields();fields.hasNext();) {
            var entry=fields.next();
            if (!codes.contains(entry.getKey()) || entry.getValue().path("ruleVersion").asInt()<1)
                throw new IllegalArgumentException("Invalid plating definition: "+entry.getKey());
            Set<String> used=AchievementRules.metrics(entry.getValue().get("rule"));
            for (String metric:used) if (!registry.supports(metric)) throw new IllegalArgumentException("Unknown plating metric: "+metric);
            dependencies.addAll(used);
            hiddenRules.put(entry.getKey(),entry.getValue());
        }
        platingRules=Map.copyOf(hiddenRules);
        definitions = List.copyOf(items);
        metrics = Set.copyOf(dependencies);
        revision = UUID.nameUUIDFromBytes(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public List<JsonNode> definitions() { return definitions; }
    public Set<String> metrics() { return metrics; }
    public String revision() { return revision; }
    public JsonNode platingRule(String code) { return platingRules.get(code); }
    public boolean publicCode(String code) {
        return definitions.stream().anyMatch(item -> code.equals(item.path("code").asText()) && item.path("enabled").asBoolean(true));
    }
    public ObjectNode display(JsonNode definition, boolean earned) {
        ObjectNode item = definition.deepCopy();
        item.remove(List.of("rule","ruleVersion","enabled"));
        item.put("plating",platingRules.containsKey(item.path("code").asText()));
        if (item.path("hidden").asBoolean() && !earned) {
            item.put("name","隐藏成就").put("english","CLASSIFIED").put("condition","获得后揭晓章面和解锁条件。");
            item.putNull("motif").putNull("image");
            item.remove(List.of("atlas","atlasIndex"));
        }
        return item;
    }
}
