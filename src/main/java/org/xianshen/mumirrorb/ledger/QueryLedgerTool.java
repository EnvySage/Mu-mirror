package org.xianshen.mumirrorb.ledger;

import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolEffect;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only ledger access for the legacy chat engine. */
@Component
public class QueryLedgerTool implements ToolExecutor {
    private static final Set<String> FIELDS = Set.of("mode", "start", "end", "q", "recordId");
    private final LedgerQueries ledger;

    public QueryLedgerTool(LedgerQueries ledger) {
        this.ledger = ledger;
    }

    @Override public String name() { return "query_ledger"; }
    @Override public ToolEffect effect() { return ToolEffect.READ; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "只读查询自己的账本。问收支、消费分类、AA 回款金额时用 summary；找具体哪笔或查看一条聚餐用 records 或 record。"
                + "给出准确区间，金额来自账本。记录链接是 /ledger?record=记录ID。",
                "{mode:summary|records|record,start?:YYYY-MM-DD,end?:YYYY-MM-DD,q?:string,recordId?:uuid}");
    }

    @Override
    public ToolExecutionResult execute(UUID owner, Map<String, Object> args) {
        if (args == null || !FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("账目查询参数无效");
        Object value = switch (String.valueOf(args.get("mode"))) {
            case "summary" -> ledger.brief(owner, date(args.get("start")), date(args.get("end")));
            case "records" -> ledger.list(owner, date(args.get("start")), date(args.get("end")),
                    args.get("q") instanceof String query ? query : "", 15);
            case "record" -> ledger.get(owner, UUID.fromString(String.valueOf(args.get("recordId"))));
            default -> throw new IllegalArgumentException("请选择账目查询方式");
        };
        return ToolExecutionResult.builder().success(true).summary("已从账本读取统计或明细")
                .payload(Map.of("ledger", value)).build();
    }

    private static LocalDate date(Object input) {
        return input == null ? null : LocalDate.parse(String.valueOf(input));
    }
}
