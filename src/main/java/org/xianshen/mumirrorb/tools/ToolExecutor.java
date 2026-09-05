package org.xianshen.mumirrorb.tools;

import java.util.Map;
import java.util.UUID;

/**
 * 工具执行器接口（8 只读工具 + B1/B2 扩展位；toolcalling-vault-design.md 第 2 节）
 *
 * <p>工具执行权在 Java（裁决 0.1）：查库组装摘要，Python 只做计划与渲染。
 * 实现必须幂等只读（写操作红线：上传=显式动作，对话内删除/覆盖需确认卡——首批不开放）。</p>
 */
public interface ToolExecutor {

    /**
     * 工具名（注册表 key，= PlannedCall.tool）
     */
    String name();

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
