package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.tools.AuditService;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;
import org.xianshen.mumirrorb.tools.ToolOrchestrator;
import org.xianshen.mumirrorb.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话 Agent 循环单测（chat-loop-design.md §4.1 / §9）
 *
 * <p>覆盖<b>六条终止条件</b>各一例：done / 空 calls / 超步数 / 超预算 / 同参数打转 / 流中断；
 * 外加 thinking 逐块实时回调（不是攒完一次性推）、中途失败带着已有结果继续、
 * 审计每步都落。{@code chatLoopEnabled=false} 的回滚路径在 {@code ChatRetrievalTest} 里验。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatLoopTest {

    @Mock
    private AiGrpcClient aiGrpcClient;
    @Mock
    private ToolRegistry registry;
    @Mock
    private AuditService auditService;
    @Mock
    private GlossaryService glossaryService;

    private VaultProperties props;
    private ToolOrchestrator orchestrator;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String QUESTION = "我最近特别焦虑怎么办";

    /** 回调与执行的**真实时序**记录（thinking 实时性靠它断言） */
    private final List<String> timeline = new ArrayList<>();
    /** onThinking 收到的每一块（断言是逐块而非一整坨） */
    private final List<String> thinkingDeltas = new ArrayList<>();
    /** onStepDone 每次收到的累积列表快照 */
    private final List<List<String>> stepDoneSnapshots = new ArrayList<>();

    private final ToolOrchestrator.StepListener listener = new ToolOrchestrator.StepListener() {
        @Override
        public void onThinking(String delta) {
            timeline.add("think:" + delta);
            thinkingDeltas.add(delta);
        }

        @Override
        public void onStepDone(List<String> cumulativeToolSummaries) {
            timeline.add("stepDone:" + cumulativeToolSummaries);
            stepDoneSnapshots.add(List.copyOf(cumulativeToolSummaries));
        }
    };

    @BeforeEach
    void setUp() {
        props = new VaultProperties();
        props.setMaxLoopSteps(4);
        props.setLoopBudgetMs(120_000);
        orchestrator = new ToolOrchestrator(aiGrpcClient, registry, auditService, glossaryService,
                props, new ObjectMapper());
        when(aiGrpcClient.hasLlmConfig(USER_ID)).thenReturn(true);
        when(registry.toProtoSpecs()).thenReturn(List.of());
        when(glossaryService.confirmedForInjection(any())).thenReturn(List.of());
    }

    // ==================== 工具装配 ====================

    /** 登记一只成功工具，执行时把事件写进 timeline（用于时序断言） */
    private ToolExecutor stubTool(String name, String summary, Map<String, Object> payload)
            throws Exception {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.name()).thenReturn(name);
        when(executor.execute(eq(USER_ID), any())).thenAnswer(inv -> {
            timeline.add("exec:" + name);
            return ToolExecutionResult.builder()
                    .success(true).summary(summary).payload(payload).build();
        });
        when(registry.get(name)).thenReturn(Optional.of(executor));
        return executor;
    }

    private static MirrorChatProto.PlanStepChunk thinking(String text) {
        return MirrorChatProto.PlanStepChunk.newBuilder().setThinking(text).build();
    }

    private static MirrorChatProto.PlanStepChunk finalFrame(boolean done, String... toolArgPairs) {
        MirrorChatProto.PlanStepChunk.Builder builder = MirrorChatProto.PlanStepChunk.newBuilder()
                .setFinal(true).setDone(done);
        for (int i = 0; i < toolArgPairs.length; i += 2) {
            builder.addCalls(MirrorChatProto.PlannedCall.newBuilder()
                    .setTool(toolArgPairs[i]).setArgsJson(toolArgPairs[i + 1]));
        }
        return builder.build();
    }

    /** 每次调用返回下一个流（Mockito 连续返回值） */
    @SafeVarargs
    private void stubStreams(List<MirrorChatProto.PlanStepChunk>... streams) {
        var stubbing = when(aiGrpcClient.planNextStep(eq(USER_ID), any(), anyLong()));
        for (List<MirrorChatProto.PlanStepChunk> s : streams) {
            stubbing = stubbing.thenReturn(s.iterator());
        }
    }

    private List<CommonProto.ToolResult> run() {
        return orchestrator.loopAndExecute(USER_ID, SESSION_ID, QUESTION, List.of(), false, listener);
    }

    // ==================== 终止条件 1：done == true ====================

    @Test
    @DisplayName("终止 1：终帧 done=true 且 calls 为空 → 立刻停，不再规划")
    void stops_whenDoneTrue() throws Exception {
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubStreams(
                List.of(finalFrame(false, "get_stats", "{\"days\":30}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertEquals("get_stats", results.get(0).getTool());
        verify(aiGrpcClient, times(2)).planNextStep(eq(USER_ID), any(), anyLong());
    }

    @Test
    @DisplayName("终止 1'：done=true 且带 calls → 执行完这最后一批就收尾，不再花一轮 LLM 说\"够了\"")
    void doneWithCalls_executesFinalBatchThenStops() throws Exception {
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubTool("search_records", "search_records:3条", Map.of("count", 3));
        // 第 1 步就给出"最后一批"：两个互不依赖的工具 + done=true
        stubStreams(List.of(finalFrame(true,
                "get_stats", "{\"days\":30}",
                "search_records", "{\"moods\":[\"anxious\"],\"days\":30}")));

        List<CommonProto.ToolResult> results = run();

        assertEquals(2, results.size(), "最后一批两个工具都应执行");
        assertTrue(timeline.contains("exec:get_stats"));
        assertTrue(timeline.contains("exec:search_records"));
        // 关键：只规划了 1 次——没有第 2 轮 LLM 调用
        verify(aiGrpcClient, times(1)).planNextStep(eq(USER_ID), any(), anyLong());
        verify(auditService, times(2)).record(eq(USER_ID), eq(SESSION_ID), anyString(), any(), anyString(), eq(true), anyLong());
    }

    // ==================== 终止条件 2：calls 为空 ====================

    @Test
    @DisplayName("终止 2：终帧 calls 为空 → 停（第一步就空 = 本轮不用工具，退化纯 RAG）")
    void stops_whenCallsEmpty() {
        stubStreams(List.of(thinking("这问题不需要查库"), finalFrame(false)));

        List<CommonProto.ToolResult> results = run();

        assertTrue(results.isEmpty());
        verify(aiGrpcClient, times(1)).planNextStep(eq(USER_ID), any(), anyLong());
        verify(auditService, org.mockito.Mockito.never())
                .record(any(), any(), anyString(), any(), any(), anyBoolean(), anyLong());
    }

    // ==================== 终止条件 3：step > maxLoopSteps ====================

    @Test
    @DisplayName("终止 3：模型不肯停也只跑 max-loop-steps 步（这里 2 步）")
    void stops_whenStepBudgetExhausted() throws Exception {
        props.setMaxLoopSteps(2);
        stubTool("search_records", "search_records:命中", Map.of("count", 1));
        // 模型永远还要再查一步（参数每次不同，避开指纹去重），靠步数闸刹住
        stubStreams(
                List.of(finalFrame(false, "search_records", "{\"query\":\"a\"}")),
                List.of(finalFrame(false, "search_records", "{\"query\":\"b\"}")),
                List.of(finalFrame(false, "search_records", "{\"query\":\"c\"}")));

        List<CommonProto.ToolResult> results = run();

        assertEquals(2, results.size());
        verify(aiGrpcClient, times(2)).planNextStep(eq(USER_ID), any(), anyLong());
    }

    // ==================== 终止条件 4：累计耗时 > loopBudgetMs ====================

    @Test
    @DisplayName("终止 4：累计耗时超 loop-budget-ms → 停（规划+执行合计掐表）")
    void stops_whenTimeBudgetExhausted() throws Exception {
        props.setMaxLoopSteps(4);
        props.setLoopBudgetMs(30); // 一步执行就会吃穿预算
        ToolExecutor slow = mock(ToolExecutor.class);
        when(slow.name()).thenReturn("search_records");
        when(slow.execute(eq(USER_ID), any())).thenAnswer(inv -> {
            timeline.add("exec:search_records");
            Thread.sleep(80);
            return ToolExecutionResult.builder()
                    .success(true).summary("search_records:命中").payload(Map.of("count", 1)).build();
        });
        when(registry.get("search_records")).thenReturn(Optional.of(slow));
        stubStreams(
                List.of(finalFrame(false, "search_records", "{\"query\":\"a\"}")),
                List.of(finalFrame(false, "search_records", "{\"query\":\"b\"}")));

        List<CommonProto.ToolResult> results = run();

        // 第 1 步照常完成并保留结果，第 2 步不再规划
        assertEquals(1, results.size());
        verify(aiGrpcClient, times(1)).planNextStep(eq(USER_ID), any(), anyLong());
    }

    // ==================== 终止条件 5：同工具 + 同参数指纹重复 ====================

    @Test
    @DisplayName("终止 5：同工具同参数第二次出现 → 立刻中断且不执行重复调用（防原地打转烧钱）")
    void stops_whenSameToolSameArgsRepeats() throws Exception {
        stubTool("search_records", "search_records:命中", Map.of("count", 1));
        stubStreams(
                List.of(finalFrame(false, "search_records", "{\"query\":\"焦虑\",\"days\":30}")),
                // key 顺序换了但语义相同 → 规范化后同指纹，照样判打转
                List.of(finalFrame(false, "search_records", "{\"days\":30,\"query\":\"焦虑\"}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size(), "重复那次不执行，只保留第一次的结果");
        assertEquals(1, timeline.stream().filter("exec:search_records"::equals).count());
        verify(aiGrpcClient, times(2)).planNextStep(eq(USER_ID), any(), anyLong());
    }

    // ==================== 终止条件 6：流中断 / 解析失败，不重试 ====================

    @Test
    @DisplayName("终止 6a：流走完没有终帧（中断）→ 不重试，返回空结果退化纯 RAG，不抛异常")
    void stops_whenStreamEndsWithoutFinalFrame() {
        stubStreams(List.of(thinking("我先看看"), thinking("……")));

        List<CommonProto.ToolResult> results = run();

        assertTrue(results.isEmpty());
        // 不重试：只调一次
        verify(aiGrpcClient, times(1)).planNextStep(eq(USER_ID), any(), anyLong());
        // thinking 已经推出去了（黑屏消除不受失败影响）
        assertEquals(List.of("我先看看", "……"), thinkingDeltas);
    }

    @Test
    @DisplayName("终止 6b：第 2 步 gRPC 挂了 → 带着第 1 步已拿到的结果正常退出（零回归 0.4）")
    void keepsResults_whenLaterStepFails() throws Exception {
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        when(aiGrpcClient.planNextStep(eq(USER_ID), any(), anyLong()))
                .thenReturn(List.of(finalFrame(false, "get_stats", "{\"days\":30}")).iterator())
                .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertEquals("get_stats", results.get(0).getTool());
        assertTrue(results.get(0).getSuccess());
    }

    @Test
    @DisplayName("工具执行抛异常 → 该步跳过并落失败审计，循环继续，不打穿上层")
    void toolThrows_loopContinues() throws Exception {
        ToolExecutor boom = mock(ToolExecutor.class);
        when(boom.name()).thenReturn("search_records");
        when(boom.execute(eq(USER_ID), any())).thenThrow(new RuntimeException("db down"));
        when(registry.get("search_records")).thenReturn(Optional.of(boom));
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubStreams(
                List.of(finalFrame(false, "search_records", "{\"query\":\"焦虑\"}")),
                List.of(finalFrame(false, "get_stats", "{\"days\":30}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertEquals("get_stats", results.get(0).getTool());
        // 审计两步都有（失败那步也落，论文的成功率素材）
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("search_records"), any(),
                org.mockito.ArgumentMatchers.contains("execute error"), eq(false), anyLong());
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("get_stats"), any(),
                eq("get_stats:记录12条/30天"), eq(true), anyLong());
    }

    @Test
    @DisplayName("未知工具名 → 跳过 + 落 unknown tool 审计（宁可少用不可错用）")
    void unknownTool_skippedAndAudited() {
        when(registry.get("drop_table")).thenReturn(Optional.empty());
        stubStreams(
                List.of(finalFrame(false, "drop_table", "{}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertTrue(results.isEmpty());
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("drop_table"), eq(null),
                eq("unknown tool"), eq(false), eq(0L));
    }

    // ==================== SSE 实时性 ====================

    @Test
    @DisplayName("thinking 逐块实时回调：每块单独触发，且在本步工具执行之前就已推出（不是攒完再推）")
    void thinking_isStreamedChunkByChunk() throws Exception {
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubStreams(
                List.of(thinking("先看看"), thinking("他最近"), thinking("写过什么"),
                        finalFrame(false, "get_stats", "{\"days\":30}")),
                List.of(thinking("材料够了"), finalFrame(true)));

        run();

        // ① 逐块：三块分别回调，不是拼成一整坨一次回调
        assertEquals(List.of("先看看", "他最近", "写过什么", "材料够了"), thinkingDeltas);
        // ② 实时：第 1 步的 thinking 全部先于该步工具执行（黑屏就是靠这个消掉的）
        assertEquals(List.of(
                "think:先看看", "think:他最近", "think:写过什么",
                "exec:get_stats",
                "stepDone:[get_stats:记录12条/30天]",
                "think:材料够了"), timeline);
    }

    @Test
    @DisplayName("onStepDone 每步推累积全量 summary（前端覆盖式赋值 → 芯片自然增长）")
    void stepDone_carriesCumulativeSummaries() throws Exception {
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubTool("search_records", "search_records:3条", Map.of("count", 3));
        stubStreams(
                List.of(finalFrame(false, "get_stats", "{\"days\":30}")),
                List.of(finalFrame(false, "search_records", "{\"query\":\"焦虑\"}")),
                List.of(finalFrame(true)));

        run();

        assertEquals(2, stepDoneSnapshots.size());
        assertEquals(List.of("get_stats:记录12条/30天"), stepDoneSnapshots.get(0));
        assertEquals(List.of("get_stats:记录12条/30天", "search_records:3条"), stepDoneSnapshots.get(1));
    }

    // ==================== 其它防御 ====================

    @Test
    @DisplayName("未配置 LLM → 一次 RPC 都不发，直接空结果")
    void noLlmConfig_shortCircuits() {
        when(aiGrpcClient.hasLlmConfig(USER_ID)).thenReturn(false);

        assertTrue(run().isEmpty());
        verify(aiGrpcClient, org.mockito.Mockito.never()).planNextStep(any(), any(), anyLong());
    }

    @Test
    @DisplayName("请求携带 step/max_steps/has_retrieval/previous_results（Python 无状态所需的全量上下文）")
    void requestCarriesLoopContext() throws Exception {
        props.setMaxLoopSteps(3);
        stubTool("get_stats", "get_stats:记录12条/30天", Map.of("record_count", 12));
        stubStreams(
                List.of(finalFrame(false, "get_stats", "{\"days\":30}")),
                List.of(finalFrame(true)));

        orchestrator.loopAndExecute(USER_ID, SESSION_ID, QUESTION,
                List.of(MirrorChatProto.ChatMessage.newBuilder()
                        .setRole("user").setContent("上一轮问的开题报告").build()),
                true, listener);

        org.mockito.ArgumentCaptor<MirrorChatProto.PlanNextStepRequest> captor =
                org.mockito.ArgumentCaptor.forClass(MirrorChatProto.PlanNextStepRequest.class);
        verify(aiGrpcClient, times(2)).planNextStep(eq(USER_ID), captor.capture(), anyLong());
        MirrorChatProto.PlanNextStepRequest first = captor.getAllValues().get(0);
        assertEquals(1, first.getStep());
        assertEquals(3, first.getMaxSteps());
        assertTrue(first.getHasRetrieval());
        assertEquals(0, first.getPreviousResultsCount());
        assertEquals(1, first.getHistoryCount());
        // 第 2 步必须看得见第 1 步的结果（循环的核心输入）
        MirrorChatProto.PlanNextStepRequest second = captor.getAllValues().get(1);
        assertEquals(2, second.getStep());
        assertEquals(1, second.getPreviousResultsCount());
        assertEquals("get_stats", second.getPreviousResults(0).getTool());
    }

    @Test
    @DisplayName("单步 deadline 用 plan-tools-timeout-ms（单步上限，不是整轮预算）")
    void perStepDeadlineUsesPlanTimeout() {
        props.setPlanToolsTimeoutMs(60_000);
        props.setLoopBudgetMs(120_000);
        stubStreams(List.of(finalFrame(true)));

        run();

        verify(aiGrpcClient).planNextStep(eq(USER_ID), any(), eq(60_000L));
    }
    // ==================== recall_item 兜底补读（窄口径，非循环逻辑）====================

    /** find_item 的 payload：一个命中文件 */
    private static Map<String, Object> findItemPayload() {
        return Map.of("count", 1, "items", List.of(Map.of(
                "vault_item_id", 11, "display_name", "开题报告.pdf", "file_type", "pdf")));
    }

    @Test
    @DisplayName("兜底补读①：done 正常收束 + 成功 find_item + 全程没 recall_item → 补一次并落 auto-backstop 审计")
    void backstop_readsFileWhenLoopOnlyFoundIt() throws Exception {
        stubTool("find_item", "find_item:1个文件", findItemPayload());
        stubTool("recall_item", "recall_item:开题报告.pdf", Map.of("item", Map.of("vault_item_id", 11)));
        stubStreams(
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(2, results.size());
        assertEquals("recall_item", results.get(1).getTool());
        assertTrue(timeline.contains("exec:recall_item"));
        // 审计要能区分"模型自己续的"和"执行器补的"
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("recall_item"),
                org.mockito.ArgumentMatchers.argThat(a -> Boolean.TRUE.equals(a.get("auto_backstop"))),
                org.mockito.ArgumentMatchers.startsWith(ToolOrchestrator.AUTO_BACKSTOP_PREFIX),
                eq(true), anyLong());
        // 补读的芯片也推给前端（累积列表最后一项）
        assertEquals(List.of("find_item:1个文件", "recall_item:开题报告.pdf"),
                stepDoneSnapshots.get(stepDoneSnapshots.size() - 1));
    }

    @Test
    @DisplayName("兜底补读②：模型自己已经调过 recall_item → 不补（不削循环的自主性）")
    void backstop_skipped_whenModelAlreadyRecalled() throws Exception {
        stubTool("find_item", "find_item:1个文件", findItemPayload());
        stubTool("recall_item", "recall_item:开题报告.pdf", Map.of("item", Map.of("vault_item_id", 11)));
        stubStreams(
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")),
                List.of(finalFrame(false, "recall_item", "{\"vault_item_id\":11}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(2, results.size());
        // recall_item 只执行了一次（模型那次），没有被补第二次
        assertEquals(1, timeline.stream().filter("exec:recall_item"::equals).count());
        verify(auditService, org.mockito.Mockito.never()).record(any(), any(), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith(ToolOrchestrator.AUTO_BACKSTOP_PREFIX),
                anyBoolean(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("recall_item 没给 id → 自动接 find_item 命中的 id（2026-09-21 联调实测：prompt 写了照抄，模型仍不给）")
    void recallWithoutId_getsFoundItemId() throws Exception {
        stubTool("find_item", "find_item:1个文件", findItemPayload());
        ToolExecutor recall = stubTool("recall_item", "recall_item:开题报告.pdf",
                Map.of("item", Map.of("vault_item_id", 11)));
        stubStreams(
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")),
                // 实测形态：第 2 步只给 query，不给 vault_item_id
                List.of(finalFrame(true, "recall_item", "{\"query\":\"架构\"}")));

        List<CommonProto.ToolResult> results = run();

        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(recall).execute(eq(USER_ID), captor.capture());
        assertEquals(11, ((Number) captor.getValue().get("vault_item_id")).intValue());
        assertEquals("架构", captor.getValue().get("query"));
        assertEquals(2, results.size(), "find_item + recall_item 都成功");
    }

    @Test
    @DisplayName("兜底补读③：因步数上限退出（非正常收束）→ 不补（跑飞了就别再补一刀）")
    void backstop_skipped_whenExitedByStepBudget() throws Exception {
        props.setMaxLoopSteps(1);
        stubTool("find_item", "find_item:1个文件", findItemPayload());
        stubTool("recall_item", "recall_item:开题报告.pdf", Map.of("item", Map.of("vault_item_id", 11)));
        stubStreams(
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")),
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告v2\"}")));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertFalse(timeline.contains("exec:recall_item"));
    }

    @Test
    @DisplayName("兜底补读③b：规划中断退出（非正常收束）→ 不补")
    void backstop_skipped_whenExitedByStreamFailure() throws Exception {
        stubTool("find_item", "find_item:1个文件", findItemPayload());
        stubTool("recall_item", "recall_item:开题报告.pdf", Map.of("item", Map.of("vault_item_id", 11)));
        when(aiGrpcClient.planNextStep(eq(USER_ID), any(), anyLong()))
                .thenReturn(List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")).iterator())
                .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertFalse(timeline.contains("exec:recall_item"));
    }

    @Test
    @DisplayName("兜底补读④：find_item 失败 / 零命中拿不到 vault_item_id → 不补")
    void backstop_skipped_whenFindItemFailed() throws Exception {
        ToolExecutor failing = mock(ToolExecutor.class);
        when(failing.name()).thenReturn("find_item");
        when(failing.execute(eq(USER_ID), any())).thenAnswer(inv -> {
            timeline.add("exec:find_item");
            return ToolExecutionResult.builder()
                    .success(false).summary("find_item:查库失败")
                    .payload(Map.of("count", 0, "items", List.of())).build();
        });
        when(registry.get("find_item")).thenReturn(Optional.of(failing));
        stubTool("recall_item", "recall_item:开题报告.pdf", Map.of("item", Map.of("vault_item_id", 11)));
        stubStreams(
                List.of(finalFrame(false, "find_item", "{\"query\":\"开题报告\"}")),
                List.of(finalFrame(true)));

        List<CommonProto.ToolResult> results = run();

        assertEquals(1, results.size());
        assertFalse(results.get(0).getSuccess());
        assertFalse(timeline.contains("exec:recall_item"));
    }
}
