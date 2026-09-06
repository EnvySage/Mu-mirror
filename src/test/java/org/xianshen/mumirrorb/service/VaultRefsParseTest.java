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
                MAPPER);
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
    @DisplayName("无 vault 工具结果时 [F1] 不产出（防串台）")
    void noToolResult_noRefs() throws Exception {
        assertTrue(parse("[F1]", List.of()).isEmpty());
    }
}
