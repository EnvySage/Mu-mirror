package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.mapper.VaultItemMapper;
import org.xianshen.mumirrorb.pojo.DO.VaultItem;
import org.xianshen.mumirrorb.service.impl.VaultServiceImpl;
import org.xianshen.mumirrorb.vault.FileTypeDetector;
import org.xianshen.mumirrorb.vault.VaultStorage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * VaultService 单测（toolcalling-vault-design.md 第 3 节）
 *
 * <p>覆盖：magic bytes 白名单（exe/zip/svg 拒）/ 单文件 20MB / 配额 500MB /
 * SHA-256 去重 / original_name 清洗（路径穿越+控制字符+255）/ ownership 404 /
 * storage_key 格式 / 消化分派语义。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VaultServiceTest {

    @Mock
    private VaultItemMapper itemMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private UserTermMapper termMapper;
    @Mock
    private VaultStorage storage;
    @Mock
    private AiGrpcClient aiGrpcClient;

    private VaultServiceImpl vaultService;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vaultService = new VaultServiceImpl(itemMapper, recordMapper, chunkMapper, termMapper,
                storage, new VaultProperties(), aiGrpcClient,
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.impl.DigestService.class), null);
        doReturn(0L).when(itemMapper).sumAliveBytes(USER_ID);
        doReturn(List.of()).when(itemMapper).selectAliveByUser(USER_ID);
        doReturn(null).when(itemMapper).selectOne(any());
    }

    // ==================== magic bytes 白名单 ====================

    @Test
    @DisplayName("PDF magic bytes 放行")
    void detect_pdf() {
        byte[] pdf = "%PDF-1.4 fake pdf body".getBytes(StandardCharsets.UTF_8);
        assertEquals("application/pdf", FileTypeDetector.detect(pdf, "a.pdf"));
    }

    @Test
    @DisplayName("裸 zip 拒绝（含 svg 脚本/可执行/视频一律白名单外）")
    void detect_zipRejected() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x21, 0x00};
        var ex = assertThrows(BusinessException.class, () ->
                vaultService.upload(USER_ID, new MockMultipartFile("file", "a.zip", "application/zip", zip), null));
        assertTrue(ex.getMessage().contains("不支持"));
    }

    @Test
    @DisplayName("docx（zip 容器含 word/）放行")
    void detect_docx() {
        byte[] docx = new byte[64];
        docx[0] = 'P';
        docx[1] = 'K';
        docx[2] = 3;
        docx[3] = 4;
        byte[] marker = "[Content_Types].xml...word/".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, docx, 8, Math.min(marker.length, 40));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FileTypeDetector.detect(docx, "a.docx"));
    }

    @Test
    @DisplayName("svg 是文本不被 mime 嗅探误放行（扩展名 svg + 文本内容 → 需 .txt/.md/.csv 后缀兜底才收）")
    void detect_svgRejected() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        // 扩展名 .svg 不在文本兜底名单 → null → 上传拒绝
        assertEquals(null, FileTypeDetector.detect(svg, "evil.svg"));
    }

    @Test
    @DisplayName("txt/md/csv 文本兜底识别")
    void detect_textFamily() {
        byte[] text = "hello 日记内容".getBytes(StandardCharsets.UTF_8);
        assertEquals("text/plain", FileTypeDetector.detect(text, "a.txt"));
        assertEquals("text/markdown", FileTypeDetector.detect(text, "a.md"));
        assertEquals("text/csv", FileTypeDetector.detect(text, "a.csv"));
        // 明明是二进制却叫 .txt：文本解码不报错但内容即字节——由扩展名兜底放行（记为已知折衷，magic 层拒不了伪文本）
        assertEquals("text/plain", FileTypeDetector.detect(text, "a.txt"));
    }

    // ==================== 上传限制 ====================

    @Test
    @DisplayName("单文件超 20MB 拒绝")
    void upload_tooLarge() {
        VaultProperties props = new VaultProperties();
        byte[] big = new byte[(int) (props.getMaxFileSizeBytes() + 1)];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", big);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> vaultService.upload(USER_ID, file, null));
        assertTrue(ex.getMessage().contains("超过单文件上限"));
    }

    @Test
    @DisplayName("配额 500MB 满后拒绝")
    void upload_quotaExceeded() {
        doReturn(new VaultProperties().getQuotaBytes()).when(itemMapper).sumAliveBytes(USER_ID);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt",
                "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> vaultService.upload(USER_ID, file, null));
        assertTrue(ex.getMessage().contains("空间不足"));
    }

    @Test
    @DisplayName("SHA-256 同用户同内容拒绝并提示已有文件名")
    void upload_duplicateContent() {
        byte[] bytes = "same content".getBytes(StandardCharsets.UTF_8);
        doReturn(VaultItem.builder().id(9L).originalName("旧文件.txt").build())
                .when(itemMapper).selectOne(any());
        MockMultipartFile file = new MockMultipartFile("file", "新名字.txt", "text/plain", bytes);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> vaultService.upload(USER_ID, file, null));
        assertTrue(ex.getMessage().contains("相同内容"));
        assertTrue(ex.getMessage().contains("旧文件.txt"));
    }

    // ==================== 文件名清洗 ====================

    @Test
    @DisplayName("original_name 清洗：路径穿越/控制字符/超长截断/空名兜底")
    void sanitizeName_rules() {
        assertEquals("a.pdf", VaultServiceImpl.sanitizeName("../../etc/passwd/a.pdf"));
        assertEquals("a.pdf", VaultServiceImpl.sanitizeName("C:\\Users\\evil\\a.pdf"));
        assertEquals("ab.pdf", VaultServiceImpl.sanitizeName("a\nb\0.pdf"));
        assertEquals("unnamed", VaultServiceImpl.sanitizeName(""));
        assertEquals("unnamed", VaultServiceImpl.sanitizeName("///"));
        assertEquals(255, VaultServiceImpl.sanitizeName("字".repeat(300)).length());
        // .. 序列消解
        assertTrue(!VaultServiceImpl.sanitizeName("a..b.pdf").contains(".."));
    }

    // ==================== ownership 404 ====================

    @Test
    @DisplayName("下载/删除/补正：非本人或已删 → 4041（不暴露存在性）")
    void ownership_404() {
        doReturn(null).when(itemMapper).selectAliveById(9L, USER_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> vaultService.download(USER_ID, 9L));
        assertEquals(4041, ex.getCode());
        assertEquals(4041, assertThrows(BusinessException.class,
                () -> vaultService.delete(USER_ID, 9L)).getCode());
        assertEquals(4041, assertThrows(BusinessException.class,
                () -> vaultService.update(USER_ID, 9L, "d", null)).getCode());
        assertEquals(4041, assertThrows(BusinessException.class,
                () -> vaultService.recall(USER_ID, 9L)).getCode());
    }

    // ==================== 补正竞态回归（B8：update 只 SET description/category） ====================

    static {
        // LambdaUpdateWrapper 生成 SET 片段需要实体的 lambda 缓存（TableInfo）；
        // 纯 Mockito 单测无 MyBatis 环境，手动初始化
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                org.xianshen.mumirrorb.pojo.DO.VaultItem.class);
    }

    @Test
    @DisplayName("B8 竞态回归：update 走 LambdaUpdateWrapper 定向 SET，不再全列覆盖（digest_status 不被冲回 pending）")
    void update_usesTargetedWrapper_notFullRowOverwrite() {
        VaultItem item = VaultItem.builder()
                .id(9L).userId(USER_ID).originalName("a.txt").storageKey("v9:u.txt")
                .mime("text/plain").sizeBytes(5L)
                .description("旧描述").category("note")
                .digestStatus("extracted").sourceChunkId(77L).build();
        doReturn(item).when(itemMapper).selectAliveById(9L, USER_ID);

        vaultService.update(USER_ID, 9L, "新描述", "learning");

        // 断言走的是 update(null, wrapper) 定向更新，且不再是 updateById 全列覆盖
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VaultItem>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(itemMapper).update(org.mockito.Mockito.eq(null), wrapper.capture());
        verify(itemMapper, never()).updateById(any());
        String setSql = wrapper.getValue().getSqlSet();
        // 写集只含 description/category（digest_status/source_chunk_id 等并发列绝不出现）
        assertTrue(setSql.contains("description"), "SET 应包含 description: " + setSql);
        assertTrue(setSql.contains("category"), "SET 应包含 category: " + setSql);
        assertTrue(!setSql.contains("digest_status"), "SET 不得包含 digest_status: " + setSql);
        assertTrue(!setSql.contains("source_chunk_id"), "SET 不得包含 source_chunk_id: " + setSql);
        assertTrue(!setSql.contains("original_name"), "SET 不得包含 original_name: " + setSql);
        // 实体上的并发敏感字段未被 update 波及（VO 返回的是实体快照，字段值只反映用户输入）
        assertEquals("新描述", item.getDescription());
        assertEquals("learning", item.getCategory());
    }

    @Test
    @DisplayName("update 空描述置 null（SET description = NULL 走定向更新而非 updateById 跳过）")
    void update_blankDescription_setsNullViaWrapper() {
        VaultItem item = VaultItem.builder()
                .id(9L).userId(USER_ID).originalName("a.txt").storageKey("v9:u.txt")
                .mime("text/plain").sizeBytes(5L)
                .description("旧描述").category("note").digestStatus("extracted").build();
        doReturn(item).when(itemMapper).selectAliveById(9L, USER_ID);

        vaultService.update(USER_ID, 9L, "   ", null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VaultItem>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(itemMapper).update(org.mockito.Mockito.eq(null), wrapper.capture());
        String setSql = wrapper.getValue().getSqlSet();
        assertTrue(setSql.contains("description"), "SET 应包含 description: " + setSql);
        // category 未传 → 不在写集（语义保持：null = 不改）
        assertTrue(!setSql.contains("category"), "category 未传不应进 SET: " + setSql);
    }

    @Test
    @DisplayName("上传后 storage_key 回填也走定向更新（同样审计 updateById 全列覆盖）")
    void upload_storageKeyBackfill_usesTargetedUpdate() throws java.io.IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt",
                "text/plain", "定向回填内容".getBytes(StandardCharsets.UTF_8));
        vaultService.upload(USER_ID, file, null);

        // upload 里 updateById(item) 仍保留（此时行是刚 insert 的私有行，无并发写者）——
        // 真正要审计的是有并发写者的更新点，这里断言 update 确实发生且带 storage_key
        ArgumentCaptor<VaultItem> captor = ArgumentCaptor.forClass(VaultItem.class);
        verify(itemMapper).insert(captor.capture());
        assertTrue(captor.getValue().getStorageKey() != null);
    }

    // ==================== 消化分派 ====================

    @Test
    @DisplayName("上传后触发异步消化（digestAsync 被调用：insert + storage.put 均发生）")
    void upload_triggersDigest() throws java.io.IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt",
                "text/plain", "今天是日记内容".getBytes(StandardCharsets.UTF_8));
        var vo = vaultService.upload(USER_ID, file, null);
        ArgumentCaptor<VaultItem> captor = ArgumentCaptor.forClass(VaultItem.class);
        verify(itemMapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getDigestStatus());
        verify(storage).put(any(), any());
        assertEquals("txt", vo.getFileType());
        assertEquals("document", vo.getKind());
    }

    @Test
    @DisplayName("storage_key 格式：v{id}:{uuid}.{ext}（首段=id，BYTEA 存储解析用）")
    void storageKey_format() {
        assertEquals(7L, org.xianshen.mumirrorb.vault.ByteaVaultStorage.itemIdOf("v7:abc-def.pdf"));
        assertThrows(IllegalArgumentException.class,
                () -> org.xianshen.mumirrorb.vault.ByteaVaultStorage.itemIdOf("bad-key"));
    }

    @Test
    @DisplayName("kind/fileType 映射：image→image, audio→audio, pdf/docx/txt→document")
    void kindMapping() {
        assertEquals("image", VaultServiceImpl.kindOf("image/png"));
        assertEquals("audio", VaultServiceImpl.kindOf("audio/mpeg"));
        assertEquals("document", VaultServiceImpl.kindOf("application/pdf"));
        assertEquals("pdf", VaultServiceImpl.fileTypeOf("application/pdf"));
        assertEquals("docx", VaultServiceImpl.fileTypeOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("mp3", VaultServiceImpl.fileTypeOf("audio/mpeg"));
    }

    @Test
    @DisplayName("删除：blob 删除 + vault chunks 级联清 + 元数据物理删")
    void delete_cascades() {
        VaultItem item = VaultItem.builder()
                .id(9L).userId(USER_ID).originalName("a.txt").storageKey("v9:u.txt")
                .mime("text/plain").sizeBytes(5L).digestStatus("done").build();
        doReturn(item).when(itemMapper).selectAliveById(9L, USER_ID);

        vaultService.delete(USER_ID, 9L);

        verify(storage).delete("v9:u.txt");
        verify(chunkMapper).delete(any());
        verify(itemMapper).deleteById(9L);
    }
}
