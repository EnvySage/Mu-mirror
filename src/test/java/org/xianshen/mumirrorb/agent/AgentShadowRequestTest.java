package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.AgentProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.AgentGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentShadowRequestTest {

    @Test
    void shadowAgentRequestHasNoJavaWriteGrant() {
        AgentProperties properties = new AgentProperties();
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        AgentWriteGrantService grantService = mock(AgentWriteGrantService.class);
        when(aiGrpcClient.buildLlmConfigFor(any())).thenReturn(CommonProto.LlmConfig.getDefaultInstance());
        when(aiGrpcClient.buildEmbeddingConfigFor(any())).thenReturn(CommonProto.EmbeddingConfig.getDefaultInstance());
        AgentGrpcClient client = new AgentGrpcClient(properties, aiGrpcClient, grantService);

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AgentRun shadow = AgentRun.builder().runId(UUID.randomUUID()).userId(userId).sessionId(sessionId)
                .engine("graph_v2").mode("shadow").status("running").startedAt(OffsetDateTime.now())
                .build();
        ChatInvocation invocation = new ChatInvocation(userId, sessionId, shadow.getRunId(), UUID.randomUUID(),
                17L, "帮我记一篇日记：仅原文", "graph_v2");

        AgentProto.AgentRunRequest request = client.buildRequest(invocation, shadow);

        assertTrue(request.getShadow());
        assertTrue(request.getCommandGrant().isEmpty());
        assertTrue(request.getAuthorizedRecordContent().isEmpty());
        assertTrue(request.getAllowedWriteToolsList().isEmpty());
        verify(grantService, never()).authorizeCreate(any(), any());
    }
}
