package org.xianshen.mumirrorb.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ChatMemoryMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/** A daily chat memory remains a proposal until this handler runs under explicit confirmation. */
@Component @RequiredArgsConstructor
public class MemoryActionHandler implements ActionHandler {
    private static final String ACCEPT = "memory.accept";
    private static final String EDIT = "memory.edit";
    private static final String FORGET = "memory.forget";
    private final ChatMemoryMapper memories;
    private final ChatMemoryService chatMemory;
    private final ObjectMapper json;

    @Override public Set<String> kinds() { return Set.of(ACCEPT, EDIT, FORGET); }

    @Override public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        if (ACCEPT.equals(kind)) {
            if (payload == null || !payload.isObject() || payload.size() != 1 || !payload.has("memoryDraft"))
                throw invalid("记忆操作仅允许 memoryDraft 字段");
            JsonNode draft = payload.get("memoryDraft");
            if (draft == null || !draft.isObject()) throw invalid("缺少记忆候选");
            chatMemory.validateDraft(userId, draft);
            ObjectNode preview = basePreview();
            preview.set("memoryDraft", draft.deepCopy());
            preview.put("activeBeforeConfirm", false);
            return preview;
        }
        if (!EDIT.equals(kind) && !FORGET.equals(kind)) throw invalid("记忆操作类型不合法");
        validatePayloadFields(kind, payload);
        UUID id = memoryId(payload);
        ChatMemory current = memories.findOwned(userId, id);
        long expectedVersion = payload.path("expectedVersion").asLong(-1);
        if (current == null || !"ACTIVE".equals(current.getState())) throw invalid("记忆已不存在或已遗忘");
        if (current.getVersion() != expectedVersion) throw new BusinessException(409, "记忆版本已变化，请刷新后重试");
        validateSourceClaims(payload, current);
        ObjectNode preview = basePreview();
        preview.put("memoryId", id.toString()).put("expectedVersion", expectedVersion);
        ObjectNode targetVersions = (ObjectNode) preview.get("targetVersions");
        targetVersions.put("memoryVersion", current.getVersion());
        preview.set("before", memoryView(current));
        if (EDIT.equals(kind)) {
            String content = payload.path("content").asText("").trim();
            String memoryKind = payload.path("kind").asText("").trim().toLowerCase(Locale.ROOT);
            if (content.isBlank() || content.length() > 500 || !Set.of("fact", "preference", "plan", "event").contains(memoryKind))
                throw invalid("修改后的记忆内容或类型无效");
            LocalDate eventDate = optionalDate(payload, "eventDate");
            OffsetDateTime expiresAt = optionalExpiry(payload, "expiresAt");
            ObjectNode after = json.createObjectNode().put("content", content).put("kind", memoryKind);
            if (eventDate != null) after.put("eventDate", eventDate.toString());
            if (expiresAt != null) after.put("expiresAt", expiresAt.toString());
            preview.set("after", after);
        } else {
            preview.put("effect", "FORGET_CHAT_SOURCES_AND_DERIVED_CONTEXT");
            String explanation = "确认遗忘后，相关聊天内容、每日总结和画像不再用于未来 AI 上下文；你已单独确认保存的记录、待办和成长贡献会保留。";
            preview.put("description", explanation);
            preview.put("explanation", explanation);
        }
        return preview;
    }

    @Override public JsonNode persistedPayload(String kind, JsonNode payload, JsonNode preview) {
        return payload.deepCopy();
    }

    @Override public void validateEdit(String kind, JsonNode original, JsonNode edited) {
        if (!ACCEPT.equals(kind)) {
            if (!Objects.equals(original.path("memoryId").asText(), edited.path("memoryId").asText())
                    || original.path("expectedVersion").asLong() != edited.path("expectedVersion").asLong()
                    || !Objects.equals(original.path("sourceSessionId").asText(), edited.path("sourceSessionId").asText())
                    || !Objects.equals(original.path("sourceMessageId").asText(), edited.path("sourceMessageId").asText()))
                throw invalid("记忆操作目标不可更换");
            return;
        }
        JsonNode before = original.path("memoryDraft");
        JsonNode after = edited.path("memoryDraft");
        if (!Objects.equals(before.path("memoryId").asText(), after.path("memoryId").asText())
                || !Objects.equals(before.path("sourceDate").asText(), after.path("sourceDate").asText())
                || !before.path("sourceMessageIds").equals(after.path("sourceMessageIds")))
            throw invalid("记忆候选来源不可更换");
    }

    @Override public void validatePreparation(String kind, JsonNode payload, JsonNode preview) {
        if (ACCEPT.equals(kind) && preview.path("activeBeforeConfirm").asBoolean(false))
            throw invalid("候选不得在确认前生效");
    }

    @Override @Transactional
    public ActionExecution execute(AuthorizedAction action) {
        if (EDIT.equals(action.getKind())) return executeEdit(action);
        if (FORGET.equals(action.getKind())) return executeForget(action);
        JsonNode draft = action.getPayload().path("memoryDraft");
        UUID sourceSessionId = chatMemory.validateDraft(action.getUserId(), draft);
        ChatMemory memory = new ChatMemory();
        memory.setId(UUID.fromString(draft.path("memoryId").asText()));
        memory.setUserId(action.getUserId());
        memory.setContent(draft.path("content").asText());
        memory.setKind(draft.path("kind").asText());
        memory.setSourceSessionId(sourceSessionId);
        memory.setSourceMessageIds(draft.path("sourceMessageIds").toString());
        memory.setSourceDate(LocalDate.parse(draft.path("sourceDate").asText()));
        if (draft.hasNonNull("eventDate") && !draft.path("eventDate").asText().isBlank())
            memory.setEventDate(LocalDate.parse(draft.path("eventDate").asText()));
        if (draft.hasNonNull("expiresAt") && !draft.path("expiresAt").asText().isBlank())
            memory.setExpiresAt(expiry(draft.path("expiresAt").asText()));
        memory.setState("ACTIVE");
        if (memories.insertConfirmed(memory) != 1) throw invalid("记忆候选已处理或已失效");
        ObjectNode result = json.createObjectNode().put("memoryId", memory.getId().toString())
                .put("outcome", "APPLIED").put("activeAfterConfirm", true);
        return ActionExecution.applied(result);
    }

    private ActionExecution executeEdit(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        UUID id = memoryId(payload);
        long expected = payload.path("expectedVersion").asLong(-1);
        LocalDate eventDate = optionalDate(payload, "eventDate");
        OffsetDateTime expiresAt = optionalExpiry(payload, "expiresAt");
        if (memories.updateActive(action.getUserId(), id, expected, payload.path("content").asText().trim(),
                payload.path("kind").asText().trim().toLowerCase(Locale.ROOT), eventDate, expiresAt) != 1)
            throw new BusinessException(409, "记忆已变化或已遗忘，请刷新后重试");
        chatMemory.invalidateEditedMemorySources(action.getUserId(), id, expected + 1);
        return ActionExecution.applied(json.createObjectNode().put("memoryId", id.toString())
                .put("outcome", "APPLIED").put("version", expected + 1));
    }

    private ActionExecution executeForget(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        chatMemory.forget(action.getUserId(), memoryId(payload), payload.path("expectedVersion").asLong(-1));
        return ActionExecution.applied(json.createObjectNode().put("memoryId", payload.path("memoryId").asText())
                .put("outcome", "APPLIED").put("forgotten", true));
    }

    private ObjectNode basePreview() {
        ObjectNode preview = json.createObjectNode().put("requiresConfirmation", true);
        preview.set("targetVersions", json.createObjectNode());
        return preview;
    }

    private static void validatePayloadFields(String kind, JsonNode payload) {
        Set<String> allowed = EDIT.equals(kind)
                ? Set.of("memoryId", "expectedVersion", "content", "kind", "eventDate", "expiresAt", "sourceSessionId", "sourceMessageId")
                : Set.of("memoryId", "expectedVersion", "sourceSessionId", "sourceMessageId");
        if (payload == null || !payload.isObject() || payload.fieldNames().hasNext() == false)
            throw invalid("记忆操作参数无效");
        payload.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid("记忆操作包含未知字段"); });
    }

    private void validateSourceClaims(JsonNode payload, ChatMemory memory) {
        if (payload.hasNonNull("sourceSessionId") && !memory.getSourceSessionId().toString().equals(payload.path("sourceSessionId").asText()))
            throw invalid("记忆来源会话不匹配");
        if (payload.hasNonNull("sourceMessageId")) {
            try {
                List<Long> ids = json.readValue(memory.getSourceMessageIds(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                if (!ids.contains(payload.path("sourceMessageId").asLong(-1))) throw invalid("记忆来源消息不匹配");
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { throw invalid("记忆来源无效"); }
        }
    }

    private ObjectNode memoryView(ChatMemory memory) {
        ObjectNode view = json.createObjectNode().put("content", memory.getContent()).put("kind", memory.getKind())
                .put("version", memory.getVersion());
        if (memory.getEventDate() != null) view.put("eventDate", memory.getEventDate().toString());
        if (memory.getExpiresAt() != null) view.put("expiresAt", memory.getExpiresAt().toString());
        return view;
    }

    private static UUID memoryId(JsonNode payload) {
        try { return UUID.fromString(payload.path("memoryId").asText()); }
        catch (Exception e) { throw invalid("记忆 ID 无效"); }
    }

    private static LocalDate optionalDate(JsonNode payload, String field) {
        if (!payload.hasNonNull(field) || payload.path(field).asText().isBlank()) return null;
        try { return LocalDate.parse(payload.path(field).asText()); }
        catch (Exception e) { throw invalid("日期格式无效"); }
    }

    private static OffsetDateTime optionalExpiry(JsonNode payload, String field) {
        if (!payload.hasNonNull(field) || payload.path(field).asText().isBlank()) return null;
        return expiry(payload.path(field).asText());
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, message); }

    private static OffsetDateTime expiry(String value) {
        try { return OffsetDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) {
            return LocalDate.parse(value).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        }
    }
}
