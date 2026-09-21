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
import java.util.Iterator;
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
 *
 * <p><b>两条路径并存</b>（chat-loop-design.md §4.1）：
 * {@link #loopAndExecute} 是 Java 驱动的多步循环（{@code vault.chat-loop-enabled=true}，默认），
 * {@link #planAndExecute} 是旧的单次规划，保留作回滚路径——两者互不影响，旧路径一行不改。</p>
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

    /** 循环末尾兜底补读的审计标记（tool_calls 里区分"模型自己续的"和"执行器补的"） */
    public static final String AUTO_BACKSTOP_PREFIX = "[auto-backstop] ";

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

    // ==================== 对话 Agent 循环（chat-loop-design.md §4.1）====================

    /**
     * 单步回调：让 {@code ChatServiceImpl} 在循环<b>进行中</b>就把进展推给前端。
     *
     * <p>这是"消除黑屏"的关键接口：{@link #onThinking(String)} 必须在每收到一块
     * thinking 增量时<b>立刻</b>被调用（不允许攒完一次性回调），否则用户还是干等。</p>
     */
    public interface StepListener {

        /** 规划思考增量（逐块，实时）→ 调用方转 SSE thinking 事件 */
        void onThinking(String delta);

        /**
         * 一步工具执行完毕 → 调用方转 SSE meta{tools_used}
         *
         * @param cumulativeToolSummaries <b>累积</b>的成功工具 summary 列表（前端 meta.tools_used
         *                                是覆盖式赋值，每次发全量，芯片自然增长）
         */
        void onStepDone(List<String> cumulativeToolSummaries);
    }

    /**
     * 循环规划 + 执行（chat-loop-design.md §1/§4.1）
     *
     * <p>Java 驱动循环：每步调一次 Python {@code PlanNextStep}（服务端流），
     * 逐块透传 thinking，终帧拿 calls/done；执行 calls 落审计并累积 previous_results，
     * 下一步把结果带回去让模型自己判断"材料够不够"。</p>
     *
     * <p><b>六条终止条件</b>（任一命中即停）：</p>
     * <ol>
     *   <li>终帧 {@code done == true}（模型自评材料够了）</li>
     *   <li>终帧 {@code calls} 为空</li>
     *   <li>{@code step > vault.max-loop-steps}</li>
     *   <li>循环累计耗时 &gt; {@code vault.loop-budget-ms}（规划 + 工具执行合计）</li>
     *   <li>同工具 + 同参数指纹重复 → 立即中断（防原地打转烧钱）</li>
     *   <li>单步流式中断 / 终帧缺失 / 解析失败 → <b>不重试</b>，带着已有结果退出</li>
     * </ol>
     *
     * <p>零回归（裁决 0.4）：任何一步失败都只是退出循环，<b>绝不抛给上层</b>——
     * 已经拿到的结果照常进 Chat；一步都没成功则返回空列表，退化成纯 RAG。</p>
     *
     * <p>与旧 {@link #planAndExecute} 的差别之一：循环里<b>没有</b>逐步给 {@code recall_item} 手动接
     * {@code find_item} id 的硬编码——模型看得见上一步结果，会自己把 id 填进参数。但循环<b>正常收束</b>
     * 时仍保留一次窄口径的 {@code recall_item} 兜底补读（四个条件同时满足才触发，见方法末尾注释），
     * 因为"模型经常只规划 find_item"是实测结论，不能用理论假设换掉。</p>
     *
     * @param history      对话历史（正序，窗口 {@code vault.plan-history-rounds}）
     * @param hasRetrieval 本轮 RAG 是否有命中（让模型知道检索空了）
     * @param listener     单步回调（可为 null = 不推 SSE）
     * @return 累积的工具结果（空 = 本轮无工具，对话走纯 RAG）
     */
    public List<CommonProto.ToolResult> loopAndExecute(UUID userId, UUID sessionId, String question,
                                                      List<MirrorChatProto.ChatMessage> history,
                                                      boolean hasRetrieval,
                                                      StepListener listener) {
        List<CommonProto.ToolResult> results = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        // 指纹集：tool + 规范化 args JSON（终止条件 5 的判据）
        java.util.Set<String> fingerprints = new java.util.LinkedHashSet<>();
        final long startedAt = System.currentTimeMillis();
        final long budgetMs = Math.max(props.getLoopBudgetMs(), 1);
        final int maxSteps = Math.max(props.getMaxLoopSteps(), 1);
        int executedSteps = 0;
        // ↓ 三个变量只服务于循环末尾那一次 recall_item 兜底补读（见方法尾部注释）
        boolean normalExit = false;      // 是否因 done / 空 calls 正常收束（步数/预算/打转/异常都不算）
        boolean recallExecuted = false;  // 全程有没有执行过 recall_item（哪怕失败，也算模型自己续过）
        Long foundItemId = null;         // 成功的 find_item 命中的首个 vault_item_id
        try {
            // 0. 未配置 LLM 直接跳过（与旧路径/每日总结幂等口径一致）
            if (!aiGrpcClient.hasLlmConfig(userId)) {
                return results;
            }
            List<CommonProto.GlossaryTerm> glossary = glossarySafely(userId);
            List<CommonProto.ToolSpec> tools = registry.toProtoSpecs();
            List<MirrorChatProto.ChatMessage> safeHistory = history == null ? List.of() : history;

            for (int step = 1; ; step++) {
                // 终止 3：步数预算
                if (step > maxSteps) {
                    log.warn("循环终止（步数上限 {}），用户: {}, 已执行 {} 步", maxSteps, userId, executedSteps);
                    break;
                }
                // 终止 4：耗时预算（规划 + 工具执行合计；每步开头掐一次表）
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed > budgetMs) {
                    log.warn("循环终止（耗时预算 {}ms 用尽，已耗 {}ms），用户: {}, 已执行 {} 步",
                            budgetMs, elapsed, userId, executedSteps);
                    break;
                }

                // 1. 单步规划（服务端流：thinking 逐块透传，终帧给 calls/done）
                StepPlan plan;
                try {
                    plan = planOneStep(userId, question, glossary, tools, safeHistory,
                            results, step, maxSteps, hasRetrieval, listener);
                } catch (Exception e) {
                    // 终止 6：不重试，带着已有结果正常退出。
                    // 必须是 WARN：2026-09-21 联调时第 1 步因思考预算过大撞 60s deadline，这条是 INFO，
                    // 被 dev 的 org.xianshen.mumirrorb=WARN 吞掉，循环静默地一个工具都没执行过
                    log.warn("循环终止（第 {} 步规划中断/解析失败，不重试），用户: {}, 原因: {}",
                            step, userId, e.getMessage());
                    break;
                }

                // 终止 1：模型自评"材料够了"。
                // done=true 且带 calls = "执行完这最后一批就收尾"（2026-09-21 联调后改定）：
                // 原先以 done 为准丢弃 calls，模型想查最后一批就只能先出 calls 再单独花一整轮
                // LLM 调用来说"够了"——实测那一轮 mimo 要 ~30s，纯属浪费。
                boolean finalBatch = false;
                if (plan.done()) {
                    if (plan.calls().isEmpty()) {
                        log.info("循环终止（模型 done=true，材料够了），用户: {}, 已执行 {} 步", userId, executedSteps);
                        normalExit = true;
                        break;
                    }
                    finalBatch = true;
                }
                // 终止 2：终帧无计划
                if (plan.calls().isEmpty()) {
                    log.info("循环终止（第 {} 步空计划），用户: {}, 已执行 {} 步", step, userId, executedSteps);
                    normalExit = true;
                    break;
                }

                // 2. 执行本步 calls（逐个落审计；终止 5 命中则整轮中断）
                boolean loopedInPlace = false;
                for (MirrorChatProto.PlannedCall call : plan.calls()) {
                    String toolName = call.getTool();
                    var executor = registry.get(toolName);
                    if (executor.isEmpty()) {
                        log.warn("循环第 {} 步含未知工具「{}」（跳过），用户: {}", step, toolName, userId);
                        auditService.record(userId, sessionId, toolName, null,
                                "unknown tool", false, 0);
                        continue;
                    }
                    long callStart = System.currentTimeMillis();
                    Map<String, Object> args;
                    try {
                        args = parseArgs(call.getArgsJson());
                    } catch (Exception e) {
                        log.warn("循环第 {} 步工具「{}」参数解析失败（跳过该步调用）: {}",
                                step, toolName, e.getMessage());
                        auditService.record(userId, sessionId, toolName,
                                Map.of("raw", call.getArgsJson()),
                                "args parse error", false, System.currentTimeMillis() - callStart);
                        continue;
                    }
                    // recall_item 没给 id → 自动接前面 find_item 命中的首个文件（同旧 planAndExecute）。
                    // 原设想"循环里模型看得见上一步结果会自己填 id"，2026-09-21 联调实测不成立：
                    // prompt 明写"照抄 vault_item_id"，模型第 2 步仍只给 {"query":"架构"}，读正文失败，
                    // 回答"看不到原文"。放在指纹计算之前，指纹反映实际执行的参数。
                    if ("recall_item".equals(toolName) && !hasPositiveVaultItemId(args) && foundItemId != null) {
                        args.put("vault_item_id", foundItemId);
                        log.info("循环第 {} 步 recall_item 未给 vault_item_id，自动接 find_item 命中 id={}",
                                step, foundItemId);
                    }
                    // 终止 5：同工具 + 同参数指纹重复 → 立即中断（不执行这次重复调用）
                    String fingerprint = fingerprint(toolName, args);
                    if (!fingerprints.add(fingerprint)) {
                        log.warn("循环终止（原地打转：工具「{}」同参数重复调用），用户: {}, 指纹: {}",
                                toolName, userId, fingerprint);
                        loopedInPlace = true;
                        break;
                    }
                    if ("recall_item".equals(toolName)) {
                        // 模型自己续了读正文 → 末尾不再补（条件 3）；失败也算"它试过了"
                        recallExecuted = true;
                    }
                    try {
                        ToolExecutionResult result = executor.get().execute(userId, args);
                        long latency = System.currentTimeMillis() - callStart;
                        // 审计一步不漏（成功/失败都落 tool_calls，论文的步数/成功率/延迟素材）
                        auditService.record(userId, sessionId, toolName, args,
                                result.getSummary(), result.isSuccess(), latency);
                        // 失败结果也带回给模型（它知情后可以换个工具或收敛）
                        results.add(CommonProto.ToolResult.newBuilder()
                                .setTool(toolName)
                                .setSummary(nullSafe(result.getSummary()))
                                .setPayloadJson(objectMapper.writeValueAsString(
                                        result.getPayload() == null ? Map.of() : result.getPayload()))
                                .setSuccess(result.isSuccess())
                                .build());
                        if (result.isSuccess()) {
                            summaries.add(nullSafe(result.getSummary()));
                            if ("find_item".equals(toolName) && foundItemId == null) {
                                foundItemId = firstVaultItemId(result.getPayload());
                            }
                        }
                    } catch (Exception e) {
                        long latency = System.currentTimeMillis() - callStart;
                        log.warn("循环第 {} 步工具「{}」执行异常（跳过），用户: {}, 原因: {}",
                                step, toolName, userId, e.getMessage());
                        auditService.record(userId, sessionId, toolName, args,
                                "execute error: " + e.getMessage(), false, latency);
                    }
                }
                executedSteps++;
                // 3. 每步执行完推一次累积 tools_used（前端覆盖式赋值 → 芯片自然增长）
                notifyStepDone(listener, summaries);
                if (loopedInPlace) {
                    break;
                }
                if (finalBatch) {
                    log.info("循环终止（模型 done=true 并附最后一批工具，执行完即收尾），用户: {}, 已执行 {} 步",
                            userId, executedSteps);
                    normalExit = true;
                    break;
                }
            }
            // 4. 兜底补读一次 recall_item（**不是循环逻辑的一部分**）
            //
            // 为什么留这一刀：旧 planAndExecute 里同名兜底的注释记的是**实测结论**——
            // 「实测模型经常只规划 find_item」，用户于是拿到"我只有文件名和元信息，正文看不到"。
            // 循环理论上给了模型好几步机会自己续 recall_item，但把实测结论换成理论假设是行为回退，
            // 所以这里补一刀；同时把触发条件收得很窄，不去削循环的自主性：
            //   ① 循环是因 done=true / 空 calls **正常收束**（步数用尽、预算用尽、原地打转、
            //      规划异常这四种退出都不补——那些情况本身说明这轮已经跑飞，再补一刀只会更乱）
            //   ② 本轮有**成功的** find_item（foundItemId 只在 success 分支里取）
            //   ③ **全程**没执行过 recall_item（不是"上一步没有"）
            //   ④ 取得到 vault_item_id
            // 另外预算已超就不补（这一刀也算进 loop-budget-ms）。补完即结束，不回到循环。
            //
            // 将来若实测确认循环里模型会自己续 recall_item，这整段可以删，循环不依赖它。
            long elapsedBeforeBackstop = System.currentTimeMillis() - startedAt;
            if (normalExit && !recallExecuted && foundItemId != null && elapsedBeforeBackstop <= budgetMs) {
                java.util.Optional<ToolExecutor> recallExecutor = registry.get("recall_item");
                if (recallExecutor.isPresent()) {
                    Map<String, Object> autoArgs = new java.util.LinkedHashMap<>();
                    autoArgs.put("vault_item_id", foundItemId);
                    autoArgs.put("query", question);
                    long start = System.currentTimeMillis();
                    try {
                        ToolExecutionResult auto = recallExecutor.get().execute(userId, autoArgs);
                        // 审计要认得出这是兜底补的（args 加标记 + summary 前缀）：论文里
                        // "模型自己续的" 和 "执行器补的" 不能混成一个数
                        Map<String, Object> auditArgs = new java.util.LinkedHashMap<>(autoArgs);
                        auditArgs.put("auto_backstop", true);
                        auditService.record(userId, sessionId, "recall_item", auditArgs,
                                AUTO_BACKSTOP_PREFIX + nullSafe(auto.getSummary()),
                                auto.isSuccess(), System.currentTimeMillis() - start);
                        results.add(CommonProto.ToolResult.newBuilder()
                                .setTool("recall_item")
                                .setSummary(nullSafe(auto.getSummary()))
                                .setPayloadJson(objectMapper.writeValueAsString(
                                        auto.getPayload() == null ? Map.of() : auto.getPayload()))
                                .setSuccess(auto.isSuccess())
                                .build());
                        if (auto.isSuccess()) {
                            summaries.add(nullSafe(auto.getSummary()));
                            notifyStepDone(listener, summaries); // 补读的芯片也要出来
                        }
                        log.info("循环只查到 find_item 没读正文，已兜底补读一次，item: {}, 成功: {}",
                                foundItemId, auto.isSuccess());
                    } catch (Exception e) {
                        log.warn("兜底补读文件内容失败（不阻断回答），item: {}, 原因: {}",
                                foundItemId, e.getMessage());
                        auditService.record(userId, sessionId, "recall_item",
                                Map.of("vault_item_id", foundItemId, "auto_backstop", true),
                                AUTO_BACKSTOP_PREFIX + "execute error: " + e.getMessage(),
                                false, System.currentTimeMillis() - start);
                    }
                }
            }
            log.info("对话循环完成，用户: {}, 执行 {} 步, 结果 {} 条, 耗时 {}ms",
                    userId, executedSteps, results.size(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            // 零回归：循环整体异常（含 glossary/registry 组装失败）→ 带着已有结果返回，绝不炸上层
            log.warn("对话循环异常（带着已有 {} 条结果继续），用户: {}, 原因: {}",
                    results.size(), userId, e.getMessage());
        }
        return results;
    }

    /** 单步终帧结果（calls/done 仅终帧有效） */
    private record StepPlan(List<MirrorChatProto.PlannedCall> calls, boolean done) {
    }

    /**
     * 跑一次 PlanNextStep 流：thinking 逐块<b>立刻</b>回调，终帧返回 calls/done。
     *
     * <p>流走完没见到 {@code final=true}（中断/Python 异常收尾）→ 抛异常，由调用方按
     * 终止条件 6 处理（不重试）。</p>
     */
    private StepPlan planOneStep(UUID userId, String question,
                                 List<CommonProto.GlossaryTerm> glossary,
                                 List<CommonProto.ToolSpec> tools,
                                 List<MirrorChatProto.ChatMessage> history,
                                 List<CommonProto.ToolResult> previousResults,
                                 int step, int maxSteps, boolean hasRetrieval,
                                 StepListener listener) {
        MirrorChatProto.PlanNextStepRequest request = MirrorChatProto.PlanNextStepRequest.newBuilder()
                .setQuestion(question)
                .addAllGlossary(glossary)
                .addAllTools(tools)
                .addAllHistory(history)
                .addAllPreviousResults(previousResults)
                .setStep(step)
                .setMaxSteps(maxSteps)
                .setHasRetrieval(hasRetrieval)
                .build();
        Iterator<MirrorChatProto.PlanStepChunk> stream =
                aiGrpcClient.planNextStep(userId, request, props.getPlanToolsTimeoutMs());
        while (stream.hasNext()) {
            MirrorChatProto.PlanStepChunk chunk = stream.next();
            // 逐块实时透传（绝不攒完再推——这是"消除黑屏"的全部意义所在）
            if (chunk.hasThinking() && !chunk.getThinking().isEmpty()) {
                notifyThinking(listener, chunk.getThinking());
            }
            if (chunk.getFinal()) {
                return new StepPlan(List.copyOf(chunk.getCallsList()), chunk.getDone());
            }
        }
        throw new IllegalStateException("PlanNextStep 流结束但没有终帧（final=true）");
    }

    /** 参数指纹：tool + 规范化 args（key 排序后序列化，{"a":1,"b":2} 与 {"b":2,"a":1} 同指纹） */
    private String fingerprint(String tool, Map<String, Object> args) {
        try {
            return tool + "#" + objectMapper.writeValueAsString(new java.util.TreeMap<>(args));
        } catch (Exception e) {
            return tool + "#" + String.valueOf(args);
        }
    }

    private Map<String, Object> parseArgs(String argsJson) throws Exception {
        if (argsJson == null || argsJson.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        Map<String, Object> args = objectMapper.readValue(argsJson,
                new TypeReference<Map<String, Object>>() {
                });
        return args == null ? new java.util.LinkedHashMap<>() : args;
    }

    /** 词表组装失败不阻断循环（与 Chat 侧同口径） */
    private List<CommonProto.GlossaryTerm> glossarySafely(UUID userId) {
        try {
            return org.xianshen.mumirrorb.grpc.GlossaryProtoMapper
                    .toProtoList(glossaryService.confirmedForInjection(userId));
        } catch (Exception e) {
            log.warn("循环词表注入失败（按无词表继续），用户: {}, 原因: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 回调本身抛异常（SSE 已断等）不能反过来杀掉循环 */
    private void notifyThinking(StepListener listener, String delta) {
        if (listener == null) {
            return;
        }
        try {
            listener.onThinking(delta);
        } catch (Exception e) {
            log.debug("thinking 回调失败（忽略）: {}", e.getMessage());
        }
    }

    private void notifyStepDone(StepListener listener, List<String> summaries) {
        if (listener == null) {
            return;
        }
        try {
            listener.onStepDone(List.copyOf(summaries));
        } catch (Exception e) {
            log.debug("stepDone 回调失败（忽略）: {}", e.getMessage());
        }
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
