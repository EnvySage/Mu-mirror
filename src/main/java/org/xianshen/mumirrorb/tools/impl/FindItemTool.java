package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.service.VaultService;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * find_item 工具：vault 三层检索（工具表第 7 行）——"我之前传过开题报告吗/昨天传的图片"
 */
@Component
@RequiredArgsConstructor
public class FindItemTool implements ToolExecutor {

    private final VaultService vaultService;

    @Override
    public String name() {
        return "find_item";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "在用户资产库（上传的文件）里找文件。适合\"我传过的XX在哪/帮我找那个PDF/昨天传的图片\"。",
                "{\"query\": \"关键词\", \"type\": \"document|image|audio（可选）\", \"days\": 7（可选）}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        String query = SearchRecordsTool.strOf(args.get("query"));
        String type = SearchRecordsTool.strOf(args.get("type"));
        Integer days = args.get("days") == null ? null : SearchRecordsTool.intOf(args.get("days"), -1);

        List<org.xianshen.mumirrorb.pojo.VO.VaultItemVO> items =
                vaultService.find(userId, query, type, days != null && days > 0 ? days : null);

        List<Map<String, Object>> cards = new ArrayList<>();
        for (var vo : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vault_item_id", vo.getId());
            m.put("display_name", vo.getOriginalName());
            m.put("file_type", vo.getFileType());
            m.put("size", vo.getSizeBytes());
            m.put("digest_status", vo.getDigestStatus());
            m.put("description", vo.getDescription());
            m.put("created_at", vo.getCreatedAt() == null ? null : vo.getCreatedAt().toString());
            m.put("match_layer", vo.getMatchLayer());
            cards.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", cards.size());
        payload.put("items", cards);
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + cards.size() + "个文件")
                .payload(payload)
                .build();
    }
}
