package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import org.xianshen.mumirrorb.tools.*;
import java.util.Map;
import java.util.UUID;

/** Prepares a user-confirmable memory proposal; this tool has no direct activation or forget path. */
@Component @RequiredArgsConstructor
public class PrepareMemoryChangeTool implements ToolExecutor {
    private final ChatMemoryService memories;
    private static final String NAME = "prepare_memory_change";

    @Override public String name() { return NAME; }
    @Override public ToolEffect effect() { return ToolEffect.PREPARE_WRITE; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition(NAME,
                "准备一条聊天记忆候选卡，供用户编辑、确认或取消；不能直接激活或遗忘记忆。",
                "{content:string(1..500),kind:fact|preference|plan|event,eventDate?:yyyy-MM-dd,expiresAt?:date-or-time}", effect());
    }
    @Override public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        throw new IllegalArgumentException("记忆候选准备需要可信的当前用户消息上下文");
    }
    @Override public ToolExecutionResult execute(UUID userId, Map<String, Object> args, ToolCallContext context) {
        if (context == null || context.sessionId() == null || context.sourceMessageId() == null || context.prepareKey() == null)
            throw new IllegalArgumentException("缺少可信用户消息上下文");
        var proposal = memories.prepareCandidate(userId, context.sessionId(), context.sourceMessageId(),
                context.prepareKey(), args);
        return ToolExecutionResult.builder().success(true).summary("记忆候选卡已准备，确认前不会生效")
                .payload(Map.of("proposalId", proposal.getId().toString(), "proposal", proposal, "state", proposal.getState()))
                .build();
    }
}
