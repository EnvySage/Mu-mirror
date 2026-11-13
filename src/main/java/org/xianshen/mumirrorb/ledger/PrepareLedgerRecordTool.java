package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.tools.ToolCallContext;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolEffect;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class PrepareLedgerRecordTool implements ToolExecutor {
    private static final Set<String> FIELDS = Set.of("title", "description", "flows", "recordId");
    private final ActionProposalService actions;
    private final ObjectMapper json;

    public PrepareLedgerRecordTool(ActionProposalService actions, ObjectMapper json) {
        this.actions = actions;
        this.json = json;
    }

    @Override public String name() { return "prepare_ledger_record"; }
    @Override public ToolEffect effect() { return ToolEffect.PREPARE_WRITE; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "用户明确要求记今天的收支时，准备一张可编辑的记账卡，等待确认。缺少金额先问。"
                + "一件事可包含多笔流水。补 AA 回款用 RECOVERY，有明确旧账目标才传 recordId。"
                + "只记今天发生的流水，不猜测日期或把历史改为今天。分类可用 food/transport/study/work/life/home/health/fun/salary/other。",
                "{title?:string,description?:string,recordId?:uuid,flows:[{kind:EXPENSE|INCOME|RECOVERY,amount:decimal,categoryId?:string,occurredOn?:YYYY-MM-DD,note?:string,relatedExpenseId?:uuid}]}",
                effect());
    }

    @Override
    public ToolExecutionResult execute(UUID owner, Map<String, Object> args) {
        throw new IllegalArgumentException("记账候选需要可信对话消息");
    }

    @Override
    public ToolExecutionResult execute(UUID owner, Map<String, Object> args, ToolCallContext context) {
        if (context == null || context.sessionId() == null || context.sourceMessageId() == null
                || context.prepareKey() == null || args == null || !FIELDS.containsAll(args.keySet())) {
            throw new IllegalArgumentException("记账候选缺少可信对话消息或参数不合法");
        }
        var card = actions.prepare(owner, new ActionPrepareDTO(LedgerActionHandler.KIND, json.valueToTree(args),
                context.prepareKey(), context.sessionId(), context.sourceMessageId()));
        return ToolExecutionResult.builder().success(true).summary("记账卡已准备，确认后入账")
                .payload(Map.of("proposalId", card.getId().toString(), "proposal", card, "state", card.getState())).build();
    }
}
