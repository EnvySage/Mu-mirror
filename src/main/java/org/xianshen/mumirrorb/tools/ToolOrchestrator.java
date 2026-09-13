package org.xianshen.mumirrorb.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PlanTools 编排器（toolcalling-vault-design.md 第 1 节方案 A Planner-Executor）
 *
 * <p>流程：question → Python PlanTools RPC（3s 超时）→ 计划 ≤2 步 → Java 逐工具执行（审计落 tool_calls）
 * → ToolResult 列表回塞 ChatRequest.tool_results → Python 渲染上下文块。</p>
 *
 * <p>零回归裁决（0.4）：PlanTools 失败/超时/空计划/未知工具/参数解析失败 → 任何一步都返回空结果，
 * 对话静默走现有 RAG 链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolOrchestrator {

    private final AiGrpcClient aiGrpcClient;
    private final ToolRegistry registry;
    private final AuditService auditService;
    private final GlossaryService glossaryService;
    private final VaultProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 规划 + 执行（全流程防御）
     *
     * @param sessionId 对话会话（审计关联；可空）
     * @return 执行结果列表（空 = 本轮无工具，对话走纯 RAG）
     */
    public List<CommonProto.ToolResult> planAndExecute(UUID userId, UUID sessionId, String question) {
        List<CommonProto.ToolResult> results = new ArrayList<>();
        try {
            // 0. 未配置 LLM 直接跳过（与每日总结幂等口径一致）
            if (!aiGrpcClient.hasLlmConfig(userId)) {
                return results;
            }
            // 1. 规划（Python PlanTools RPC，3s 超时；未上线/失败 → 空）
            MirrorChatProto.PlanToolsRequest request = MirrorChatProto.PlanToolsRequest.newBuilder()
                    .setQuestion(question)
                    .addAllGlossary(org.xianshen.mumirrorb.grpc.GlossaryProtoMapper
                            .toProtoList(glossaryService.confirmedForInjection(userId)))
                    .addAllTools(registry.toProtoSpecs())
                    .build();
            MirrorChatProto.PlanToolsReply reply = aiGrpcClient.planTools(userId, request,
                    props.getPlanToolsTimeoutMs());
            List<MirrorChatProto.PlannedCall> calls = reply.getCallsList();
            if (calls.isEmpty()) {
                log.debug("PlanTools 空计划，用户: {}", userId);
                return results;
            }

            // 2. 执行（≤2 步；未知工具/参数非法跳过）
            int executed = 0;
            // 步骤间传参：Planner 一次性产出 ≤2 步、看不到前一步的结果，
            // recall_item 需要的 vault_item_id 只能由执行器接上 find_item 的命中
            Long lastFoundItemId = null;
            for (MirrorChatProto.PlannedCall call : calls) {
                if (executed >= props.getMaxToolCalls()) {
                    break;
                }
                String toolName = call.getTool();
                var executor = registry.get(toolName);
                if (executor.isEmpty()) {
                    log.warn("PlanTools 计划含未知工具「{}」（跳过），用户: {}", toolName, userId);
                    auditService.record(userId, sessionId, toolName, null,
                            "unknown tool", false, 0);
                    continue;
                }
                long start = System.currentTimeMillis();
                Map<String, Object> args;
                try {
                    args = objectMapper.readValue(call.getArgsJson(),
                            new TypeReference<Map<String, Object>>() {
                            });
                } catch (Exception e) {
                    log.warn("PlanTools 工具「{}」参数解析失败（跳过）: {}", toolName, e.getMessage());
                    auditService.record(userId, sessionId, toolName, Map.of("raw", call.getArgsJson()),
                            "args parse error", false, System.currentTimeMillis() - start);
                    continue;
                }
                if (args == null) {
                    args = new java.util.LinkedHashMap<>();
                }
                // 步骤间传参：recall_item 没给 id 时自动接上一步 find_item 命中的第一个文件
                if ("recall_item".equals(toolName) && !hasPositiveVaultItemId(args)
                        && lastFoundItemId != null) {
                    args.put("vault_item_id", lastFoundItemId);
                    log.info("recall_item 未给 vault_item_id，自动接 find_item 命中 id={}", lastFoundItemId);
                }
                try {
                    ToolExecutionResult result = executor.get().execute(userId, args);
                    long latency = System.currentTimeMillis() - start;
                    auditService.record(userId, sessionId, toolName, args,
                            result.getSummary(), result.isSuccess(), latency);
                    // 记下 find_item 的首个命中，供后续 recall_item 取用
                    if (result.isSuccess() && "find_item".equals(toolName)) {
                        lastFoundItemId = firstVaultItemId(result.getPayload());
                    }
                    if (result.isSuccess()) {
                        executed++;
                        results.add(CommonProto.ToolResult.newBuilder()
                                .setTool(toolName)
                                .setSummary(nullSafe(result.getSummary()))
                                .setPayloadJson(objectMapper.writeValueAsString(
                                        result.getPayload() == null ? Map.of() : result.getPayload()))
                                .setSuccess(true)
                                .build());
                    } else {
                        // 失败结果也带上（LLM 知情后可改走 RAG 语义回答）
                        results.add(CommonProto.ToolResult.newBuilder()
                                .setTool(toolName)
                                .setSummary(nullSafe(result.getSummary()))
                                .setPayloadJson(objectMapper.writeValueAsString(
                                        result.getPayload() == null ? Map.of() : result.getPayload()))
                                .setSuccess(false)
                                .build());
                    }
                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - start;
                    log.warn("工具「{}」执行异常（跳过），用户: {}, 原因: {}", toolName, userId, e.getMessage());
                    auditService.record(userId, sessionId, toolName, args,
                            "execute error: " + e.getMessage(), false, latency);
                }
            }
            // 3. 兜底补读：Planner 只规划了 find_item 却没规划 recall_item 时，自动补一次。
            // 原因：find_item 只给"文件名 + 描述"，看不到正文；而"问某份材料"时读内容基本是必需动作，
            // 是否规划 recall_item 全看模型是否听话（实测经常只规划 find_item，用户就得到
            // "我只有文件名和元信息，具体内容看不到"）。这一步把它兜住，宁多读一次也不答不了。
            boolean plannedRecall = calls.stream()
                    .anyMatch(c -> "recall_item".equals(c.getTool()));
            java.util.Optional<ToolExecutor> recallExecutor = registry.get("recall_item");
            if (!plannedRecall && lastFoundItemId != null
                    && executed < props.getMaxToolCalls() && recallExecutor.isPresent()) {
                Map<String, Object> autoArgs = new java.util.LinkedHashMap<>();
                autoArgs.put("vault_item_id", lastFoundItemId);
                autoArgs.put("query", question);
                long start = System.currentTimeMillis();
                try {
                    ToolExecutionResult auto = recallExecutor.get().execute(userId, autoArgs);
                    auditService.record(userId, sessionId, "recall_item", autoArgs,
                            auto.getSummary(), auto.isSuccess(), System.currentTimeMillis() - start);
                    results.add(CommonProto.ToolResult.newBuilder()
                            .setTool("recall_item")
                            .setSummary(nullSafe(auto.getSummary()))
                            .setPayloadJson(objectMapper.writeValueAsString(
                                    auto.getPayload() == null ? Map.of() : auto.getPayload()))
                            .setSuccess(auto.isSuccess())
                            .build());
                    log.info("PlanTools 未规划 recall_item，已自动补读文件内容，item: {}, 成功: {}",
                            lastFoundItemId, auto.isSuccess());
                } catch (Exception e) {
                    log.warn("自动补读文件内容失败（不阻断回答），item: {}, 原因: {}",
                            lastFoundItemId, e.getMessage());
                }
            }
            log.info("PlanTools 完成，用户: {}, 计划 {} 步, 成功执行 {} 步", userId, calls.size(), executed);
        } catch (Exception e) {
            // 零回归：规划失败/超时/Python 未上线 → 空结果，对话照常
            log.info("PlanTools 不可用（跳过工具走 RAG），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
        }
        return results;
    }

    /** args 里是否给了有效的 vault_item_id（>0）——没有则允许执行器自动补 */
    private static boolean hasPositiveVaultItemId(Map<String, Object> args) {
        Object raw = args.get("vault_item_id");
        if (raw instanceof Number n) {
            return n.longValue() > 0;
        }
        if (raw == null) {
            return false;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** find_item payload 里第一个命中文件的 vault_item_id（无命中返回 null） */
    private static Long firstVaultItemId(Object payload) {
        if (!(payload instanceof Map<?, ?> m)) {
            return null;
        }
        if (m.get("items") instanceof List<?> items) {
            for (Object o : items) {
                Long id = vaultItemIdOf(o);
                if (id != null) {
                    return id;
                }
            }
        }
        return vaultItemIdOf(m.get("item"));
    }

    private static Long vaultItemIdOf(Object node) {
        if (node instanceof Map<?, ?> m) {
            Object raw = m.get("vault_item_id");
            if (raw instanceof Number n) {
                return n.longValue();
            }
            if (raw != null) {
                try {
                    return Long.parseLong(String.valueOf(raw).trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
