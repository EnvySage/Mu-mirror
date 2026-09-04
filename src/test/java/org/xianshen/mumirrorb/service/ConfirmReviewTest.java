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
        when(aiGrpcClient.classifySingle(eq(USER_ID), any()))
                .thenThrow(new RuntimeException("gRPC down"));
        when(aiGrpcClient.embed(eq(USER_ID), any())).thenReturn(embedResponse());

        recordService.confirmReview(RECORD_ID, USER_ID);

        verify(aiGrpcClient).classifySingle(eq(USER_ID), eq("改过的文本"));
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
        verify(aiGrpcClient, never()).classifySingle(any(), any());
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
        when(aiGrpcClient.classifySingle(eq(USER_ID), eq("新文本")))
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
}
