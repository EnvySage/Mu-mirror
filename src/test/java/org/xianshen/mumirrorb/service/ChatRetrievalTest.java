package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.ChatSessionMapper;
import org.xianshen.mumirrorb.mapper.ConversationHistoryMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.service.impl.ChatServiceImpl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话四路检索单元测试（设计文档 6.6 / 13.1）
 *
 * <p>覆盖：路由分发（画像回退 HYBRID、STRUCTURED SQL、SEMANTIC/HYBRID 衰减开关）、
 * time_range 关衰减、检索为空兜底落库。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatRetrievalTest {

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ConversationHistoryMapper historyMapper;
    @Mock
    private ChatSearchMapper searchMapper;
    @Mock
    private SettingsMapper settingsMapper;
    @Mock
    private AiGrpcClient aiGrpcClient;
    @Mock
    private org.xianshen.mumirrorb.service.ChatMemoryService chatMemoryService;

    private ChatServiceImpl chatService;
    private org.xianshen.mumirrorb.tools.ToolOrchestrator toolOrchestrator;
    private org.xianshen.mumirrorb.config.VaultProperties vaultProperties;
    private org.xianshen.mumirrorb.mapper.ProfileStatsMapper statsMapper;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        toolOrchestrator = org.mockito.Mockito.mock(org.xianshen.mumirrorb.tools.ToolOrchestrator.class);
        // 旧四路检索用例明确走回滚路径；Agent 路径的用例单独开启循环。
        vaultProperties = new org.xianshen.mumirrorb.config.VaultProperties();
        vaultProperties.setChatLoopEnabled(false);
        statsMapper = org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.ProfileStatsMapper.class);
        chatService = new ChatServiceImpl(sessionMapper, historyMapper, searchMapper,
                settingsMapper, aiGrpcClient,
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.GlossaryService.class),
                toolOrchestrator,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.xianshen.mumirrorb.config.MirrorProperties(),
                vaultProperties, statsMapper,
                chatMemoryService,
                new org.xianshen.mumirrorb.service.impl.DailyChatSessionResolver(sessionMapper));
        // 兜底分档：默认按"有记录但没匹配上"档（老用户）；零记录档由专门用例覆盖
        when(statsMapper.countUserRecords(eq(USER_ID), any(), any())).thenReturn(12L);

        ChatSession session = ChatSession.builder()
                .id(SESSION_ID).userId(USER_ID).title("测试")
                .dayKey(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))
                .timezone("Asia/Shanghai")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(sessionMapper.findDaily(eq(USER_ID), any())).thenReturn(session);
        java.util.concurrent.atomic.AtomicLong messageIds = new java.util.concurrent.atomic.AtomicLong(1);
        when(historyMapper.insert(any())).thenAnswer(invocation -> {
            ((org.xianshen.mumirrorb.pojo.DO.ConversationHistory) invocation.getArgument(0)).setId(messageIds.getAndIncrement());
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            var reply = (org.xianshen.mumirrorb.pojo.DO.ConversationHistory) invocation.getArgument(0);
            historyMapper.insert(reply);
            return reply.getId();
        }).when(chatMemoryService).persistAssistantReply(any());
        when(settingsMapper.selectOne(any())).thenReturn(UserSettings.builder().ragHalfLife(30).build());
        when(aiGrpcClient.embed(eq(USER_ID), anyString())).thenReturn(EmbeddingProto.EmbedResponse.newBuilder()
                .setDimension(1024)
                .addVector(0.1f).addVector(0.2f)
                .build());
    }

    @Test
    void assistantReplyPersistenceCarriesSourceIdsFromServerBuiltAssistantContext() {
        var memory = org.xianshen.mumirrorb.grpc.gen.CommonProto.ChatMemoryContext.newBuilder()
                .setMemoryId(UUID.randomUUID().toString()).setContent("authorized memory")
                .addSourceMessageIds(111L).addSourceMessageIds(112L).build();
        var context = org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext.newBuilder().addMemories(memory).build();
        var request = MirrorChatProto.ChatRequest.newBuilder().setAssistantContext(context).build();

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(chatService, "persistAssistantReply",
                USER_ID, SESSION_ID, "answer", List.of(), List.of(), List.of(), false, request);

        var captor = org.mockito.ArgumentCaptor.forClass(org.xianshen.mumirrorb.pojo.DO.ConversationHistory.class);
        verify(chatMemoryService).persistAssistantReply(captor.capture());
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode().add(111L).add(112L),
                captor.getValue().getContextSourceMessageIds());
    }

    private MirrorChatProto.ExtractIntentResponse intent(String queryType, String timeRange) {
        return MirrorChatProto.ExtractIntentResponse.newBuilder()
                .setQueryType(queryType)
                .setContentType("")
                .setTimeRange(timeRange)
                .setRewrittenQuery("改写后的问题")
                .build();
    }

    private List<RetrievedChunkDTO> hit() {
        return List.of(RetrievedChunkDTO.builder()
                .recordId(1L).content("片段").title("标题")
                .createdAt("2026-09-03 14:30").contentType("learning").score(0.2)
                .build());
    }

    private MirrorChatProto.ChatChunk answerChunk() {
        return MirrorChatProto.ChatChunk.newBuilder()
                .setContent("回答")
                .addSources(MirrorChatProto.Source.newBuilder().setRecordId(1L).setQuote("引用").setDate("2026-09-03"))
                .setDone(true)
                .build();
    }

    /** 指定文本、无 sources 的 done 块——无材料路径用（没有资料可引用） */
    private MirrorChatProto.ChatChunk answerChunk(String text) {
        return MirrorChatProto.ChatChunk.newBuilder()
                .setContent(text)
                .setDone(true)
                .build();
    }

    @Test
    @DisplayName("SEMANTIC 路由：无 time_range → 开衰减（halfLife=30）")
    void semantic_appliesDecay() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt());
        verify(searchMapper, never()).searchStructured(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("流式回答沿用开始时解析的会话写入 assistant 消息")
    void streamPersistsAssistantToSessionResolvedAtStart() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(historyMapper).selectRecentForModel(eq(SESSION_ID), eq(USER_ID), anyInt());
        org.mockito.ArgumentCaptor<org.xianshen.mumirrorb.pojo.DO.ConversationHistory> inserted =
                org.mockito.ArgumentCaptor.forClass(org.xianshen.mumirrorb.pojo.DO.ConversationHistory.class);
        verify(historyMapper, times(2)).insert(inserted.capture());
        assertTrue(inserted.getAllValues().stream().allMatch(message -> SESSION_ID.equals(message.getSessionId())));
        assertEquals("assistant", inserted.getAllValues().get(1).getRole());
    }

    @Test
    @DisplayName("待确认记忆候选的正文不进入模型 action context")
    void pendingMemoryPayloadIsRedactedFromAssistantContext() {
        var actions = org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.ActionProposalService.class);
        UUID actionId = UUID.randomUUID();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.set("memoryDraft", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("memoryId", UUID.randomUUID().toString()).put("content", "PRIVATE_MEMORY_SENTINEL"));
        when(actions.listForSession(USER_ID, SESSION_ID)).thenReturn(List.of(
                org.xianshen.mumirrorb.pojo.VO.ActionProposalVO.builder().id(actionId).kind("memory.accept")
                        .state("PENDING").version(4).payload(payload).result(payload).build(),
                org.xianshen.mumirrorb.pojo.VO.ActionProposalVO.builder().id(UUID.randomUUID()).kind("memory.edit")
                        .state("PENDING").version(2).payload(payload).result(payload).build()));
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "actionProposalService", actions);
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());
        var request = org.mockito.ArgumentCaptor.forClass(MirrorChatProto.ChatRequest.class);

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(aiGrpcClient).chatStream(eq(USER_ID), request.capture());
        String summary = request.getValue().getAssistantContext().getActionStatusSummary();
        assertTrue(summary.contains(actionId.toString()));
        assertTrue(summary.contains("kind=memory.accept"));
        assertTrue(summary.contains("state=PENDING"));
        assertTrue(summary.contains("version=4"));
        assertTrue(summary.contains("requiresConfirmation=true"));
        assertFalse(summary.contains("payload="));
        assertFalse(summary.contains("result="));
        assertFalse(summary.contains("PRIVATE_MEMORY_SENTINEL"));
    }

    @Test
    @DisplayName("HYBRID 路由：time_range 非空 → 关衰减（用户点名时间不应降权）")
    void hybrid_timeRange_disablesDecay() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("上个月在忙什么"), any())).thenReturn(intent("hybrid", "上个月"));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                eq(false), eq(30.0), any(), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("上个月在忙什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                eq(false), eq(30.0), any(), anyInt());
    }

    @Test
    @DisplayName("STRUCTURED 路由：带元数据条件时纯 SQL 过滤，不调 Embed 不走向量")
    void structured_noVectorSearch() {
        // 结构化意图必须真带过滤条件（ExtractIntent 对"我的待办"会给出 contentType=todo）；
        // 无条件时新逻辑会回退 HYBRID（见 structuredWithoutMetaFilter_fallsBackToHybrid）
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我的待办"), any()))
                .thenReturn(intent("structured", "").toBuilder().setContentType("todo").build());
        when(searchMapper.searchStructured(eq(USER_ID), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("我的待办").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchStructured(eq(USER_ID), any(), any(), any(), any(), any(), anyInt());
        verify(aiGrpcClient, never()).embed(any(), anyString());
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), any(), anyInt());
    }

    @Test
    @DisplayName("画像不再使用独立 RAG 路由；旧 profile 路由降级为 hybrid，由 get_profile 负责快照")
    void profileRoute_fallsBackToHybrid() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我是什么样的人"), any())).thenReturn(intent("profile", ""));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), eq(30.0), any(), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("我是什么样的人").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), eq(30.0), any(), anyInt());
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), any(), anyInt());
    }

    @Test
    @DisplayName("检索为空 → 仍调 LLM（无材料也交给模型，2026-09-23 起不再直接吐模板）")
    void emptyRetrieval_stillCallsLlm() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("说了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt()))
                .thenReturn(List.of());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk("在的，今天想聊什么？")).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("说了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        // 无材料不再短路：模型照常被调用，由 prompt 决定怎么自然回应
        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && "在的，今天想聊什么？".equals(h.getContent())
                        && !Boolean.TRUE.equals(h.getIsFallback())));
    }

    @Test
    @DisplayName("检索为空 + LLM 返回空流 → 回落到分档兜底文案（最后一道网）")
    void emptyRetrieval_llmEmptyStream_stillFallsBack() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("说了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt()))
                .thenReturn(List.of());
        // LLM 一个字都没吐（流为空）→ streamAnswer 内部兜底
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.<MirrorChatProto.ChatChunk>of().iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("说了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        // 文案 = 分档里的"有记录但没匹配上"（§6.1），且打 is_fallback=true 不进后续上下文（§6.2）
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && ChatServiceImpl.FALLBACK_NO_MATCH.equals(h.getContent())
                        && Boolean.TRUE.equals(h.getIsFallback())));
    }

    @Test
    @DisplayName("检索为空但工具查到文件 → 不走兜底，chatStream 仍被调用（工具结果不被丢弃）")
    void emptyRetrieval_withUsableToolData_stillCallsLlm() {
        vaultProperties.setChatLoopEnabled(true);
        when(toolOrchestrator.loopAndExecute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolResult.newBuilder()
                        .setTool("find_item").setSummary("find_item:1个文件").setSuccess(true)
                        .setPayloadJson("{\"count\":1,\"items\":[{\"vault_item_id\":11,"
                                + "\"display_name\":\"开题报告.pdf\",\"file_type\":\"pdf\","
                                + "\"digest_status\":\"confirmed\"}]}")
                        .build()));
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(
                MirrorChatProto.ChatChunk.newBuilder().setContent("就在 [F1] 那份里").setDone(true).build())
                .iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("我传过的开题报告在哪").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        verify(historyMapper, never()).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole()) && Boolean.TRUE.equals(h.getIsFallback())));
    }

    @Test
    @DisplayName("Agent 工具零命中仍交给 Chat 判断；模型空流才回落兜底")
    void emptyRetrieval_withEmptyToolPayload_emptyStreamFallsBack() {
        vaultProperties.setChatLoopEnabled(true);
        when(toolOrchestrator.loopAndExecute(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolResult.newBuilder()
                        .setTool("find_item").setSummary("find_item:0个文件").setSuccess(true)
                        .setPayloadJson("{\"count\":0,\"items\":[]}").build()));
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.<MirrorChatProto.ChatChunk>of().iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("说了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        // 无材料不再短路：照样问模型；这一例模型也没给内容 → 回落分档文案
        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && ChatServiceImpl.FALLBACK_NO_MATCH.equals(h.getContent())
                        && Boolean.TRUE.equals(h.getIsFallback())));
    }

    @Test
    @DisplayName("相关性下限（余弦距离 0.35）下推到 SQL；全被滤掉 → 仍调 LLM（阈值 0.35 不动）")
    void relevanceThreshold_passedToSqlAndStillCallsLlm() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), eq(0.35), anyInt()))
                .thenReturn(List.of());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk("这阵子没翻到相关的")).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), eq(0.35), anyInt());
        // 阈值仍是红线（下推到 SQL 不放松）；但阈值滤空不等于不回答——交给模型自然回应
        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && "这阵子没翻到相关的".equals(h.getContent())));
    }

    @Test
    @DisplayName("STRUCTURED 无元数据条件 → 回退 HYBRID（不再按时间倒序端最近 N 条）")
    void structuredWithoutMetaFilter_fallsBackToHybrid() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我记得我上传过设计文档"), any()))
                .thenReturn(intent("structured", ""));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("我记得我上传过设计文档").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt());
        verify(searchMapper, never()).searchStructured(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("STRUCTURED 带元数据条件 → 正常走 SQL 过滤（不回退）")
    void structuredWithMetaFilter_usesSql() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我有哪些待办"), any()))
                .thenReturn(intent("structured", "").toBuilder().setContentType("todo").build());
        when(searchMapper.searchStructured(eq(USER_ID), eq("todo"), any(), any(), any(), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("我有哪些待办").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchStructured(eq(USER_ID), eq("todo"), any(), any(), any(), any(), anyInt());
        verify(searchMapper, never()).searchHybrid(any(), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt());
    }

    @Test
    @DisplayName("sources 带 n（正文 [n] 原始编号）——前端不能靠数组下标定位来源")
    void sourcesCarryCitationNumber() throws Exception {
        MirrorChatProto.ChatChunk chunk = MirrorChatProto.ChatChunk.newBuilder()
                .setDone(true)
                .addSources(MirrorChatProto.Source.newBuilder()
                        .setRecordId(11L).setQuote("q").setDate("2026-09-01").setN(5))
                .build();
        List<RetrievedChunkDTO> chunks = hit();

        java.lang.reflect.Method m = ChatServiceImpl.class.getDeclaredMethod(
                "extractSources", MirrorChatProto.ChatChunk.class, List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> sources =
                (List<java.util.Map<String, Object>>) m.invoke(chatService, chunk, chunks);
        org.junit.jupiter.api.Assertions.assertEquals(1, sources.size());
        org.junit.jupiter.api.Assertions.assertEquals(5, sources.get(0).get("n"));
        org.junit.jupiter.api.Assertions.assertEquals(11L, sources.get(0).get("record_id"));

        // 模型没引用任何 [n] → sources 为空（不再兜底挂前 3 条检索结果）
        List<java.util.Map<String, Object>> none =
                (List<java.util.Map<String, Object>>) m.invoke(chatService,
                        MirrorChatProto.ChatChunk.newBuilder().setDone(true).build(), chunks);
        org.junit.jupiter.api.Assertions.assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("AI 流失败 → 兜底'暂时无法回答'落库（6.6）")
    void chatStreamFails_fallbackPersisted() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenThrow(new RuntimeException("gRPC down"));

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole()) && "暂时无法回答".equals(h.getContent())));
    }

    @Test
    @DisplayName("ExtractIntent 超时回退 HYBRID：fallback 携带原文 rewritten_query，embed 收到非空文本不 NPE")
    void intentTimeout_fallbackCarriesOriginalQuery() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("昨天上班我忙了什么"), any()))
                .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("昨天上班我忙了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        // 关键断言：embed 收到的是原文（非 null/空），HYBRID 检索用原文作向量检索 query
        verify(aiGrpcClient).embed(eq(USER_ID), eq("昨天上班我忙了什么"));
        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt());
    }

    // ==================== 兜底文案三档（chat-loop-design.md §6.1）====================
    // 2026-09-23 起：无材料也先问模型，这三档退为"模型一个字都没吐"时的最后一道网。
    // 故这些用例统一桩空流，验证的是"选档对不对"，而非"内容有没有被短路返回"。

    @Test
    @DisplayName("兜底第 1 档：零记录新用户 + 模型空流 → 情绪引导文案，不是\"查无此记录\"口吻")
    void fallbackTier1_zeroRecordUser_getsInvite() {
        when(statsMapper.countUserRecords(eq(USER_ID), any(), any())).thenReturn(0L);
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我最近特别焦虑怎么办"), any()))
                .thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), anyBoolean(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.<MirrorChatProto.ChatChunk>of().iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("我最近特别焦虑怎么办").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && ChatServiceImpl.FALLBACK_NEW_USER.equals(h.getContent())));
    }

    @Test
    @DisplayName("兜底第 3 档：有记录 + 情绪倾诉（关键词/moods）+ 模型空流 → 有人情味的回应")
    void fallbackTier3_emotionalTalk_getsEmpathy() {
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("我最近特别焦虑怎么办"), any()))
                .thenReturn(intent("semantic", "").toBuilder().addMoods("anxious").build());
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), anyBoolean(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.<MirrorChatProto.ChatChunk>of().iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("我最近特别焦虑怎么办").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole())
                        && ChatServiceImpl.FALLBACK_EMOTIONAL.equals(h.getContent())));
    }

    // ==================== 兜底消息不污染历史（§6.2）====================

    @Test
    @DisplayName("is_fallback=true 的 assistant 消息不进 ChatRequest.history（靠列不靠文案匹配）")
    void fallbackMessage_excludedFromHistory() {
        when(historyMapper.selectRecentForModel(eq(SESSION_ID), eq(USER_ID), anyInt())).thenReturn(
                new java.util.ArrayList<>(List.of(
                        org.xianshen.mumirrorb.pojo.DO.ConversationHistory.builder()
                                .role("assistant").content(ChatServiceImpl.FALLBACK_NO_MATCH)
                                .isFallback(true).build(),
                        // 用户自己打出同样一句话：不能被误跳（这就是不用文案匹配的理由）
                        org.xianshen.mumirrorb.pojo.DO.ConversationHistory.builder()
                                .role("user").content(ChatServiceImpl.FALLBACK_NO_MATCH)
                                .isFallback(false).build(),
                        org.xianshen.mumirrorb.pojo.DO.ConversationHistory.builder()
                                .role("assistant").content("正常回答").isFallback(false).build())));
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), anyBoolean(), anyDouble(), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        org.mockito.ArgumentCaptor<MirrorChatProto.ChatRequest> captor =
                org.mockito.ArgumentCaptor.forClass(MirrorChatProto.ChatRequest.class);
        verify(aiGrpcClient).chatStream(eq(USER_ID), captor.capture());
        List<MirrorChatProto.ChatMessage> history = captor.getValue().getHistoryList();
        org.junit.jupiter.api.Assertions.assertEquals(2, history.size());
        org.junit.jupiter.api.Assertions.assertTrue(history.stream()
                .noneMatch(m -> "assistant".equals(m.getRole())
                        && ChatServiceImpl.FALLBACK_NO_MATCH.equals(m.getContent())));
        org.junit.jupiter.api.Assertions.assertTrue(history.stream()
                .anyMatch(m -> "user".equals(m.getRole())
                        && ChatServiceImpl.FALLBACK_NO_MATCH.equals(m.getContent())));
    }

    // ==================== 循环接线 / 回滚闸 ====================

    @Test
    @DisplayName("chat-loop-enabled=false → 走旧 planAndExecute，一次都不调 loopAndExecute（回滚路径）")
    void chatLoopDisabled_usesLegacyPlanAndExecute() {
        vaultProperties.setChatLoopEnabled(false);
        when(aiGrpcClient.extractIntent(eq(USER_ID), eq("最近学了什么"), any())).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), anyBoolean(), anyDouble(), any(), anyInt()))
                .thenReturn(hit());
        when(toolOrchestrator.planAndExecute(any(), any(), any(), any(), isNull())).thenReturn(List.of(
                org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolResult.newBuilder()
                        .setTool("search_records").setSummary("search_records:3条").setSuccess(true)
                        .setPayloadJson("{\"count\":3}").build()));
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());

        CapturingEmitter emitter = new CapturingEmitter();
        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("最近学了什么").sessionId(SESSION_ID).build(), emitter);

        verify(toolOrchestrator).planAndExecute(eq(USER_ID), eq(SESSION_ID), any(), eq("最近学了什么"), isNull());
        verify(toolOrchestrator, never()).loopAndExecute(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Agent 路径不预先抽意图或检索；规划历史窗口与回答历史窗口独立")
    void chatLoopEnabled_skipsPreRetrievalAndKeepsHistoryWindows() {
        vaultProperties.setChatLoopEnabled(true);
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());

        CapturingEmitter emitter = new CapturingEmitter();
        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("最近学了什么").sessionId(SESSION_ID).build(), emitter);

        verify(toolOrchestrator).loopAndExecute(eq(USER_ID), eq(SESSION_ID), any(), eq("最近学了什么"),
                any(), isNull(), any());
        verify(aiGrpcClient, never()).extractIntent(any(), anyString(), any());
        verify(searchMapper, never()).searchStructured(any(), any(), any(), any(), any(), any(), anyInt());
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), any(), anyInt());
        verify(searchMapper, never()).searchHybrid(any(), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt());
        verify(aiGrpcClient).chatStream(eq(USER_ID), any());
        org.junit.jupiter.api.Assertions.assertTrue(emitter.sent.stream().anyMatch(e -> e.contains("回答")));
        // 两个历史窗口互不干扰：规划器 6 轮=12 条，答案侧 HISTORY_ROUNDS=3 轮=6 条
        verify(historyMapper).selectRecentForModel(SESSION_ID, USER_ID, 12);
        verify(historyMapper).selectRecentForModel(SESSION_ID, USER_ID, 6);
    }

    @Test
    @DisplayName("循环回调实时透传 SSE：thinking 逐块发 thinking 事件，每步发累积 meta.tools_used")
    void loopCallbacks_streamThinkingAndToolChips() {
        vaultProperties.setChatLoopEnabled(true);
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenReturn(List.of(answerChunk()).iterator());
        when(toolOrchestrator.loopAndExecute(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    org.xianshen.mumirrorb.tools.ToolOrchestrator.StepListener listener = inv.getArgument(6);
                    listener.onThinking("先看看");
                    listener.onThinking("他写过什么");
                    listener.onStepDone(List.of("get_stats:记录12条/30天"));
                    listener.onStepDone(List.of("get_stats:记录12条/30天", "search_records:3条"));
                    return List.of();
                });
        CapturingEmitter emitter = new CapturingEmitter();

        chatService.chat(USER_ID, ChatRequestDTO.builder()
                .question("最近学了什么").sessionId(SESSION_ID).build(), emitter);

        // thinking 逐块：两个独立事件，payload 结构与 streamAnswer 的 thinking 同构
        List<String> thinking = emitter.sent.stream().filter(e -> e.contains("event:thinking")).toList();
        org.junit.jupiter.api.Assertions.assertEquals(2, thinking.size());
        org.junit.jupiter.api.Assertions.assertTrue(thinking.get(0).contains("{\"content\":\"先看看\"}"));
        org.junit.jupiter.api.Assertions.assertTrue(thinking.get(1).contains("{\"content\":\"他写过什么\"}"));
        // meta.tools_used 累积全量（前端覆盖式赋值 → 芯片增长）
        List<String> chips = emitter.sent.stream().filter(e -> e.contains("tools_used")).toList();
        org.junit.jupiter.api.Assertions.assertEquals(2, chips.size());
        org.junit.jupiter.api.Assertions.assertTrue(chips.get(1).contains("search_records:3条"));
        org.junit.jupiter.api.Assertions.assertTrue(chips.get(1).contains("get_stats:记录12条/30天"));
    }

    /** 抓取 SSE 事件原文的 emitter（SseEmitter 未绑定请求时 send 会抛，这里直接截住） */
    private static class CapturingEmitter extends SseEmitter {
        private final List<String> sent = new java.util.ArrayList<>();

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            builder.build().forEach(d -> sb.append(d.getData()));
            sent.add(sb.toString());
        }
    }
}
