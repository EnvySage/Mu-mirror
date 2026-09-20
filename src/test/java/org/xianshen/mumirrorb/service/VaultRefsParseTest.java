package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.service.impl.ChatServiceImpl;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * extractVaultRefs 编号解析单测（fix-batch B3 / Y3）
 *
 * <p>核心：文件引用只认 [F\d+] 独立编号空间——普通 [1]（日记 sources 引用）不再触发文件卡，
 * [F1] 命中 find_item/recall_item 结果第 1 项。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VaultRefsParseTest {

    @Mock
    private org.xianshen.mumirrorb.mapper.ChatSessionMapper sessionMapper;
    @Mock
    private org.xianshen.mumirrorb.mapper.ConversationHistoryMapper historyMapper;
    @Mock
    private org.xianshen.mumirrorb.mapper.ChatSearchMapper searchMapper;
    @Mock
    private org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper snapshotMapper;
    @Mock
    private org.xianshen.mumirrorb.mapper.SettingsMapper settingsMapper;
    @Mock
    private org.xianshen.mumirrorb.grpc.AiGrpcClient aiGrpcClient;

    private ChatServiceImpl chatService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(sessionMapper, historyMapper, searchMapper,
                snapshotMapper, settingsMapper, aiGrpcClient,
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.GlossaryService.class),
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.tools.ToolOrchestrator.class),
                MAPPER,
                new org.xianshen.mumirrorb.config.MirrorProperties(),
                new org.xianshen.mumirrorb.config.VaultProperties(),
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.mapper.ProfileStatsMapper.class));
    }

    private CommonProto.ToolResult findItemResult() throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "count", 2,
                "items", List.of(
                        Map.of("vault_item_id", 11, "display_name", "开题报告.pdf",
                                "file_type", "pdf", "size", 1024, "digest_status", "confirmed"),
                        Map.of("vault_item_id", 12, "display_name", "笔记.md",
                                "file_type", "md", "size", 512, "digest_status", "extracted"))));
        return CommonProto.ToolResult.newBuilder()
                .setTool("find_item").setSummary("find_item:2个文件")
                .setPayloadJson(payload).setSuccess(true).build();
    }

    /** 反射调私有 extractVaultRefs（与既有生产路径一致，不走 SSE） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parse(String answer, List<CommonProto.ToolResult> results)
            throws Exception {
        Method m = ChatServiceImpl.class.getDeclaredMethod("extractVaultRefs", String.class, List.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(chatService, answer, results);
    }

    @Test
    @DisplayName("[F1] 命中 find_item 第 1 项 → 文件卡（n=1, vault_item_id=11）")
    void fMark_resolvesFileCard() throws Exception {
        List<Map<String, Object>> refs = parse("你的开题报告见 [F1]。", List.of(findItemResult()));
        assertEquals(1, refs.size());
        assertEquals(1, refs.get(0).get("n"));
        assertEquals(11L, refs.get(0).get("vault_item_id"));
        assertEquals("开题报告.pdf", refs.get(0).get("display_name"));
        assertEquals("confirmed", refs.get(0).get("digest_status"));
    }

    @Test
    @DisplayName("普通 [1]（sources 引用）不再触发文件卡——B3 核心回归断言")
    void plainMark_noFileCard() throws Exception {
        List<Map<String, Object>> refs = parse("根据你的日记 [1]，你最近……", List.of(findItemResult()));
        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("[F2] 取第 2 项；[F1][F2] 同气泡去重后按序返回；越界 [F3] 忽略")
    void fMark_orderAndDedup() throws Exception {
        List<Map<String, Object>> refs = parse("[F2] 和 [F1]，另见 [F3]（越界）。重复 [F1]。",
                List.of(findItemResult()));
        assertEquals(2, refs.size());
        assertEquals(1, refs.get(0).get("n"));
        assertEquals(12L, refs.get(1).get("vault_item_id"));
    }

    @Test
    @DisplayName("recall_item 单 item payload（无 items 列表）同样命中")
    void recallItem_payload() throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "item", Map.of("vault_item_id", 33, "display_name", "论文.pdf",
                        "file_type", "pdf", "digest_status", "confirmed")));
        CommonProto.ToolResult tr = CommonProto.ToolResult.newBuilder()
                .setTool("recall_item").setPayloadJson(payload).setSuccess(true).build();
        List<Map<String, Object>> refs = parse("文件内容见 [F1]。", List.of(tr));
        assertEquals(1, refs.size());
        assertEquals(33L, refs.get(0).get("vault_item_id"));
    }

    @Test
    @DisplayName("recall_item 真实 payload（RecallItemTool 实产）字段与 find_item 同构 → 出卡（防驼峰回归）")
    void recallItem_realPayloadFromTool() throws Exception {
        // 回归护栏：此前的用例手工拼蛇形 map，掩盖了 RecallItemTool 直接塞 VaultItemVO
        // 被 Jackson 序列化成驼峰（originalName/digestStatus）的真链路断环。
        org.xianshen.mumirrorb.service.VaultService vaultService =
                org.mockito.Mockito.mock(org.xianshen.mumirrorb.service.VaultService.class);
        org.xianshen.mumirrorb.pojo.VO.VaultItemVO vo =
                org.xianshen.mumirrorb.pojo.VO.VaultItemVO.builder()
                        .id(33L).originalName("论文.pdf").fileType("pdf").sizeBytes(2048L)
                        .digestStatus("confirmed").quote("第一章 绪论").matchLayer("strong").build();
        org.mockito.Mockito.when(vaultService.recall(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(33L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(vo);
        org.xianshen.mumirrorb.tools.impl.RecallItemTool tool =
                new org.xianshen.mumirrorb.tools.impl.RecallItemTool(vaultService);

        org.xianshen.mumirrorb.tools.ToolExecutionResult result =
                tool.execute(UUID.randomUUID(), Map.of("vault_item_id", 33));
        assertTrue(result.isSuccess());
        String payloadJson = MAPPER.writeValueAsString(result.getPayload());
        CommonProto.ToolResult tr = CommonProto.ToolResult.newBuilder()
                .setTool("recall_item").setPayloadJson(payloadJson).setSuccess(true).build();

        List<Map<String, Object>> refs = parse("文件内容见 [F1]。", List.of(tr));
        assertEquals(1, refs.size());
        assertEquals(33L, refs.get(0).get("vault_item_id"));
        assertEquals("论文.pdf", refs.get(0).get("display_name"));
        assertEquals("pdf", refs.get(0).get("file_type"));
        assertEquals("confirmed", refs.get(0).get("digest_status"));
        assertEquals("第一章 绪论", refs.get(0).get("quote"));
    }

    @Test
    @DisplayName("检索侧 vault keyChunk 命中 → 弱引用文件卡；文件记录不混进日记 sources")
    void vaultChunkHit_becomesWeakFileCard() throws Exception {
        org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO vaultChunk =
                org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO.builder()
                        .recordId(9L).vaultItemId(33L).title("mirror项目的设计文档.md")
                        .content("mirror项目的设计文档.md：毕设整体设计，Markdown 文档")
                        .createdAt("2026-09-12 21:13").contentType("note").score(0.42)
                        .build();
        org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO diaryChunk =
                org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO.builder()
                        .recordId(11L).title("导师评审").content("内容")
                        .createdAt("2026-07-20 23:55").contentType("thought").score(0.30)
                        .build();
        List<org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO> chunks = List.of(diaryChunk, vaultChunk);

        // 1) 检索侧 vault 命中 → 文件卡（无 quote → 前端渲染弱引用芯片）
        java.lang.reflect.Method m = ChatServiceImpl.class.getDeclaredMethod(
                "vaultRefsFromChunks", List.class, List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs =
                (List<Map<String, Object>>) m.invoke(chatService, chunks, List.of());
        assertEquals(1, refs.size());
        assertEquals(33L, refs.get(0).get("vault_item_id"));
        assertEquals("mirror项目的设计文档.md", refs.get(0).get("display_name"));
        assertEquals("confirmed", refs.get(0).get("digest_status"));
        org.junit.jupiter.api.Assertions.assertNull(refs.get(0).get("quote"));

        // 2) 工具侧已有同 id 的卡 → 不重复出卡
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> merged = (List<Map<String, Object>>) m.invoke(chatService, chunks,
                List.of(Map.of("n", 1, "vault_item_id", 33L, "quote", "摘录")));
        assertEquals(1, merged.size());

        // 3) 文件记录不进"日记来源"：模型回传的 source 指向 vault 记录时被跳过
        org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatChunk done =
                org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatChunk.newBuilder()
                        .setDone(true)
                        .addSources(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.Source.newBuilder()
                                .setRecordId(9L).setN(1))
                        .addSources(org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.Source.newBuilder()
                                .setRecordId(11L).setN(2))
                        .build();
        java.lang.reflect.Method es = ChatServiceImpl.class.getDeclaredMethod("extractSources",
                org.xianshen.mumirrorb.grpc.gen.MirrorChatProto.ChatChunk.class, List.class);
        es.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) es.invoke(chatService, done, chunks);
        assertEquals(1, sources.size());
        assertEquals(11L, sources.get(0).get("record_id"));
        assertEquals(2, sources.get(0).get("n"));
    }

    @Test
    @DisplayName("无 vault 工具结果时 [F1] 不产出（防串台）")
    void noToolResult_noRefs() throws Exception {
        assertTrue(parse("[F1]", List.of()).isEmpty());
    }
}
