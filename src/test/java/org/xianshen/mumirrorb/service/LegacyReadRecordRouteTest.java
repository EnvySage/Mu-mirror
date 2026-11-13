package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.agent.ChatInvocation;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.service.impl.ChatServiceImpl;
import org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver;
import org.xianshen.mumirrorb.tools.AuditService;
import org.xianshen.mumirrorb.tools.ToolOrchestrator;
import org.xianshen.mumirrorb.tools.ToolRegistry;
import org.xianshen.mumirrorb.tools.impl.ReadRecordTool;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises the real legacy planner, registered reader, and final ChatRequest handoff. */
class LegacyReadRecordRouteTest {

    @Test
    void plannedReadRecordDeliversCompleteAuthorizedSnippetToLegacyAnswerRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        long recordId = 428L;
        String tailFact = "末尾事实：合同交付日期是 2026-12-31，负责人为林青。";
        String fullSnippet = "已授权片段正文。".repeat(30) + tailFact;

        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        ChatSearchMapper searchMapper = mock(ChatSearchMapper.class);
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        var glossaryService = mock(GlossaryService.class);
        var auditService = mock(AuditService.class);
        when(aiGrpcClient.hasLlmConfig(userId)).thenReturn(true);
        when(glossaryService.confirmedForInjection(userId)).thenReturn(List.of());
        when(glossaryService.matchQueryTerms(userId, "读取记录末尾的交付日期")).thenReturn(List.of());
        when(aiGrpcClient.extractIntent(eq(userId), eq("读取记录末尾的交付日期"), any()))
                .thenReturn(MirrorChatProto.ExtractIntentResponse.newBuilder()
                        .setQueryType("structured").setContentType("todo")
                        .setRewrittenQuery("读取记录末尾的交付日期").build());
        when(searchMapper.searchStructured(eq(userId), eq("todo"), any(), any(), any(), any(), eq(5), eq(0)))
                .thenReturn(List.of());
        when(searchMapper.readAuthorizedRecord(userId, recordId, 21, 0)).thenReturn(List.of(
                RetrievedChunkDTO.builder().chunkId(991L).recordId(recordId)
                        .createdAt("2026-11-12 10:20").content(fullSnippet).build()));
        when(aiGrpcClient.planTools(eq(userId), any(), anyLong())).thenReturn(
                MirrorChatProto.PlanToolsReply.newBuilder().addCalls(
                        MirrorChatProto.PlannedCall.newBuilder().setTool("read_record")
                                .setArgsJson("{\"record_id\":428,\"offset\":0,\"limit\":20}")
                                .build()).build());
        when(aiGrpcClient.chatStream(eq(userId), any())).thenReturn(List.of(
                MirrorChatProto.ChatChunk.newBuilder().setContent("记录末尾写明交付日期为 2026-12-31。")
                        .setDone(true).build()).iterator());

        var reader = new ReadRecordTool(searchMapper);
        var registry = new ToolRegistry(List.of(reader));
        VaultProperties vaultProperties = new VaultProperties();
        vaultProperties.setChatLoopEnabled(false);
        vaultProperties.setMaxToolCalls(2);
        ToolOrchestrator planner = new ToolOrchestrator(aiGrpcClient, registry, auditService,
                glossaryService, vaultProperties, new com.fasterxml.jackson.databind.ObjectMapper());

        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ConversationHistoryMapper historyMapper = mock(ConversationHistoryMapper.class);
        when(historyMapper.selectRecentForModel(sessionId, userId, 6)).thenReturn(List.of());
        var memoryService = mock(ChatMemoryService.class);
        var statsMapper = mock(ProfileStatsMapper.class);
        ChatServiceImpl chatService = new ChatServiceImpl(sessionMapper, historyMapper, searchMapper,
                settingsMapper, aiGrpcClient, glossaryService, planner,
                new com.fasterxml.jackson.databind.ObjectMapper(), new MirrorProperties(), vaultProperties,
                statsMapper, memoryService, new DailyChatSessionResolver(sessionMapper));

        var finalResult = chatService.runLegacy(new ChatInvocation(userId, sessionId, UUID.randomUUID(),
                UUID.randomUUID(), 7001L, "读取记录末尾的交付日期", "legacy"), new SseEmitter());

        var plannerRequest = org.mockito.ArgumentCaptor.forClass(MirrorChatProto.PlanToolsRequest.class);
        verify(aiGrpcClient).planTools(eq(userId), plannerRequest.capture(), anyLong());
        var readerSpec = plannerRequest.getValue().getToolsList().stream()
                .filter(spec -> spec.getName().equals("read_record")).findFirst().orElseThrow();
        assertEquals("READ", readerSpec.getEffect());
        assertTrue(readerSpec.getArgsSchema().contains("record_id"));
        assertTrue(readerSpec.getArgsSchema().contains("offset"));
        assertTrue(readerSpec.getArgsSchema().contains("limit"));

        var answerRequest = org.mockito.ArgumentCaptor.forClass(MirrorChatProto.ChatRequest.class);
        verify(aiGrpcClient).chatStream(eq(userId), answerRequest.capture());
        assertEquals("STRUCTURED", finalResult.route());
        assertEquals(1, answerRequest.getValue().getToolResultsCount());
        CommonProto.ToolResult readResult = answerRequest.getValue().getToolResults(0);
        assertEquals("read_record", readResult.getTool());
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(readResult.getPayloadJson());
        assertEquals("full", payload.path("content_scope").asText());
        assertEquals(fullSnippet, payload.path("snippets").get(0).path("text").asText());
        assertTrue(fullSnippet.length() > 120);
        assertTrue(payload.path("snippets").get(0).path("text").asText().endsWith(tailFact));
        verify(searchMapper).readAuthorizedRecord(userId, recordId, 21, 0);
    }
}
