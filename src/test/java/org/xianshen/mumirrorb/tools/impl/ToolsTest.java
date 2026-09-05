package org.xianshen.mumirrorb.tools.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 只读工具单测（工具注册表首批 8 只中可单测的 5 只；vault 三只在 VaultServiceTest/VaultService 层覆盖）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolsTest {

    @Mock
    private ChatSearchMapper searchMapper;
    @Mock
    private ProfileStatsMapper statsMapper;
    @Mock
    private ProfileSnapshotMapper snapshotMapper;
    @Mock
    private UserTermMapper termMapper;

    @InjectMocks
    private SearchRecordsTool searchRecords;
    @InjectMocks
    private GetStatsTool getStats;
    @InjectMocks
    private GetProfileTool getProfile;
    @InjectMocks
    private GetGlossaryTool getGlossary;
    @InjectMocks
    private CompareSnapshotsTool compareSnapshots;

    private static final UUID USER_ID = UUID.randomUUID();

    private static RetrievedChunkDTO chunk(long recordId, String title, String content, String date) {
        return RetrievedChunkDTO.builder()
                .recordId(recordId).title(title).content(content).createdAt(date)
                .contentType("learning").score(0.5).build();
    }

    @Test
    @DisplayName("search_records：SQL 结果转文件卡结构，limit≤20，summary 计数")
    void searchRecords_basic() {
        when(searchMapper.searchStructured(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(chunk(1L, "标题", "正文内容一", "2026-09-01 10:00")));

        ToolExecutionResult r = searchRecords.execute(USER_ID, Map.of("days", 7));

        assertTrue(r.isSuccess());
        assertEquals("search_records:1条", r.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals(1, payload.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) payload.get("records");
        assertEquals(1L, records.get(0).get("record_id"));
        assertEquals("标题", records.get(0).get("title"));
    }

    @Test
    @DisplayName("search_records：query 关键词过滤（content 或 title 命中保留）")
    void searchRecords_queryFilter() {
        when(searchMapper.searchStructured(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        chunk(1L, "RAG 学习", "学了RAG检索", "2026-09-01 10:00"),
                        chunk(2L, "健身", "卧推80kg", "2026-09-02 10:00")));

        ToolExecutionResult r = searchRecords.execute(USER_ID, Map.of("query", "RAG"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals(1, payload.get("count"));
    }

    @Test
    @DisplayName("get_stats：记录数/情绪分布/待办剩余聚合")
    void getStats_aggregates() {
        when(statsMapper.countUserRecords(any(), any())).thenReturn(12L);
        when(statsMapper.selectMoodStats(any(), any())).thenReturn(List.of(
                Map.of("mood", "satisfied", "count", 8),
                Map.of("mood", "anxious", "count", 4)));
        when(statsMapper.selectTodoStatusCounts(USER_ID)).thenReturn(List.of(
                Map.of("status", "completed", "count", 2),
                Map.of("status", "not_started", "count", 3)));

        ToolExecutionResult r = getStats.execute(USER_ID, Map.of("days", 30));

        assertEquals("get_stats:记录12条/30天", r.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals(12L, payload.get("record_count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> todos = (Map<String, Object>) payload.get("todos");
        assertEquals(5, todos.get("total"));
        assertEquals(3, todos.get("remaining"));
    }

    @Test
    @DisplayName("get_profile：manual 优先回退 monthly；无快照 available=false")
    void getProfile_fallbackAndEmpty() {
        when(snapshotMapper.selectLatest(USER_ID, "manual"))
                .thenReturn(ProfileSnapshot.builder().id(4L).snapshotType("manual")
                        .overallSummary("总结").moodAnalysis("情绪").build());
        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of());
        assertEquals("get_profile:manual快照", r.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals(true, payload.get("available"));

        when(snapshotMapper.selectLatest(USER_ID, "manual")).thenReturn(null);
        when(snapshotMapper.selectLatest(USER_ID, "monthly")).thenReturn(null);
        ToolExecutionResult empty = getProfile.execute(USER_ID, Map.of());
        assertEquals("get_profile:尚无画像", empty.getSummary());
    }

    @Test
    @DisplayName("get_glossary：指定 term 过滤（含 alias 命中）；未收录 found=false")
    void getGlossary_termFilter() {
        when(termMapper.selectConfirmedTop(USER_ID, 30)).thenReturn(List.of(
                UserTerm.builder().id(1L).userId(USER_ID).term("论文")
                        .aliases(List.of("毕设")).description("毕业设计").build(),
                UserTerm.builder().id(2L).userId(USER_ID).term("游戏")
                        .aliases(List.of()).description("明日方舟").build()));

        ToolExecutionResult r = getGlossary.execute(USER_ID, Map.of("term", "毕设"));
        assertEquals("get_glossary:1个词条", r.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> terms = (List<Map<String, Object>>) payload.get("terms");
        assertEquals("论文", terms.get(0).get("term"));

        ToolExecutionResult miss = getGlossary.execute(USER_ID, Map.of("term", "FGO"));
        assertEquals("get_glossary:未收录「FGO」", miss.getSummary());
        assertEquals(false, ((Map<?, ?>) miss.getPayload()).get("found"));
    }

    @Test
    @DisplayName("compare_snapshots：指定 id 对比（归属校验他人快照回 null 走缺省）")
    void compareSnapshots_ownedOnly() {
        ProfileSnapshot mine = ProfileSnapshot.builder().id(4L).userId(USER_ID)
                .snapshotType("manual").overallSummary("新总结")
                .createdAt(OffsetDateTime.now()).build();
        when(snapshotMapper.selectById(4L)).thenReturn(mine);
        when(snapshotMapper.selectAllByUser(USER_ID)).thenReturn(List.of(mine,
                ProfileSnapshot.builder().id(3L).userId(USER_ID).snapshotType("monthly")
                        .overallSummary("旧总结").createdAt(OffsetDateTime.now().minusMonths(1)).build()));

        ToolExecutionResult r = compareSnapshots.execute(USER_ID, Map.of("a_id", 4, "b_id", 3));
        assertEquals("compare_snapshots:9月 vs 8月", r.getSummary());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> diffs = (List<Map<String, String>>) ((Map<?, ?>) r.getPayload()).get("diffs");
        assertEquals(1, diffs.size());
        assertEquals("overall", diffs.get(0).get("dimension"));
        assertFalse(diffs.get(0).get("newer").isEmpty());
    }

    @Test
    @DisplayName("compare_snapshots：快照不足 available=false")
    void compareSnapshots_insufficient() {
        when(snapshotMapper.selectById(any())).thenReturn(null);
        when(snapshotMapper.selectAllByUser(USER_ID)).thenReturn(List.of());
        ToolExecutionResult r = compareSnapshots.execute(USER_ID, Map.of());
        assertEquals("compare_snapshots:快照不足", r.getSummary());
    }
}
