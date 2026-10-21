package org.xianshen.mumirrorb.agent;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.AgentGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        var request = new AgentGrpcClient(new AgentProperties(), aiGrpcClient, mock(AgentWriteGrantService.class))
                .buildRequest(invocation, run);

        assertEquals(context, request.getAssistantContext());
    }
}
