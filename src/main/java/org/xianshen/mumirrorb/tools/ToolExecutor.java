package org.xianshen.mumirrorb.tools;

import java.util.Map;
import java.util.UUID;

/** Java-owned tools declare READ or PREPARE_WRITE; no submission tool exists. */
public interface ToolExecutor {

    /**
     * 工具名（注册表 key，= PlannedCall.tool）
     */
    String name();

    ToolEffect effect();

    default ToolExecutionResult execute(UUID userId, Map<String, Object> args, ToolCallContext context) throws Exception {
        if (effect() != ToolEffect.READ) throw new IllegalStateException("Preparation requires a contextual executor");
        return execute(userId, args);
    }

    /**
     * 定义（进 PlanToolsRequest.tools）
     */
    ToolDefinition definition();

    /**
     * 执行
     *
     * @param userId 用户（工具一律以当前用户身份查库，隔离天然成立）
     * @param args   PlanTools 解析出的参数 JSON
     * @return 结果（summary 进 meta.tools_used / tool_calls.result_summary；
     *              payload 进 ChatRequest.tool_results.payload_json）
     * @throws Exception 执行失败（Executor 捕获记审计后按失败结果继续，不让单工具炸对话）
     */
    ToolExecutionResult execute(UUID userId, Map<String, Object> args) throws Exception;
}
