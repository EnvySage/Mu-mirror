package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.VaultItemMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.VaultItem;
import org.xianshen.mumirrorb.service.impl.DigestService;
import org.xianshen.mumirrorb.vault.ContentExtractor;
import org.xianshen.mumirrorb.vault.VaultStorage;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DigestService 单测（fix-batch B5 拆分 + B7 五态/确认门禁）
 *
 * <p>覆盖：文本提取落 chunk 不 embed → extracted（确认门禁核心：提取阶段零向量）/
 * 图片 extracted（Y4 如实）/ 音视频 skipped / 无文本 failed / confirm 生成 key chunk
 * （keyChunk='true' 白名单标记 + contentType='note'）并 embed 全文 → confirmed /
 * key embed 失败保持 extracted / confirmed 幂等。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestServiceTest {

    @Mock
    private VaultItemMapper itemMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private VaultStorage storage;
    @Mock
    private AiGrpcClient aiGrpcClient;

    private DigestService digestService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Long ITEM_ID = 42L;

    /** force ContentExtractor 缓存无关——txt 直解码，无需打桩 */
    private VaultItem textItem() {
        return VaultItem.builder()
                .id(ITEM_ID).userId(USER_ID).originalName("开题报告.txt")
                .storageKey("v42:uuid.txt").sizeBytes(64L).mime("text/plain")
                .category("learning").description("RAG 检索方向")
                .digestStatus("pending")
                .build();
    }

    @BeforeEach
    void setUp() {
        digestService = new DigestService(itemMapper, recordMapper, chunkMapper,
                storage, new VaultProperties(), aiGrpcClient);
        doReturn(EmbeddingProto.EmbedResponse.newBuilder().setDimension(1024)
                .addVector(0.1f).addVector(0.2f).build())
                .when(aiGrpcClient).embed(eq(USER_ID), anyString());
    }

    private void stubBlob(String content) {
        doReturn(org.xianshen.mumirrorb.pojo.DO.VaultBlob.builder()
                .vaultItemId(ITEM_ID).data(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build()).when(storage).get("v42:uuid.txt");
    }

    // ==================== 提取管道（B7 五态：extracted 停，不 embed） ====================

    @Test
    @DisplayName("文本提取：落虚 Record + chunk（不 embed）→ extracted；chunk 无向量（确认门禁）")
    void digestText_landsChunkWithoutEmbed() {
        VaultItem item = textItem();
        stubBlob("第一章 RAG 检索优化研究……");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);

        digestService.digestAsync(USER_ID, ITEM_ID);

        // 虚 Record：source='vault'（进时间线隔离口径）
        ArgumentCaptor<Record> recordCaptor = ArgumentCaptor.forClass(Record.class);
        verify(recordMapper).insert(recordCaptor.capture());
        assertEquals("vault", recordCaptor.getValue().getSource());

        // chunk 落库但 embedding 为 null（确认后才 embed——B7 核心断言）
        ArgumentCaptor<Chunk> chunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper).insert(chunkCaptor.capture());
        assertNull(chunkCaptor.getValue().getEmbedding());
        assertEquals(ITEM_ID, chunkCaptor.getValue().getVaultItemId());
        assertEquals(42L, chunkCaptor.getValue().getMetadata().get("vaultItemId"));

        // 状态停 extracted；aiGrpcClient.embed 全程未调用
        assertEquals("extracted", item.getDigestStatus());
        verify(aiGrpcClient, never()).embed(any(), anyString());
    }

    @Test
    @DisplayName("图片：半消化 → extracted（Y4 如实口径，无全文 chunk）")
    void digestImage_extracted() {
        VaultItem item = VaultItem.builder().id(ITEM_ID).userId(USER_ID)
                .originalName("photo.png").storageKey("v42:u.png").mime("image/png")
                .digestStatus("pending").build();
        doReturn(item).when(itemMapper).selectById(ITEM_ID);

        digestService.digestAsync(USER_ID, ITEM_ID);

        assertEquals("extracted", item.getDigestStatus());
        verify(recordMapper, never()).insert(any(Record.class));
        verify(chunkMapper, never()).insert(any(Chunk.class));
        verify(aiGrpcClient, never()).embed(any(), anyString());
    }

    @Test
    @DisplayName("音视频：零消化 → skipped")
    void digestAudio_skipped() {
        VaultItem item = VaultItem.builder().id(ITEM_ID).userId(USER_ID)
                .originalName("memo.mp3").storageKey("v42:u.mp3").mime("audio/mpeg")
                .digestStatus("pending").build();
        doReturn(item).when(itemMapper).selectById(ITEM_ID);

        digestService.digestAsync(USER_ID, ITEM_ID);

        assertEquals("skipped", item.getDigestStatus());
    }

    @Test
    @DisplayName("无文本产出（空白 PDF）→ failed（管道隔离）")
    void digestEmptyText_failed() {
        VaultItem item = textItem();
        stubBlob("");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);

        digestService.digestAsync(USER_ID, ITEM_ID);

        assertEquals("failed", item.getDigestStatus());
        verify(recordMapper, never()).insert(any(Record.class));
    }

    // ==================== 确认门禁（B7：确认才 embed） ====================

    @Test
    @DisplayName("confirm：更新元数据 → 生成 key chunk（keyChunk='true'+contentType='note'+embed）→ 全文 embed → confirmed")
    void confirmDigest_keyChunkAndConfirmed() {
        VaultItem item = textItem();
        item.setDigestStatus("extracted");
        stubBlob("第一章 RAG 检索优化研究……");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);
        // 提取阶段先跑一次：落全文 chunk（无向量）
        digestService.digestAsync(USER_ID, ITEM_ID);
        Chunk fullTextChunk = captureLastChunk();
        doReturn(List.of(fullTextChunk)).when(chunkMapper).selectList(any());
        // key chunk 挂靠 record 反查
        doReturn(fullTextChunk).when(chunkMapper).selectOne(any());

        digestService.confirmDigest(item, "RAG 毕业论文", "一份关于 RAG 检索的开题报告", "learning");

        // ② key chunk：embed 文本 = key+description+类型（§3.3c）
        ArgumentCaptor<String> embedText = ArgumentCaptor.forClass(String.class);
        verify(aiGrpcClient, org.mockito.Mockito.times(2)).embed(eq(USER_ID), embedText.capture());
        assertEquals("RAG 毕业论文：一份关于 RAG 检索的开题报告，文本", embedText.getAllValues().get(0));

        // key chunk metadata：keyChunk='true'（B1 白名单标记）+ contentType='note'
        // （chunkMapper.insert 共 2 次：提取阶段全文 chunk + 确认阶段 key chunk；取第 2 次）
        ArgumentCaptor<Chunk> keyChunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, org.mockito.Mockito.times(2)).insert(keyChunkCaptor.capture());
        Chunk keyChunk = keyChunkCaptor.getAllValues().get(1);
        assertEquals("true", String.valueOf(keyChunk.getMetadata().get("keyChunk")));
        assertEquals("note", String.valueOf(keyChunk.getMetadata().get("contentType")));
        assertNotNull(keyChunk.getEmbedding()); // 确认后才有向量
        assertEquals(ITEM_ID, keyChunk.getVaultItemId());

        // ③ 全文 chunk 这时才 embed
        assertNotNull(fullTextChunk.getEmbedding());
        // ④ 状态 confirmed
        assertEquals("confirmed", item.getDigestStatus());
        // 元数据已更新（key/description/category 用户改后提交）
        assertEquals("RAG 毕业论文", item.getOriginalName());
        assertEquals("一份关于 RAG 检索的开题报告", item.getDescription());
    }

    @Test
    @DisplayName("confirm：key chunk embed 失败 → 保持 extracted 可重试（不进 confirmed）")
    void confirmDigest_embedFail_staysExtracted() {
        VaultItem item = textItem();
        item.setDigestStatus("extracted");
        stubBlob("正文");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);
        digestService.digestAsync(USER_ID, ITEM_ID);
        Chunk fullTextChunk = captureLastChunk();
        doReturn(fullTextChunk).when(chunkMapper).selectOne(any());
        doThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE))
                .when(aiGrpcClient).embed(eq(USER_ID), anyString());

        digestService.confirmDigest(item, "新名", null, null);

        assertEquals("extracted", item.getDigestStatus());
        // key chunk 没落：extract 阶段全文 chunk 已 insert 1 次，confirm 阶段不再新增（仍 1 次）
        verify(chunkMapper, org.mockito.Mockito.times(1)).insert(any(Chunk.class));
        // 元数据更新保留（用户的输入不丢）
        assertEquals("新名", item.getOriginalName());
    }

    @Test
    @DisplayName("confirm：已 confirmed 幂等直接返回（不重建 key chunk 不重复 embed）")
    void confirmDigest_confirmed_idempotent() {
        VaultItem item = textItem();
        item.setDigestStatus("confirmed");

        VaultItem out = digestService.confirmDigest(item, "x", null, null);

        assertEquals("confirmed", out.getDigestStatus());
        verify(chunkMapper, never()).insert(any(Chunk.class));
        verify(aiGrpcClient, never()).embed(any(), anyString());
    }

    @Test
    @DisplayName("confirm：图片资产（无全文 chunk）→ lazily 建锚点 Record，key chunk 落库")
    void confirmDigest_image_lazyRecordAnchor() {
        VaultItem item = VaultItem.builder().id(ITEM_ID).userId(USER_ID)
                .originalName("photo.png").storageKey("v42:u.png").mime("image/png")
                .digestStatus("extracted").description("周末爬山").build();
        doReturn(item).when(itemMapper).selectById(ITEM_ID);
        doReturn(List.of()).when(chunkMapper).selectList(any()); // 无全文 chunk
        doReturn(null).when(chunkMapper).selectOne(any()); // 无既有锚点

        digestService.confirmDigest(item, null, "周末爬山", null);

        ArgumentCaptor<Record> recordCaptor = ArgumentCaptor.forClass(Record.class);
        verify(recordMapper).insert(recordCaptor.capture()); // lazily 建 key chunk 锚点
        assertEquals("vault", recordCaptor.getValue().getSource());
        ArgumentCaptor<Chunk> keyChunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper).insert(keyChunkCaptor.capture());
        assertEquals("true", String.valueOf(keyChunkCaptor.getValue().getMetadata().get("keyChunk")));
        assertEquals("confirmed", item.getDigestStatus());
    }

    private Chunk captureLastChunk() {
        ArgumentCaptor<Chunk> captor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== B8 竞态审计：定向更新（不再全列覆盖） ====================

    static {
        // LambdaUpdateWrapper 生成 SET 片段需要实体的 lambda 缓存（TableInfo）；
        // 纯 Mockito 单测无 MyBatis 环境，手动初始化
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                VaultItem.class);
    }

    @Test
    @DisplayName("B8 竞态审计：消化完成的 extract 状态回写走定向 SET（source_chunk_id+digest_status），不整行覆盖")
    void digest_extracted_usesTargetedUpdate() {
        VaultItem item = textItem();
        stubBlob("第一章 RAG 检索优化研究……");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);

        digestService.digestAsync(USER_ID, ITEM_ID);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VaultItem>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(itemMapper).update(org.mockito.Mockito.eq(null), wrapper.capture());
        String setSql = wrapper.getValue().getSqlSet();
        assertTrue(setSql.contains("source_chunk_id"), "SET 应含 source_chunk_id: " + setSql);
        assertTrue(setSql.contains("digest_status"), "SET 应含 digest_status: " + setSql);
        // 并发敏感列绝不进写集（description/category 是 HTTP 线程 update() 的领地）
        assertTrue(!setSql.contains("description"), "SET 不得含 description: " + setSql);
        assertTrue(!setSql.contains("category"), "SET 不得含 category: " + setSql);
        verify(itemMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("B8 竞态审计：digestAsync 异常路径 failed 也走定向 SET，只动 digest_status")
    void digest_failed_usesTargetedStatusPatch() {
        VaultItem item = textItem();
        stubBlob("正文");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);
        doThrow(new RuntimeException("storage down")).when(storage).get("v42:uuid.txt");

        digestService.digestAsync(USER_ID, ITEM_ID);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VaultItem>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(itemMapper).update(org.mockito.Mockito.eq(null), wrapper.capture());
        String setSql = wrapper.getValue().getSqlSet();
        assertEquals("digest_status=#{ew.paramNameValuePairs.MPGENVAL1}", setSql);
        verify(itemMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("B8 竞态审计：confirm 元数据更新走定向 SET（original_name/description/category），两处 updateById 已消失")
    void confirm_metadata_usesTargetedUpdate() {
        VaultItem item = textItem();
        item.setDigestStatus("extracted");
        stubBlob("正文");
        doReturn(item).when(itemMapper).selectById(ITEM_ID);
        doReturn(List.of()).when(chunkMapper).selectList(any());
        doReturn(null).when(chunkMapper).selectOne(any());

        digestService.confirmDigest(item, "新名", "新描述", "work");

        // ① 元数据定向更新（不含 digest_status——confirmed 是最后一步单独 SET）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VaultItem>> wrappers =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).update(org.mockito.Mockito.eq(null), wrappers.capture());
        String metaSet = wrappers.getAllValues().get(0).getSqlSet();
        assertTrue(metaSet.contains("original_name"), "SET 应含 original_name: " + metaSet);
        assertTrue(metaSet.contains("description"), "SET 应含 description: " + metaSet);
        assertTrue(metaSet.contains("category"), "SET 应含 category: " + metaSet);
        assertTrue(!metaSet.contains("digest_status"), "元数据 SET 不得含 digest_status: " + metaSet);
        // ④ confirmed 状态定向 SET
        String confirmedSet = wrappers.getAllValues().get(1).getSqlSet();
        assertTrue(confirmedSet.contains("digest_status"), "SET 应含 digest_status: " + confirmedSet);
        assertTrue(!confirmedSet.contains("original_name"), "状态 SET 不得含 original_name: " + confirmedSet);
        verify(itemMapper, never()).updateById(any());
    }
}
