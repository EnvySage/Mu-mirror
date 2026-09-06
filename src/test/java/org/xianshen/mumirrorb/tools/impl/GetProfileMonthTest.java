package org.xianshen.mumirrorb.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.junit.jupiter.api.BeforeAll;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * get_profile month 参数 + get_coverage 语料收口单测（fix-batch B4 / B2）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetProfileMonthTest {

    @Mock
    private ProfileSnapshotMapper snapshotMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private ChunkMapper chunkMapper;

    @InjectMocks
    private GetProfileTool getProfile;
    @InjectMocks
    private GetCoverageTool getCoverage;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeAll
    static void initLambdaCache() {
        // MP LambdaQueryWrapper 对实体 lambda 列的解析依赖 TableInfo 缓存；
        // 纯 Mockito 环境没有 MyBatis-Plus 启动过程，这里手动注册 Record/Chunk
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, org.xianshen.mumirrorb.pojo.DO.Record.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                org.xianshen.mumirrorb.pojo.DO.Chunk.class);
    }

    @BeforeEach
    void setUp() {
        doReturn(List.of()).when(recordMapper).selectList(any());
        doReturn(List.of()).when(chunkMapper).selectList(any());
    }

    // ==================== B4：get_profile month ====================

    @Test
    @DisplayName("无 month 参数：维持原语义（manual 优先 fallback monthly）")
    void noMonth_latestSemantics() {
        when(snapshotMapper.selectLatest(USER_ID, "manual")).thenReturn(
                ProfileSnapshot.builder().id(4L).snapshotType("manual").overallSummary("最新").build());
        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of());
        assertEquals("get_profile:manual快照", r.getSummary());
        verify(snapshotMapper, org.mockito.Mockito.never())
                .selectLatestInMonth(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("month=2026-08：monthly 按 period_month 精确归属（selectLatestInMonth）")
    void month_periodMonthLookup() {
        when(snapshotMapper.selectLatestInMonth(eq(USER_ID), eq("manual"), eq("2026-08"),
                any(), any())).thenReturn(null);
        when(snapshotMapper.selectLatestInMonth(eq(USER_ID), eq("monthly"), eq("2026-08"),
                any(), any())).thenReturn(
                ProfileSnapshot.builder().id(9L).snapshotType("monthly").periodMonth("2026-08")
                        .overallSummary("八月总结").build());

        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of("month", "2026-08"));

        assertEquals("get_profile:monthly快照", r.getSummary());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals("2026-08", payload.get("month"));
        assertEquals("八月总结", payload.get("overall_summary"));
    }

    @Test
    @DisplayName("month 有 manual 快照在该月：manual 优先（窗口近似）")
    void month_manualFirst() {
        when(snapshotMapper.selectLatestInMonth(eq(USER_ID), eq("manual"), eq("2026-08"),
                any(), any())).thenReturn(
                ProfileSnapshot.builder().id(5L).snapshotType("manual").overallSummary("手动").build());

        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of("month", "2026-08"));
        assertEquals("get_profile:manual快照", r.getSummary());
        // monthly 查询不应再发生（manual 已命中）
        verify(snapshotMapper, org.mockito.Mockito.never()).selectLatestInMonth(
                eq(USER_ID), eq("monthly"), any(), any(), any());
    }

    @Test
    @DisplayName("month 该月无快照：available=false + 指引文案")
    void month_empty() {
        when(snapshotMapper.selectLatestInMonth(any(), any(), any(), any(), any())).thenReturn(null);
        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of("month", "2026-01"));
        assertEquals("get_profile:2026-01 无快照", r.getSummary());
        assertEquals(false, ((Map<?, ?>) r.getPayload()).get("available"));
    }

    @Test
    @DisplayName("month 格式非法：success=false 可读报错")
    void month_badFormat() {
        ToolExecutionResult r = getProfile.execute(USER_ID, Map.of("month", "八月"));
        assertFalse(r.isSuccess());
        assertEquals("get_profile:month 格式非法", r.getSummary());
    }

    // ==================== B2：get_coverage 语料收口 ====================

    @Test
    @DisplayName("get_coverage：只统计 user+done 记录的 chunks（record 白名单预过滤先发生）")
    void coverage_recordWhitelist() {
        doReturn(List.of(Record.builder().id(1L).build(), Record.builder().id(2L).build()))
                .when(recordMapper).selectList(any());
        doReturn(List.of(
                org.xianshen.mumirrorb.pojo.DO.Chunk.builder().id(10L).recordId(1L)
                        .segment("论文进展顺利").content("论文进展顺利")
                        .createdAt(OffsetDateTime.now(ZONE)).build()))
                .when(chunkMapper).selectList(any());

        ToolExecutionResult r = getCoverage.execute(USER_ID, Map.of("query", "论文"));

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getPayload();
        assertEquals(true, payload.get("covered"));
        assertEquals(1, payload.get("mention_days"));
    }

    @Test
    @DisplayName("get_coverage：无 user 记录 → 直接无覆盖（不扫 chunk）")
    void coverage_noUserRecords() {
        doReturn(List.of()).when(recordMapper).selectList(any());
        ToolExecutionResult r = getCoverage.execute(USER_ID, Map.of("query", "论文"));
        assertEquals(false, ((Map<?, ?>) r.getPayload()).get("covered"));
        verify(chunkMapper, org.mockito.Mockito.never()).selectList(any());
    }
}
