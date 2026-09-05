package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.service.VaultService;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * recall_item 工具：vault 文件详情 + 引用摘录（工具表第 8 行）——"那个 PDF 里写了什么"
 */
@Component
@RequiredArgsConstructor
public class RecallItemTool implements ToolExecutor {

    private final VaultService vaultService;

    @Override
    public String name() {
        return "recall_item";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "读取用户资产库中某文件的详情与内容摘录（仅已消化的文档有摘录）。适合\"那个文件里说了什么/打开我传的XX\"。",
                "{\"vault_item_id\": 123}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        long id = SearchRecordsTool.intOf(args.get("vault_item_id"), -1);
        if (id <= 0) {
            // 无 id 时引导先 find_item
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary(name() + ":缺少 vault_item_id（可先 find_item）")
                    .payload(Map.of("error", "需要 vault_item_id，可先用 find_item 定位文件"))
                    .build();
        }
        try {
            var vo = vaultService.recall(userId, id);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("item", vo);
            payload.put("quote", vo.getQuote());
            payload.put("digest_status", vo.getDigestStatus());
            if (!"done".equals(vo.getDigestStatus())) {
                payload.put("note", "文件尚未完成内容索引，仅元数据可答");
            }
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + ":" + vo.getOriginalName())
                    .payload(payload)
                    .build();
        } catch (Exception e) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary(name() + ":文件不存在")
                    .payload(Map.of("error", "文件不存在或已删除"))
                    .build();
        }
    }
}
