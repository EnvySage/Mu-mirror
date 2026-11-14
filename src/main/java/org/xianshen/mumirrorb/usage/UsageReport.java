package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** Only whitelisted values cross this boundary. Raw upstream JSON is never persisted. */
public record UsageReport(boolean complete, List<Attempt> attempts) {
    public record Attempt(UUID id,String status,String provider,String model,Long inputTokens,
            Long outputTokens,Long totalTokens,BigDecimal credits,String creditUnit,String usageState,
            Long latencyMs,Integer httpStatus,Long cacheReadTokens,Long cacheCreationTokens,Long reasoningTokens) { }

    public static final class Parser {
        public static final int MAX_BYTES=48*1024, MAX_ATTEMPTS=100;
        private final ObjectMapper json;
        public Parser(ObjectMapper json) {
            this.json=json.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            this.json.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(4096).maxNumberLength(64).build());
        }
        public UsageReport parse(byte[] bytes,UUID invocation) {
            if(bytes==null || bytes.length>MAX_BYTES) return new UsageReport(false,List.of());
            List<Attempt> result=new ArrayList<>();
            try {
                JsonNode root=json.readTree(bytes);
                if(root==null || !root.isObject() || !root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt()
                        || root.path("version").intValue()!=1 || !invocation.equals(uuid(root.get("invocation_id")))
                        || !root.path("complete").isBoolean() || !root.path("attempts").isArray())
                    return new UsageReport(false,List.of());
                boolean complete=root.get("complete").booleanValue() && root.get("attempts").size()<=MAX_ATTEMPTS;
                Set<UUID> ids=new HashSet<>();
                for(JsonNode node:root.get("attempts")) {
                    if(result.size()==MAX_ATTEMPTS) break;
                    UUID id=uuid(node.get("id"));
                    if(!ids.add(id)) throw new IllegalArgumentException();
                    String status=oneOf(node,"status",Set.of("success","error","cancelled"));
                    String state=oneOf(node,"usage_state",Set.of("complete","partial","unknown"));
                    BigDecimal credits=decimal(node.get("credits"));
                    Long input=number(node.get("input_tokens")),output=number(node.get("output_tokens")),total=number(node.get("total_tokens"));
                    if(input==null&&output==null&&total==null&&credits==null)state="unknown";
                    String unit=credits==null ? null : nullableText(node.get("credit_unit"),32);
                    Long http=number(node.get("http_status"),599);
                    if(http!=null && http<100) throw new IllegalArgumentException();
                    result.add(new Attempt(id,status,text(node.get("provider"),128),text(node.get("model"),256),
                            input,output,total,
                            credits,credits==null?null:unit==null?"积分":unit,state,number(node.get("latency_ms")),
                            http==null?null:http.intValue(),number(node.get("cache_read_tokens")),
                            number(node.get("cache_creation_tokens")),number(node.get("reasoning_tokens"))));
                }
                return new UsageReport(complete,List.copyOf(result));
            } catch(Exception invalid) {
                return new UsageReport(false,List.copyOf(result));
            }
        }
        private static UUID uuid(JsonNode n) {
            String s=text(n,36); UUID id=UUID.fromString(s);
            if(!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
            return id;
        }
        private static String oneOf(JsonNode node,String field,Set<String> values) {
            String value=text(node.get(field),32);
            if(!values.contains(value)) throw new IllegalArgumentException();
            return value;
        }
        private static String text(JsonNode n,int max) {
            if(n==null || !n.isTextual() || n.textValue().length()>max
                    || n.textValue().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            return n.textValue();
        }
        private static String nullableText(JsonNode n,int max) { return n==null||n.isNull()?null:text(n,max); }
        private static Long number(JsonNode n) { return number(n,1_000_000_000_000L); }
        private static Long number(JsonNode n,long max) {
            if(n==null||n.isNull()) return null;
            if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()>max) throw new IllegalArgumentException();
            return n.longValue();
        }
        private static BigDecimal decimal(JsonNode n) {
            if(n==null||n.isNull()) return null;
            if(!n.isTextual()||!n.textValue().matches("[0-9]{1,26}(\\.[0-9]{1,12})?")) throw new IllegalArgumentException();
            return new BigDecimal(n.textValue());
        }
    }
}
