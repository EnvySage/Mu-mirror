package org.xianshen.mumirrorb.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** User-facing text comes from the actual preparation result, never another model call. */
public record PreparedActionReply(String text, String outcome) {
    public static PreparedActionReply from(String tool, boolean success, JsonNode payload) {
        if (!success || payload == null || !payload.isObject()) return failed();
        JsonNode proposal = payload.path("proposal");
        if (proposal.isObject() && !proposal.path("id").asText("").isBlank()) {
            String text = switch (proposal.path("state").asText("")) {
                case "PENDING" -> pendingText(tool);
                case "CANCELLED" -> "这张操作卡已取消，未执行；本次没有重新创建。";
                case "SUCCEEDED" -> "这张操作卡此前已执行完成，具体结果请查看卡片。";
                case "EXECUTING" -> "这张操作卡正在执行，请查看卡片中的实际状态。";
                case "STALE" -> "这张操作卡的目标已发生变化，请重新查看后发起操作。";
                case "FAILED" -> "这张操作卡执行失败，请查看卡片中的原因。";
                default -> null;
            };
            return text == null ? failed() : new PreparedActionReply(text, "action_prepared");
        }
        if (payload.path("requiresTargetSelection").asBoolean(false)) {
            JsonNode todos = payload.path("todos");
            if (!todos.isArray() || todos.isEmpty()) {
                return new PreparedActionReply("我没找到待处理的待办，目前没有做任何改动。", "clarification");
            }
            StringBuilder text = new StringBuilder("你想处理哪一条待办？我找到这些：");
            int count = 0;
            for (JsonNode todo : todos) {
                if (++count > 8) break;
                text.append("\n").append(count).append(". #").append(todo.path("id").asText(""))
                        .append(" ").append(todo.path("title").asText("（无标题）"));
                String status = todo.path("currentStatus").asText("");
                if (!status.isBlank()) text.append("（").append(status).append("）");
            }
            text.append("\n告诉我序号就行。");
            return new PreparedActionReply(text.toString(), "clarification");
        }
        return failed();
    }

    private static PreparedActionReply failed() {
        return new PreparedActionReply("操作卡未能生成，本轮操作没有完成，请重试。", "action_failed");
    }

    private static String pendingText(String tool) {
        return switch (tool) {
            case "prepare_record" -> "我把内容放进记录卡了，你看一眼；确认后我再保存。";
            case "prepare_todo" -> "待办操作卡已经备好，确认后才会改动。";
            case "prepare_memory_change" -> "我先把它放进记忆候选卡，你确认后它才会生效。";
            case "prepare_growth_adjustment" -> "成长调整卡已经备好，确认后才会记分。";
            default -> "操作卡已经备好，确认后才会执行。";
        };
    }
}
