package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.service.TodoRegistryService;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolEffect;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只读待办清单：让对话能说出具体待办，而不是只有数量。 */
@Component
@RequiredArgsConstructor
public class ListTodosTool implements ToolExecutor {

    private static final int MAX_LIMIT = 50;
    private final TodoRegistryService todos;

    @Override
    public String name() {
        return "list_todos";
    }

    @Override
    public ToolEffect effect() {
        return ToolEffect.READ;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "列出用户的待办登记清单（只读）。问“还有哪些没完成的计划/待办具体是什么/某条待办状态”时使用。"
                        + "status=open 只列未完成（默认），completed 只列已完成，all 全部。"
                        + "需要修改、完成或删除待办时改用 prepare_todo。",
                "{\"status\": \"open|completed|all（可选，默认 open）\", \"limit\": 20（1..50）}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        args = args == null ? Map.of() : args;
        String status = SearchRecordsTool.strOf(args.get("status"));
        status = status == null || status.isBlank() ? "open" : status.trim().toLowerCase();
        int limit = Math.min(Math.max(SearchRecordsTool.intOf(args.get("limit"), 20), 1), MAX_LIMIT);

        List<TodoItemVO> all = todos.listAll(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TodoItemVO todo : all) {
            boolean completed = "completed".equalsIgnoreCase(todo.getCurrentStatus());
            if ("open".equals(status) && completed) continue;
            if ("completed".equals(status) && !completed) continue;
            if (!List.of("open", "completed", "all").contains(status)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("todo_id", todo.getId());
            item.put("title", todo.getTitle());
            item.put("status", todo.getCurrentStatus());
            if (todo.getNotes() != null) item.put("notes", todo.getNotes());
            if (todo.getDueAt() != null) item.put("due_at", todo.getDueAt());
            if (todo.getSourceExcerpt() != null) item.put("source_excerpt", todo.getSourceExcerpt());
            if (todo.getSourceChunkId() != null) item.put("source_chunk_id", todo.getSourceChunkId());
            if (todo.getLinkCount() != null) item.put("link_count", todo.getLinkCount());
            if (todo.getPendingSuggestionCount() != null && todo.getPendingSuggestionCount() > 0)
                item.put("pending_suggestions", todo.getPendingSuggestionCount());
            items.add(item);
            if (items.size() >= limit) break;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("status", status);
        payload.put("todos", items);
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + items.size() + "条（" + status + "）")
                .payload(payload)
                .build();
    }
}
