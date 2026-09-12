package org.xianshen.mumirrorb.grpc;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.xianshen.mumirrorb.config.RecordContextProperties;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiGrpcClient.recent_context 组装逻辑单元测试（近 7 天记录摘要语境注入）
 *
 * <p>覆盖：同 title 去重保留最近一条、maxHints 上限截断、keywords 上限截断、
 * 空标题跳过、excludeRecordId 透传、装配失败（SQL 异常）降级为空清单。</p>
 */
class AiGrpcClientRecentContextTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private ChunkMapper chunkMapper;
    private AiGrpcClient client;
    private RecordContextProperties props;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(ChunkMapper.class);
        props = new RecordContextProperties();
        // 私有方法不依赖 channel / stub，channel 等传 mock 即可
        client = new AiGrpcClient(
                mock(ManagedChannel.class),
                mock(SettingsMapper.class),
                mock(GlossaryService.class),
                mock(ApplicationContext.class),
                chunkMapper,
                props);
    }

    private static Map<String, Object> row(String title, Object keywordsJson, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("keywordsJson", keywordsJson);
        m.put("date", date);
        return m;
    }

    private List<RecordProcessorProto.RecentHint> invoke(Long excludeRecordId) {
        try {
            Method m = AiGrpcClient.class.getDeclaredMethod("recentContextHints", UUID.class, Long.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<RecordProcessorProto.RecentHint> result =
                    (List<RecordProcessorProto.RecentHint>) m.invoke(client, USER_ID, excludeRecordId);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("同 title 去重保留最近 + keywords 上限截断 + 空标题跳过")
    void assemble_dedupAndTruncate() {
        when(chunkMapper.selectRecentContextHints(eq(USER_ID), any(), any(), eq(50)))
                .thenReturn(List.of(
                        // JSON 字符串（JDBC PGobject.toString 形态）
                        row("学吉他", "[\"吉他\",\"音乐\",\"练习\",\"和弦\",\"节拍\",\"多余\"]", "2026-09-10"),
                        // 同 title 旧条目，应被去重跳过
                        row("学吉他", "[\"旧标题重复\"]", "2026-09-01"),
                        // 已解析 List 形态
                        row("健身", List.of("健身", "运动"), "2026-09-09"),
                        // 空标题跳过
                        row("", "[]", "2026-09-08")
                ));

        List<RecordProcessorProto.RecentHint> hints = invoke(null);

        assertEquals(2, hints.size());
        assertEquals("学吉他", hints.get(0).getTitle());
        assertEquals("2026-09-10", hints.get(0).getDate());
        assertEquals(List.of("吉他", "音乐", "练习", "和弦", "节拍"), hints.get(0).getKeywordsList());
        assertEquals("健身", hints.get(1).getTitle());
        assertEquals(List.of("健身", "运动"), hints.get(1).getKeywordsList());
    }

    @Test
    @DisplayName("maxHints 上限截断：只保留最近 N 条（时间倒序）")
    void assemble_capMaxHints() {
        props.setMaxHints(2);
        when(chunkMapper.selectRecentContextHints(eq(USER_ID), any(), any(), eq(50)))
                .thenReturn(List.of(
                        row("A", "[]", "2026-09-10"),
                        row("B", "[]", "2026-09-09"),
                        row("C", "[]", "2026-09-08")
                ));

        List<RecordProcessorProto.RecentHint> hints = invoke(null);

        assertEquals(2, hints.size());
        assertEquals("A", hints.get(0).getTitle());
        assertEquals("B", hints.get(1).getTitle());
    }

    @Test
    @DisplayName("excludeRecordId 透传给 SQL（审核补分类排除自身）")
    void assemble_passesExcludeRecordId() {
        when(chunkMapper.selectRecentContextHints(eq(USER_ID), any(), any(), eq(50)))
                .thenReturn(List.of());

        invoke(42L);

        verify(chunkMapper).selectRecentContextHints(eq(USER_ID), any(), eq(42L), eq(50));
    }

    @Test
    @DisplayName("装配失败（SQL 异常）降级为空清单，不抛出")
    void assemble_failure_returnsEmpty() {
        when(chunkMapper.selectRecentContextHints(eq(USER_ID), any(), any(), eq(50)))
                .thenThrow(new RuntimeException("db down"));

        List<RecordProcessorProto.RecentHint> hints = invoke(null);

        assertNotNull(hints);
        assertTrue(hints.isEmpty());
    }
}
