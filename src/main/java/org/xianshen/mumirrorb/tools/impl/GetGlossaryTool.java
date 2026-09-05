package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * get_glossary 工具：个人词典解释列表——"XX 是什么意思（我的词）"类问题
 */
@Component
@RequiredArgsConstructor
public class GetGlossaryTool implements ToolExecutor {

    private final UserTermMapper termMapper;

    @Override
    public String name() {
        return "get_glossary";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "查询用户的个人词典：TA 给自己生活中的词赋予的含义（如\"论文\"=\"毕设RAG检索\"）。适合用户问\"我的XX是指什么/你理解的XX是什么\"。",
                "{\"term\": \"词条名（可选，缺省返回全部已生效词条）\"}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        String term = SearchRecordsTool.strOf(args.get("term"));
        List<UserTerm> terms = termMapper.selectConfirmedTop(userId, 30);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserTerm t : terms) {
            if (term != null && !term.isBlank()
                    && !t.getTerm().equalsIgnoreCase(term.trim())
                    && (t.getAliases() == null || !t.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(term.trim())))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", t.getTerm());
            m.put("description", t.getDescription());
            m.put("aliases", t.getAliases());
            m.put("confirmed_at", t.getLastConfirmedAt() == null ? null
                    : t.getLastConfirmedAt().format(DateTimeFormatter.ISO_LOCAL_DATE));
            items.add(m);
        }
        if (term != null && !term.isBlank() && items.isEmpty()) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + ":未收录「" + term.trim() + "」")
                    .payload(Map.of("term", term.trim(), "found", false))
                    .build();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("found", true);
        payload.put("terms", items);
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + items.size() + "个词条")
                .payload(payload)
                .build();
    }
}
