package org.xianshen.mumirrorb.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import org.xianshen.mumirrorb.tools.*;
import java.util.*;

/** The model can prepare a persisted card; it receives no submission capability. */
@Component
@RequiredArgsConstructor
public class PrepareTodoTool implements ToolExecutor {
    private final ActionProposalService actions;
    private final TodoRegistryService todos;
    private final ObjectMapper json;
    private static final Set<String> FIELDS = Set.of("kind", "todoId", "expectedTargetVersion", "title", "notes", "dueAt", "currentStatus");
    @Override public String name() { return "prepare_todo"; }
    @Override public ToolEffect effect() { return ToolEffect.PREPARE_WRITE; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition(name(), "准备待办新建/修改/完成/删除操作卡，等待用户确认；不会执行或推送提醒。目标不明确时返回可选择待办。",
                "{kind:todo.create|todo.update|todo.complete|todo.delete,todoId?:integer,expectedTargetVersion?:integer,title?:string(1..200),notes?:string,dueAt?:ISO8601,currentStatus?:not_started|in_progress|completed}", effect());
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        validateArgs(args);
        throw new IllegalArgumentException("待办准备需要已保存的用户消息上下文");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args, ToolCallContext context) {
        validateArgs(args);
        if (context == null || context.sessionId() == null || context.sourceMessageId() == null || context.prepareKey() == null)
            throw new IllegalArgumentException("缺少可信用户消息上下文");
        String kind = String.valueOf(args.get("kind"));
        if (!Set.of("todo.create", "todo.update", "todo.complete", "todo.delete").contains(kind))
            throw new IllegalArgumentException("未知待办操作");
        if (!"todo.create".equals(kind) && !args.containsKey("todoId")) {
            return ToolExecutionResult.builder().success(true).summary("请选择要操作的待办，尚未准备或执行修改")
                    .payload(Map.of("requiresTargetSelection", true, "todos", todos.listAll(userId))).build();
        }
        Map<String,Object> payload = new LinkedHashMap<>(args); payload.remove("kind");
        var card = actions.prepare(userId, new ActionPrepareDTO(kind, json.valueToTree(payload), context.prepareKey(), context.sessionId(), context.sourceMessageId()));
        String summary = switch (card.getState()) {
            case "PENDING" -> "已准备待办操作卡，等待用户确认；尚未执行";
            case "CANCELLED" -> "该操作卡已取消，未执行；本轮重试不会重新创建";
            case "SUCCEEDED" -> "该操作卡已完成，请使用卡片中的实际结果";
            case "EXECUTING" -> "该操作卡正在执行，请查询实际状态";
            case "STALE" -> "该操作卡目标已变化，需要重新查看";
            default -> "该操作卡未成功，请查看卡片状态";
        };
        return ToolExecutionResult.builder().success(true).summary(summary)
                .payload(Map.of("proposalId", card.getId().toString(), "proposal", card, "state", card.getState())).build();
    }

    private void validateArgs(Map<String,Object> args) {
        if (args == null || !FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("不允许身份、确认凭据或未知待办参数");
    }
}
