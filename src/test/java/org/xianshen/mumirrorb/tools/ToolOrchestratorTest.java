package org.xianshen.mumirrorb.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * ToolOrchestrator 单测（toolcalling-vault-design.md 第 1 节：PlanTools 编排 + 零回归）
 *
 * <p>覆盖：Python 未上线降级 / 3s 超时降级 / 空计划 / 未知工具跳过 / 参数解析失败跳过 /
 * 正常计划执行并审计 / 步数上限 / 失败结果也进上下文 / AuditService 落库。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolOrchestratorTest {

    @Mock
    private AiGrpcClient aiGrpcClient;
    @Mock
    private GlossaryService glossaryService;
    @Mock
    private AuditService auditService;

    private ToolOrchestrator orchestrator;
    private ToolRegistry registry;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    /** 可控的测试工具 */
    static final class EchoTool implements ToolExecutor {
        int calls = 0;

        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name(), "echo", "{}");
        }

        @Override
        public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
            calls++;
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary("echo_tool:1次")
                    .payload(Map.of("echo", args))
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of(new EchoTool()));
        VaultProperties props = new VaultProperties();
        // 8dd3dd2 把超时提到 135s（yml 135000 / Java 默认 65000）——测试断言须与生产口径一致，
        // 否则 verify(planTools(..., 3000L)) 在默认值漂移后必挂
        props.setPlanToolsTimeoutMs(3000);
        orchestrator = new ToolOrchestrator(aiGrpcClient, registry, auditService,
                glossaryService, props,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(aiGrpcClient.hasLlmConfig(USER_ID)).thenReturn(true);
        when(glossaryService.confirmedForInjection(any())).thenReturn(List.of());
    }

    private MirrorChatProto.PlanToolsReply reply(String... toolArgsJson) {
        var builder = MirrorChatProto.PlanToolsReply.newBuilder();
        for (int i = 0; i < toolArgsJson.length; i += 2) {
            builder.addCalls(MirrorChatProto.PlannedCall.newBuilder()
                    .setTool(toolArgsJson[i]).setArgsJson(toolArgsJson[i + 1]));
        }
        return builder.build();
    }

    @Test
    @DisplayName("正常计划：规划 → 执行 → ToolResult 组装，审计落库成功")
    void planAndExecute_happy() {
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("echo_tool", "{\"k\":1}"));

        List<CommonProto.ToolResult> results = orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getSuccess());
        assertEquals("echo_tool", results.get(0).getTool());
        assertTrue(results.get(0).getPayloadJson().contains("\"k\":1"));
        verify(aiGrpcClient).planTools(eq(USER_ID), any(), eq(3000L));
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("echo_tool"), any(), any(), eq(true), anyLong());
    }

    @Test
    @DisplayName("Python 未上线/超时 → 空结果零回归（不抛异常）")
    void planAndExecute_grpcDown_zeroRegression() {
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        assertTrue(orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").isEmpty());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), eq(true), anyLong());
    }

    @Test
    @DisplayName("空计划 → 空结果")
    void planAndExecute_emptyPlan() {
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong())).thenReturn(reply());
        assertTrue(orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").isEmpty());
    }

    @Test
    @DisplayName("未知工具跳过（审计记 unknown tool，不让单工具炸对话）")
    void planAndExecute_unknownTool_skipped() {
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("no_such_tool", "{}"));

        assertTrue(orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").isEmpty());
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("no_such_tool"), any(),
                eq("unknown tool"), eq(false), anyLong());
    }

    @Test
    @DisplayName("参数 JSON 解析失败跳过（审计 args parse error）")
    void planAndExecute_badArgs_skipped() {
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("echo_tool", "{not-json"));

        assertTrue(orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").isEmpty());
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("echo_tool"), any(),
                eq("args parse error"), eq(false), anyLong());
    }

    @Test
    @DisplayName("maxToolCalls=2 上限：3 步计划只执行前 2 步")
    void planAndExecute_stepLimit() {
        EchoTool tool = new EchoTool();
        registry = new ToolRegistry(List.of(tool));
        orchestrator = new ToolOrchestrator(aiGrpcClient, registry, auditService,
                glossaryService, new VaultProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("echo_tool", "{}", "echo_tool", "{}", "echo_tool", "{}"));

        assertEquals(2, orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").size());
        assertEquals(2, tool.calls);
    }

    @Test
    @DisplayName("工具执行抛异常 → 记审计不炸对话，其余计划继续")
    void planAndExecute_executorThrows() {
        ToolExecutor throwing = new ToolExecutor() {
            @Override
            public String name() {
                return "boom_tool";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name(), "boom", "{}");
            }

            @Override
            public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
                throw new IllegalStateException("db down");
            }
        };
        registry = new ToolRegistry(List.of(throwing, new EchoTool()));
        orchestrator = new ToolOrchestrator(aiGrpcClient, registry, auditService,
                glossaryService, new VaultProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("boom_tool", "{}", "echo_tool", "{}"));

        List<CommonProto.ToolResult> results = orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题");

        assertEquals(1, results.size());
        assertEquals("echo_tool", results.get(0).getTool());
        verify(auditService).record(eq(USER_ID), eq(SESSION_ID), eq("boom_tool"), any(), any(), eq(false), anyLong());
    }

    @Test
    @DisplayName("未配置 LLM 直接跳过（不调 RPC）")
    void planAndExecute_noLlm() {
        when(aiGrpcClient.hasLlmConfig(USER_ID)).thenReturn(false);
        assertTrue(orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题").isEmpty());
        verify(aiGrpcClient, never()).planTools(any(), any(), anyLong());
    }

    @Test
    @DisplayName("工具执行失败（success=false）结果也进上下文（LLM 知情后改走 RAG）")
    void planAndExecute_failedResult_stillInContext() {
        ToolExecutor failing = new ToolExecutor() {
            @Override
            public String name() {
                return "fail_tool";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name(), "fail", "{}");
            }

            @Override
            public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
                return ToolExecutionResult.builder().success(false)
                        .summary("fail_tool:没有结果").payload(Map.of("error", "无记录")).build();
            }
        };
        registry = new ToolRegistry(List.of(failing));
        orchestrator = new ToolOrchestrator(aiGrpcClient, registry, auditService,
                glossaryService, new VaultProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(aiGrpcClient.planTools(eq(USER_ID), any(), anyLong()))
                .thenReturn(reply("fail_tool", "{}"));

        List<CommonProto.ToolResult> results = orchestrator.planAndExecute(USER_ID, SESSION_ID, "问题");
        assertEquals(1, results.size());
        assertEquals(false, results.get(0).getSuccess());
    }

    @Test
    @DisplayName("ToolRegistry：定义快照进 ToolSpec，name 分发")
    void registry_specs() {
        var specs = registry.toProtoSpecs();
        assertEquals(1, specs.size());
        assertEquals("echo_tool", specs.get(0).getName());
        assertTrue(registry.get("echo_tool").isPresent());
        assertTrue(registry.get("nope").isEmpty());
    }
}
