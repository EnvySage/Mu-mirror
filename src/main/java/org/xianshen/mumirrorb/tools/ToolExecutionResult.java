package org.xianshen.mumirrorb.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行结果（payload 由 Executor 自行序列化为 JSON 字符串）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {

    /** 成功与否（失败时 summary 带原因） */
    private boolean success;

    /** 输出摘要（如 "search_records:12条"；≤500 字符截断） */
    private String summary;

    /** 完整结果对象（Executor 返回 Map/List 结构，Orchestrator 统一序列化 payload_json） */
    private Object payload;
}
