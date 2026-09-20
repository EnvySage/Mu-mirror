package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.service.impl.RecordServiceImpl;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * confirmReview 补分类 + 兜底逻辑单元测试（设计文档 13.1 / 5.4）
 *
 * 覆盖：
 * - 无 Chunk 报错
 * - 补分类失败不阻断（仍 DONE）
 * - Embedding 失败不阻断（仍 DONE）
 * - classified_segment 非空的 Chunk 不调 LLM
 * - 补分类成功回填 metadata + classified_segment
 */
@ExtendWith(MockitoExtension.class)
class ConfirmReviewTest {

    @Mock
    private RecordMapper recordMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private AiGrpcClient aiGrpcClient;

    @InjectMocks
    private RecordServiceImpl recordService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Long RECORD_ID = 1L;

    private Record record;

    @BeforeEach
    void setUp() {
        record = Record.builder()
                .id(RECORD_ID)
                .userId(USER_ID)
                .content("今天学了 Spring Boot，下午去健身")
                .source("user")
                .status(RecordStatus.REVIEWING)
                .userReviewed(false)
                .build();
    }

    private RecordProcessorProto.ClassifyResponse singleResponse(String title) {
        RecordProcessorProto.ClassifyItem item = RecordProcessorProto.ClassifyItem.newBuilder()
                .setTitle(title)
                .setSummary("摘要")
                .setContent("文本")
                .setContentType(org.xianshen.mumirrorb.grpc.gen.CommonProto.ContentType.LEARNING)
                .build();
        return RecordProcessorProto.ClassifyResponse.newBuilder()
                .setSkip(false)
                .addItems(item)
                .build();
    }

    /** 带时间词替换表的分类响应（AI 判别出"今天" → 具体日期） */
    private RecordProcessorProto.ClassifyResponse singleResponseWithTimeSub(String original, String resolved) {
        RecordProcessorProto.ClassifyItem item = RecordProcessorProto.ClassifyItem.newBuilder()
                .setTitle("标题")
                .setSummary("摘要")
                .setContent("文本")
                .setContentType(org.xianshen.mumirrorb.grpc.gen.CommonProto.ContentType.LEARNING)
                .addTimeSubstitutions(RecordProcessorProto.TimeSubstitution.newBuilder()
                        .setOriginal(original)
                        .setResolved(resolved)
                        .build())
                .build();
        return RecordProcessorProto.ClassifyResponse.newBuilder()
                .setSkip(false)
                .addItems(item)
                .build();
    }

    private EmbeddingProto.EmbedResponse embedResponse() {
        return EmbeddingProto.EmbedResponse.newBuilder()
                .setDimension(1024)
                .setModelName("bge-m3")
                .addVector(0.1f).addVector(0.2f)
                .build();
    }

