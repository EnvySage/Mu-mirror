package org.xianshen.mumirrorb.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.tools.*;
import java.util.*;

/** Assistant may prepare a record card, but cannot save or authorize it. */
@Component
@RequiredArgsConstructor
public class PrepareRecordTool implements ToolExecutor {
    private final ActionProposalService actions;
    private final ObjectMapper json;
    private static final Set<String> FIELDS = Set.of("kind", "rawContent", "mode", "sourceRecordId");

    @Override public String name() { return "prepare_record"; }
    @Override public ToolEffect effect() { return ToolEffect.PREPARE_WRITE; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition(name(), "准备创建或整理记录的可编辑操作卡；等待用户确认后才保存或授权。",
                "{kind:record.create|record.organize,rawContent?:string(1..2000),mode?:ONLY_SAVE|AI_ASSISTED,sourceRecordId?:integer}", effect());
    }

    @Override public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        throw new IllegalArgumentException("记录准备需要已保存的用户消息上下文");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args, ToolCallContext context) {
        if (args == null || !FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("不允许身份、确认凭据或未知记录参数");
        if (context == null || context.sessionId() == null || context.sourceMessageId() == null || context.prepareKey() == null)
            throw new IllegalArgumentException("缺少可信用户消息上下文");
        String kind = Objects.toString(args.get("kind"), "");
        if (!Set.of("record.create", "record.organize").contains(kind)) throw new IllegalArgumentException("未知记录操作");
        Object raw = args.get("rawContent");
        if ("record.create".equals(kind) && (!(raw instanceof String text) || text.isBlank() || text.length() > 2000))
            throw new IllegalArgumentException("记录内容不能为空且不能超过 2000 字");
        if (args.get("mode") != null && !Set.of("ONLY_SAVE", "AI_ASSISTED").contains(String.valueOf(args.get("mode"))))
            throw new IllegalArgumentException("记录模式非法");
        if ("record.organize".equals(kind) && !(args.get("sourceRecordId") instanceof Number))
            throw new IllegalArgumentException("整理操作缺少记录 ID");

        Map<String, Object> payload = new LinkedHashMap<>(args);
        var proposal = actions.prepare(userId, new ActionPrepareDTO(kind, json.valueToTree(payload),
                context.prepareKey(), context.sessionId(), context.sourceMessageId()));
        return ToolExecutionResult.builder().success(true).summary("已准备记录操作卡，尚未保存或授权")
                .payload(Map.of("proposalId", proposal.getId().toString(), "proposal", proposal, "state", proposal.getState())).build();
    }
}
