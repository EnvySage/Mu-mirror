package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.service.VaultService;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * save_item 工具：vault 资产关联确认（文件已上传后的对话内引用确认）
 *
 * <p>写操作红线遵守：上传是显式动作免确认；本工具只读——确认某文件"已保存"状态并回卡片信息。
 * 对话内删除/覆盖必须确认卡（二期）。</p>
 */
@Component
@RequiredArgsConstructor
public class SaveItemTool implements ToolExecutor {

    private final VaultService vaultService;

    @Override
    public String name() {
        return "save_item";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "确认用户资产库中某文件已保存（返回文件卡信息）。文件上传本身走上传接口，本工具用于对话内确认。",
                "{\"vault_item_id\": 123}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        long id = SearchRecordsTool.intOf(args.get("vault_item_id"), -1);
        if (id <= 0) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary(name() + ":缺少 vault_item_id")
                    .payload(Map.of("error", "缺少 vault_item_id 参数"))
                    .build();
        }
        try {
            var vo = vaultService.recall(userId, id);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("saved", true);
            payload.put("item", vo);
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + ":" + vo.getOriginalName())
                    .payload(payload)
                    .build();
        } catch (Exception e) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary(name() + ":文件不存在")
                    .payload(Map.of("saved", false, "error", "文件不存在或已删除"))
                    .build();
        }
    }
}