    @Test
    @DisplayName("无 Chunk 时 confirm 报错（设计文档 5.2 约束）")
    void confirm_withNoChunks_throws() {
        when(recordMapper.selectOne(any())).thenReturn(record);
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> recordService.confirmReview(RECORD_ID, USER_ID));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(recordMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("非 REVIEWING 状态 confirm 报错")
    void confirm_notReviewing_throws() {
        record.setStatus(RecordStatus.DONE);
        when(recordMapper.selectOne(any())).thenReturn(record);

        assertThrows(BusinessException.class, () -> recordService.confirmReview(RECORD_ID, USER_ID));
        verify(recordMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("补分类失败不阻断：仍 DONE，metadata 保留旧值（5.6）")
    void confirm_classifyFails_stillDone() {
        when(recordMapper.selectOne(any())).thenReturn(record);
        Chunk chunk = Chunk.builder().id(10L).recordId(RECORD_ID).userId(USER_ID)
                .content("原文").segment("改过的文本").classifiedSegment(null) // 文本已改，需补分类
                .build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(aiGrpcClient.classifySingle(eq(USER_ID), any(), eq(RECORD_ID), any()))
                .thenThrow(new RuntimeException("gRPC down"));
        when(aiGrpcClient.embed(eq(USER_ID), any())).thenReturn(embedResponse());

        recordService.confirmReview(RECORD_ID, USER_ID);

        // 参照日期为 null：本测试 record 未设 createdAt（AI 侧按无参照日期处理，退化为不消解）
        verify(aiGrpcClient).classifySingle(eq(USER_ID), eq("改过的文本"), eq(RECORD_ID), isNull());
        assertEquals(RecordStatus.DONE, record.getStatus());
        assertTrue(record.getUserReviewed());
        assertNull(chunk.getMetadata());
        assertNull(chunk.getClassifiedSegment());
    }

    @Test
    @DisplayName("Embedding 失败不阻断：仍 DONE（裁决 #5）")
    void confirm_embedFails_stillDone() {
        when(recordMapper.selectOne(any())).thenReturn(record);
        Chunk chunk = Chunk.builder().id(10L).recordId(RECORD_ID).userId(USER_ID)
                .content("原文").segment("片段").classifiedSegment("片段") // 文本未变，无需补分类
                .build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(aiGrpcClient.embed(eq(USER_ID), any()))
                .thenThrow(new RuntimeException("embed down"));

        recordService.confirmReview(RECORD_ID, USER_ID);

        // 文本未变 → 不调 LLM
        verify(aiGrpcClient, never()).classifySingle(any(), any(), any(), any());
        assertEquals(RecordStatus.DONE, record.getStatus());
        assertTrue(record.getUserReviewed());
        assertNull(chunk.getEmbedding());
    }

    @Test
    @DisplayName("补分类成功：回填 metadata + classified_segment")
    void confirm_classifySucceeds_fillsMetadata() {
        when(recordMapper.selectOne(any())).thenReturn(record);
        Chunk chunk = Chunk.builder().id(10L).recordId(RECORD_ID).userId(USER_ID)
                .content("原文").segment("新文本").classifiedSegment(null)
                .build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(aiGrpcClient.classifySingle(eq(USER_ID), eq("新文本"), eq(RECORD_ID), any()))
                .thenReturn(singleResponse("学Spring Boot"));
        when(aiGrpcClient.embed(eq(USER_ID), any())).thenReturn(embedResponse());

        recordService.confirmReview(RECORD_ID, USER_ID);

        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, atLeastOnce()).updateById(captor.capture());
        Chunk saved = captor.getValue();
        assertEquals("learning", saved.getMetadata().get("contentType"));
        assertEquals("新文本", saved.getClassifiedSegment());
        assertEquals(RecordStatus.DONE, record.getStatus());
    }

    @Test
    @DisplayName("补分类返回时间替换表 → segment 消解，且 embedding 吃的是消解后文本")
    void confirm_timeSubstitution_resolvesSegmentAndEmbedding() {
        when(recordMapper.selectOne(any())).thenReturn(record);
        Chunk chunk = Chunk.builder().id(11L).recordId(RECORD_ID).userId(USER_ID)
                .content("今天学了 Spring Boot")
                .segment("今天学了 Spring Boot")
                .classifiedSegment(null) // 需补分类
                .build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(aiGrpcClient.classifySingle(eq(USER_ID), eq("今天学了 Spring Boot"), eq(RECORD_ID), any()))
                .thenReturn(singleResponseWithTimeSub("今天", "9月19日"));
        when(aiGrpcClient.embed(eq(USER_ID), any())).thenReturn(embedResponse());

        recordService.confirmReview(RECORD_ID, USER_ID);

        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, atLeastOnce()).updateById(captor.capture());
        Chunk saved = captor.getValue();
        // segment（展示/embedding 文本）已消解，classified_segment 同步记消解后文本
        assertEquals("9月19日学了 Spring Boot", saved.getSegment());
        assertEquals("9月19日学了 Spring Boot", saved.getClassifiedSegment());
        // content 原文不动（红线：用户输入不可修改）
        assertEquals("今天学了 Spring Boot", saved.getContent());
        // 关键：embedding 发生在消解之后，吃到的是带真日期的文本
        verify(aiGrpcClient).embed(eq(USER_ID), eq("9月19日学了 Spring Boot"));
    }
}
