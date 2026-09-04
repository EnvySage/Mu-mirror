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
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话四路检索单元测试（设计文档 6.6 / 13.1）
 *
 * <p>覆盖：路由分发（PROFILE 快照/回退 HYBRID、STRUCTURED SQL、SEMANTIC/HYBRID 衰减开关）、
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
    private ProfileSnapshotMapper snapshotMapper;
    @Mock
    private SettingsMapper settingsMapper;
    @Mock
    private AiGrpcClient aiGrpcClient;

    private ChatServiceImpl chatService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(sessionMapper, historyMapper, searchMapper,
                snapshotMapper, settingsMapper, aiGrpcClient, new com.fasterxml.jackson.databind.ObjectMapper());

        ChatSession session = ChatSession.builder()
                .id(SESSION_ID).userId(USER_ID).title("测试")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(settingsMapper.selectOne(any())).thenReturn(UserSettings.builder().ragHalfLife(30).build());
        when(aiGrpcClient.embed(eq(USER_ID), anyString())).thenReturn(EmbeddingProto.EmbedResponse.newBuilder()
                .setDimension(1024)
                .addVector(0.1f).addVector(0.2f)
                .build());
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

    @Test
    @DisplayName("SEMANTIC 路由：无 time_range → 开衰减（halfLife=30）")
    void semantic_appliesDecay() {
        when(aiGrpcClient.extractIntent(USER_ID, "最近学了什么")).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), anyInt());
        verify(searchMapper, never()).searchStructured(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("HYBRID 路由：time_range 非空 → 关衰减（用户点名时间不应降权）")
    void hybrid_timeRange_disablesDecay() {
        when(aiGrpcClient.extractIntent(USER_ID, "上个月在忙什么")).thenReturn(intent("hybrid", "上个月"));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                eq(false), eq(30.0), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("上个月在忙什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                eq(false), eq(30.0), anyInt());
    }

    @Test
    @DisplayName("STRUCTURED 路由：纯 SQL 过滤，不调 Embed 不走向量")
    void structured_noVectorSearch() {
        when(aiGrpcClient.extractIntent(USER_ID, "我的待办")).thenReturn(intent("structured", ""));
        when(searchMapper.searchStructured(eq(USER_ID), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("我的待办").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(searchMapper).searchStructured(eq(USER_ID), any(), any(), any(), any(), any(), anyInt());
        verify(aiGrpcClient, never()).embed(any(), anyString());
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("PROFILE 路由：有快照查快照；无快照回退 HYBRID")
    void profile_fallsBackToHybrid_whenNoSnapshot() {
        when(aiGrpcClient.extractIntent(USER_ID, "我是什么样的人")).thenReturn(intent("profile", ""));
        when(snapshotMapper.selectRecent(USER_ID, "manual", 2)).thenReturn(List.of());
        when(snapshotMapper.selectRecent(USER_ID, "monthly", 2)).thenReturn(List.of());
        when(aiGrpcClient.extractIntent(USER_ID, "我是什么样的人")).thenReturn(intent("profile", ""));
        when(searchMapper.searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), eq(30.0), anyInt())).thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("我是什么样的人").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(snapshotMapper).selectRecent(USER_ID, "manual", 2);
        verify(snapshotMapper).selectRecent(USER_ID, "monthly", 2);
        verify(searchMapper).searchHybrid(eq(USER_ID), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), eq(30.0), anyInt());
    }

    @Test
    @DisplayName("PROFILE 路由：manual+monthly 共 ≤2 份快照作上下文")
    void profile_usesSnapshots() {
        ProfileSnapshot monthly = ProfileSnapshot.builder()
                .id(9L).userId(USER_ID).snapshotType("monthly")
                .overallSummary("九月总结").createdAt(OffsetDateTime.now())
                .build();
        when(aiGrpcClient.extractIntent(USER_ID, "我是什么样的人")).thenReturn(intent("profile", ""));
        when(snapshotMapper.selectRecent(USER_ID, "manual", 2)).thenReturn(List.of());
        when(snapshotMapper.selectRecent(USER_ID, "monthly", 2)).thenReturn(List.of(monthly));
        when(aiGrpcClient.chatStream(eq(USER_ID), any()))
                .thenReturn(List.of(answerChunk()).iterator());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("我是什么样的人").sessionId(SESSION_ID).build(), new SseEmitter());

        // 未回退到 HYBRID / SEMANTIC
        verify(searchMapper, never()).searchHybrid(any(), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), anyInt());
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), anyInt());
        // Chat 请求携带了画像伪 chunk
        org.mockito.ArgumentCaptor<MirrorChatProto.ChatRequest> captor =
                org.mockito.ArgumentCaptor.forClass(MirrorChatProto.ChatRequest.class);
        verify(aiGrpcClient).chatStream(eq(USER_ID), captor.capture());
        MirrorChatProto.ChatRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(1, request.getChunksCount());
        org.junit.jupiter.api.Assertions.assertEquals("镜子画像", request.getChunks(0).getTitle());
    }

    @Test
    @DisplayName("检索为空 → 兜底文案落库 assistant 消息（6.6）")
    void emptyRetrieval_fallbackPersisted() {
        when(aiGrpcClient.extractIntent(USER_ID, "说了什么")).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), anyInt()))
                .thenReturn(List.of());

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("说了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(aiGrpcClient, never()).chatStream(any(), any());
        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole()) && "没有找到相关记录".equals(h.getContent())));
    }

    @Test
    @DisplayName("AI 流失败 → 兜底'暂时无法回答'落库（6.6）")
    void chatStreamFails_fallbackPersisted() {
        when(aiGrpcClient.extractIntent(USER_ID, "最近学了什么")).thenReturn(intent("semantic", ""));
        when(searchMapper.searchSemantic(eq(USER_ID), anyString(), eq(true), eq(30.0), anyInt()))
                .thenReturn(hit());
        when(aiGrpcClient.chatStream(eq(USER_ID), any())).thenThrow(new RuntimeException("gRPC down"));

        chatService.chat(USER_ID, ChatRequestDTO.builder().question("最近学了什么").sessionId(SESSION_ID).build(), new SseEmitter());

        verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat(h ->
                "assistant".equals(h.getRole()) && "暂时无法回答".equals(h.getContent())));
    }
}
