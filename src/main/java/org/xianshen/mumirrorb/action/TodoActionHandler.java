package org.xianshen.mumirrorb.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistry;
import org.xianshen.mumirrorb.pojo.DTO.TodoCommandDTO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import java.time.OffsetDateTime;
import java.util.*;

/** Prepares previews without domain writes; only AuthorizedAction reaches execution. */
@Component
@RequiredArgsConstructor
public class TodoActionHandler implements ActionHandler {
    private final TodoRegistryService todos;
    private final ObjectMapper json;
    private static final Set<String> KINDS = Set.of("todo.create", "todo.update", "todo.complete", "todo.delete");
    private static final Set<String> FIELDS = Set.of("todoId", "expectedTargetVersion", "title", "notes", "dueAt", "currentStatus");

    @Override public Set<String> kinds() { return KINDS; }

    @Override
    public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
        validatePayload(kind, payload);
        TodoRegistry before = "todo.create".equals(kind) ? null : todos.ownedSnapshot(userId, payload.get("todoId").longValue());
        TodoCommandDTO command = "todo.delete".equals(kind) ? null : command(kind, payload, before);
        ObjectNode preview = json.createObjectNode();
        ObjectNode versions = preview.putObject("targetVersions");
        // Stable across JSONB round trips (Jackson otherwise turns small LongNodes into IntNodes).
        if (before != null) versions.put(String.valueOf(before.getId()), String.valueOf(before.getVersion()));
        preview.set("before", before == null ? json.nullNode() : state(before.getTitle(), before.getCurrentStatus(), before.getNotes(), before.getDueAt()));
        preview.set("after", command == null ? json.nullNode() : state(command.getTitle(), command.getCurrentStatus(), command.getNotes(), command.getDueAt()));
        preview.putArray("effects").add(kind);
        preview.put("notificationCapability", "NONE");
        preview.put("targetDeleted", before != null && before.getDeletedAt() != null);
        return preview;
    }

    @Override
    public void validatePreparation(String kind, JsonNode payload, JsonNode preview) {
        if (preview.path("targetDeleted").asBoolean()) throw new BusinessException(4041, "待办不存在");
        if (!"todo.create".equals(kind)) {
            long current = preview.path("targetVersions").path(payload.get("todoId").asText()).asLong();
            if (current != payload.get("expectedTargetVersion").longValue())
                throw new BusinessException(409, "待办已变更，请刷新后重新准备");
        }
    }

    @Override
    public ActionExecution execute(AuthorizedAction action) {
        JsonNode payload = action.getPayload();
        validatePayload(action.getKind(), payload);
        Long id = payload.has("todoId") ? payload.get("todoId").longValue() : null;
        ObjectNode result = json.createObjectNode();
        if ("todo.delete".equals(action.getKind())) {
            todos.deleteTodo(id, action.getUserId(), payload.get("expectedTargetVersion").longValue());
            result.put("todoId", id).put("deleted", true);
        } else {
            TodoRegistry before = id == null ? null : todos.ownedActive(action.getUserId(), id);
            TodoCommandDTO command = command(action.getKind(), payload, before);
            TodoItemVO item = id == null ? todos.createDirect(action.getUserId(), command)
                    : todos.updateDirect(action.getUserId(), id, payload.get("expectedTargetVersion").longValue(), command);
            result.put("todoId", item.getId()).put("version", item.getVersion()).put("currentStatus", item.getCurrentStatus());
        }
        result.put("effect", action.getKind());
        return ActionExecution.applied(result);
    }

    private void validatePayload(String kind, JsonNode payload) {
        if (!KINDS.contains(kind) || payload == null || !payload.isObject()) throw invalid("待办操作非法");
        payload.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw invalid("待办参数不允许: " + field); });
        if ("todo.create".equals(kind)) {
            if (payload.has("todoId") || payload.has("expectedTargetVersion")) throw invalid("新建待办不能指定目标");
        } else {
            positive(payload, "todoId"); positive(payload, "expectedTargetVersion");
        }
        for (String field : List.of("title", "notes", "dueAt", "currentStatus")) {
            if (payload.hasNonNull(field) && !payload.get(field).isTextual()) throw invalid(field + " 必须为文本");
        }
        if (payload.hasNonNull("title") && (payload.get("title").asText().isBlank() || payload.get("title").asText().trim().length() > 200))
            throw invalid("待办标题须为 1..200 字");
        if (payload.hasNonNull("currentStatus") && !Set.of("not_started", "in_progress", "completed").contains(payload.get("currentStatus").asText()))
            throw invalid("待办状态非法");
        if ("todo.complete".equals(kind) && payload.hasNonNull("currentStatus") && !"completed".equals(payload.get("currentStatus").asText()))
            throw invalid("完成操作的状态必须为 completed");
    }

    private TodoCommandDTO command(String kind, JsonNode payload, TodoRegistry before) {
        TodoCommandDTO command = new TodoCommandDTO();
        command.setTitle(payload.hasNonNull("title") ? payload.get("title").asText() : before == null ? null : before.getTitle());
        command.setNotes(payload.has("notes") ? text(payload.get("notes")) : before == null ? null : before.getNotes());
        try {
            command.setDueAt(payload.has("dueAt") ? (payload.get("dueAt").isNull() ? null : OffsetDateTime.parse(payload.get("dueAt").asText()))
                    : before == null ? null : before.getDueAt());
        } catch (RuntimeException e) { throw invalid("dueAt 需要包含时区的 ISO 日期时间"); }
        command.setCurrentStatus("todo.complete".equals(kind) ? "completed" : payload.hasNonNull("currentStatus") ? payload.get("currentStatus").asText()
                : before == null ? "not_started" : before.getCurrentStatus());
        command.validate();
        return command;
    }

    private ObjectNode state(String title, String status, String notes, OffsetDateTime dueAt) {
        ObjectNode state = json.createObjectNode().put("title", title).put("currentStatus", status).put("notes", notes);
        state.put("dueAt", dueAt == null ? null : dueAt.toString()); return state;
    }
    private static String text(JsonNode value) { return value == null || value.isNull() ? null : value.asText(); }
    private static void positive(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw invalid(field + " 必须为正整数");
    }
    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
