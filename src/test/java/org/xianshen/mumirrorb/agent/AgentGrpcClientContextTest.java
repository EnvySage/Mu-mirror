package org.xianshen.mumirrorb.agent;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.AgentGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.tools.ToolRegistry;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentGrpcClientContextTest {

    @Test
    void buildRequestCarriesServerAcceptedAssistantContext() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.buildLlmConfigFor(userId)).thenReturn(CommonProto.LlmConfig.getDefaultInstance());
        when(aiGrpcClient.buildEmbeddingConfigFor(userId)).thenReturn(CommonProto.EmbeddingConfig.getDefaultInstance());
        CommonProto.AssistantContext context = CommonProto.AssistantContext.newBuilder()
                .setRoleTone("基于已授权记忆")
                .addMemories(CommonProto.ChatMemoryContext.newBuilder()
                        .setMemoryId(UUID.randomUUID().toString()).setContent("accepted memory")
                        .addSourceMessageIds(17L).addSourceMessageIds(21L))
                .build();
        ChatInvocation invocation = new ChatInvocation(userId, sessionId, runId, UUID.randomUUID(),
                100L, "相关问题", "graph_v2", context, List.of(17L, 21L));
        AgentRun run = AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId).mode("shadow").build();

        var request = new AgentGrpcClient(new AgentProperties(), aiGrpcClient, mock(ToolRegistry.class))
                .buildRequest(invocation, run);

        assertEquals(context, request.getAssistantContext());
    }

    @Test
    void liveRequestExposesOnlyJavaPreparedActionDefinitionsAndNoDirectWriteGrant() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        when(aiGrpcClient.buildLlmConfigFor(userId)).thenReturn(CommonProto.LlmConfig.getDefaultInstance());
        when(aiGrpcClient.buildEmbeddingConfigFor(userId)).thenReturn(CommonProto.EmbeddingConfig.getDefaultInstance());
        var toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.prepareWriteProtoSpecs()).thenReturn(List.of(CommonProto.ToolSpec.newBuilder()
                .setName("prepare_record").setEffect("PREPARE_WRITE").build()));
        var run = AgentRun.builder().runId(runId).userId(userId).sessionId(sessionId)
                .engine("graph_v2").mode("live").build();
        var invocation = new ChatInvocation(userId, sessionId, runId, UUID.randomUUID(), 17L,
                "帮我写日记", "graph_v2");

        var request = new AgentGrpcClient(new AgentProperties(), aiGrpcClient, toolRegistry)
                .buildRequest(invocation, run);

        assertEquals(1, request.getPrepareToolsCount());
        assertEquals("prepare_record", request.getPrepareTools(0).getName());
        assertTrue(request.getCommandGrant().isEmpty());
        assertTrue(request.getAuthorizedRecordContent().isEmpty());
        assertTrue(request.getAllowedWriteToolsList().isEmpty());
    }
}
