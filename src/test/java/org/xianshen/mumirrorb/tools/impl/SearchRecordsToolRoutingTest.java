package org.xianshen.mumirrorb.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchRecordsToolRoutingTest {

    @Mock
    private ChatSearchMapper searchMapper;
    @Mock
    private AiGrpcClient aiGrpcClient;
    @Mock
    private SettingsMapper settingsMapper;

    private SearchRecordsTool tool;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tool = new SearchRecordsTool(searchMapper, aiGrpcClient, settingsMapper, new MirrorProperties());
        when(aiGrpcClient.embed(eq(USER_ID), anyString())).thenReturn(
                EmbeddingProto.EmbedResponse.newBuilder().setDimension(2).addVector(0.1f).addVector(0.2f).build());
        when(settingsMapper.selectOne(any())).thenReturn(UserSettings.builder().ragHalfLife(14).build());
    }

    @Test
    @DisplayName("semantic 模式用自然语言向量检索，并保留余弦距离供相关度展示")
    void semanticEmbedsQueryAndReturnsScore() {
        when(searchMapper.searchSemantic(eq(USER_ID), eq("[0.1,0.2]"), eq(true), eq(14.0), eq(0.35), eq(10)))
                .thenReturn(List.of(RetrievedChunkDTO.builder().recordId(7L).title("开题")
                        .content("导师建议调整框架").createdAt("2026-10-12 14:30")
                        .contentType("work").score(0.2).build()));

        var result = tool.execute(USER_ID, Map.of(
                "mode", "semantic", "query", "最近为什么为开题感到压力", "limit", 10));

        assertEquals("search_records:1条（semantic）", result.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> record = ((List<Map<String, Object>>) payload.get("records")).get(0);
        assertEquals(7L, record.get("record_id"));
        assertEquals(0.2, record.get("score"));
        verify(aiGrpcClient).embed(USER_ID, "最近为什么为开题感到压力");
        verify(searchMapper).searchSemantic(eq(USER_ID), eq("[0.1,0.2]"), eq(true), eq(14.0), eq(0.35), eq(10));
        verify(searchMapper, never()).searchHybrid(any(), anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), anyDouble(), any(), anyInt());
    }

    @Test
    @DisplayName("semantic 问题附带明确日期/情绪过滤时自动走 hybrid，并关闭日期范围的时间衰减")
    void semanticWithFiltersUsesHybrid() {
        when(searchMapper.searchHybrid(eq(USER_ID), eq("[0.1,0.2]"), isNull(), eq(List.of("anxious")),
                eq("{\"anxious\"}"), any(), any(), eq(false), eq(14.0), eq(0.35), eq(10)))
                .thenReturn(List.of());

        var result = tool.execute(USER_ID, Map.of(
                "mode", "semantic", "query", "当天发生的焦虑事件", "date", "2026-10-15",
                "moods", List.of("anxious"), "limit", 10));

        assertEquals("search_records:0条（hybrid）（2026-10-15）", result.getSummary());
        verify(searchMapper).searchHybrid(eq(USER_ID), eq("[0.1,0.2]"), isNull(), eq(List.of("anxious")),
                eq("{\"anxious\"}"), any(), any(), eq(false), eq(14.0), eq(0.35), eq(10));
        verify(searchMapper, never()).searchSemantic(any(), anyString(), anyBoolean(), anyDouble(), any(), anyInt());
    }
}
