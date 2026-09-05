package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;
import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.pojo.VO.MirrorStatsVO;
import org.xianshen.mumirrorb.pojo.VO.SnapshotListVO;
import org.xianshen.mumirrorb.service.impl.MirrorServiceImpl;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 镜子统计端点 Service 层单测（GET /api/mirror/stats）
 *
 * 覆盖：
 * - days clamp（Controller 语义在 Service 入参假设已 clamp，此处验证边界透传）
 * - hourDist 补零 0-23 全 24 格
 * - weekdayDist DOW→周一=0 转换 + 补零 0-6
 * - moodDaily/recordDaily 按日期序列补零
 * - todo 状态计数聚合 + openItems 截 10 条
 */
@ExtendWith(MockitoExtension.class)
class MirrorStatsTest {

    @Mock
    private ProfileSnapshotMapper snapshotMapper;

    @Mock
    private ProfileStatsMapper statsMapper;

    @Mock
    private org.xianshen.mumirrorb.mapper.SettingsMapper settingsMapper;

    @Mock
    private org.xianshen.mumirrorb.grpc.AiGrpcClient aiGrpcClient;

    @InjectMocks
    private MirrorServiceImpl mirrorService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 打桩：所有统计 SQL 返回空（默认全零窗口） */
    private void stubEmpty() {
        lenient().when(statsMapper.selectMoodDaily(eq(USER_ID), any())).thenReturn(List.of());
        lenient().when(statsMapper.selectRecordDaily(eq(USER_ID), any())).thenReturn(List.of());
        lenient().when(statsMapper.selectHourDistribution(eq(USER_ID), any())).thenReturn(List.of());
        lenient().when(statsMapper.selectWeekdayDistribution(eq(USER_ID), any())).thenReturn(List.of());
        lenient().when(statsMapper.selectKeywordStats(eq(USER_ID), any(), eq(10))).thenReturn(List.of());
        lenient().when(statsMapper.selectTodoStatusCounts(USER_ID)).thenReturn(List.of());
        lenient().when(statsMapper.selectOpenTodos(USER_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("空数据：窗口每天补零，hourDist 24 格 / weekdayDist 7 格全零")
    void stats_emptyData_zeroFilled() {
        stubEmpty();
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        assertEquals(30, vo.getDays());
        assertEquals(30, vo.getMoodDaily().size());
        assertEquals(30, vo.getRecordDaily().size());
        assertTrue(vo.getMoodDaily().stream().allMatch(d -> d.getMoods().isEmpty()));
        assertTrue(vo.getRecordDaily().stream().allMatch(d -> d.getCount() == 0));
        assertEquals(24, vo.getHourDist().size());
        assertTrue(vo.getHourDist().stream().allMatch(b -> b.getCount() == 0));
        assertEquals(7, vo.getWeekdayDist().size());
        assertTrue(vo.getWeekdayDist().stream().allMatch(b -> b.getCount() == 0));
        assertTrue(vo.getKeywordTop().isEmpty());
        assertEquals(0, vo.getTodo().getTotal());
        assertTrue(vo.getTodo().getOpenItems().isEmpty());
    }

    @Test
    @DisplayName("日期窗口：末位是今天，首位是 29 天前，升序连续")
    void stats_dateWindow_endsWithToday() {
        stubEmpty();
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        List<MirrorStatsVO.DayCountVO> recordDaily = vo.getRecordDaily();
        LocalDate today = LocalDate.now(ZONE);
        assertEquals(today.toString(), recordDaily.get(recordDaily.size() - 1).getDate());
        assertEquals(today.minusDays(29).toString(), recordDaily.get(0).getDate());
        // 相邻两天连续
        for (int i = 1; i < recordDaily.size(); i++) {
            assertEquals(LocalDate.parse(recordDaily.get(i - 1).getDate()).plusDays(1),
                    LocalDate.parse(recordDaily.get(i).getDate()));
        }
    }

    @Test
    @DisplayName("weekday 转换：DOW 周日=0 → 前端协议周一=0（DOW 1 → bucket 0，DOW 0 → bucket 6）")
    void stats_weekday_dowToMondayZero() {
        stubEmpty();
        // DOW: 周一=1 count 5；周日=0 count 2；周六=6 count 3
        when(statsMapper.selectWeekdayDistribution(eq(USER_ID), any())).thenReturn(List.of(
                row("bucket", 1, "count", 5L),
                row("bucket", 0, "count", 2L),
                row("bucket", 6, "count", 3L)
        ));
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        List<MirrorStatsVO.BucketCountVO> wd = vo.getWeekdayDist();
        assertEquals(7, wd.size());
        assertEquals(5, wd.get(0).getCount()); // 周一
        assertEquals(0, wd.get(1).getCount());
        assertEquals(0, wd.get(2).getCount());
        assertEquals(0, wd.get(3).getCount());
        assertEquals(0, wd.get(4).getCount());
        assertEquals(3, wd.get(5).getCount()); // 周六
        assertEquals(2, wd.get(6).getCount()); // 周日
        // 总数守恒
        assertEquals(10, wd.stream().mapToInt(MirrorStatsVO.BucketCountVO::getCount).sum());
    }

    @Test
    @DisplayName("hourDist 补零：SQL 只回 3 个桶时仍出全 24 格，命中桶计数正确")
    void stats_hourDist_zeroFilled24() {
        stubEmpty();
        when(statsMapper.selectHourDistribution(eq(USER_ID), any())).thenReturn(List.of(
                row("bucket", 2, "count", 65L),
                row("bucket", 4, "count", 2L),
                row("bucket", 23, "count", 1L)
        ));
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        List<MirrorStatsVO.BucketCountVO> hd = vo.getHourDist();
        assertEquals(24, hd.size());
        assertEquals(65, hd.get(2).getCount());
        assertEquals(2, hd.get(4).getCount());
        assertEquals(1, hd.get(23).getCount());
        assertEquals(0, hd.get(0).getCount());
        assertEquals(68, hd.stream().mapToInt(MirrorStatsVO.BucketCountVO::getCount).sum());
    }

    @Test
    @DisplayName("moodDaily：SQL 有数据天进 moods，无数据天 moods 空数组，同日多情绪保留")
    void stats_moodDaily_byDate() {
        stubEmpty();
        LocalDate today = LocalDate.now(ZONE);
        String todayStr = today.toString();
        String yesterdayStr = today.minusDays(1).toString();
        when(statsMapper.selectMoodDaily(eq(USER_ID), any())).thenReturn(List.of(
                row("date", todayStr, "mood", "satisfied", "count", 3L),
                row("date", todayStr, "mood", "anxious", "count", 1L),
                row("date", yesterdayStr, "mood", "calm", "count", 2L)
        ));
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        MirrorStatsVO.MoodDayVO todayVo = vo.getMoodDaily().get(29);
        assertEquals(todayStr, todayVo.getDate());
        assertEquals(2, todayVo.getMoods().size());
        MirrorStatsVO.MoodDayVO yesterdayVo = vo.getMoodDaily().get(28);
        assertEquals(1, yesterdayVo.getMoods().size());
        assertEquals("calm", yesterdayVo.getMoods().get(0).getMood());
        // 其余天空数组
        assertEquals(0, vo.getMoodDaily().get(0).getMoods().size());
    }

    @Test
    @DisplayName("recordDaily：有记录天计数，无记录天补 0")
    void stats_recordDaily_byDate() {
        stubEmpty();
        LocalDate today = LocalDate.now(ZONE);
        when(statsMapper.selectRecordDaily(eq(USER_ID), any())).thenReturn(List.of(
                row("date", today.minusDays(1).toString(), "count", 4L),
                row("date", today.toString(), "count", 2L)
        ));
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        assertEquals(4, vo.getRecordDaily().get(28).getCount());
        assertEquals(2, vo.getRecordDaily().get(29).getCount());
        assertEquals(0, vo.getRecordDaily().get(0).getCount());
    }

    @Test
    @DisplayName("todo：状态计数聚合（未知状态兜底归 not_started）+ openItems 截 10 条")
    void stats_todo_countsAndLimit() {
        stubEmpty();
        when(statsMapper.selectTodoStatusCounts(USER_ID)).thenReturn(List.of(
                row("status", "not_started", "count", 2L),
                row("status", "in_progress", "count", 1L),
                row("status", "completed", "count", 3L)
        ));
        List<ProfileStatsDTO.TodoItemDTO> openTodos = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            openTodos.add(ProfileStatsDTO.TodoItemDTO.builder()
                    .recordId((long) i)
                    .title("待办" + i)
                    .summary("摘要" + i)
                    .taskStatus("not_started")
                    .createdAt("2026-09-01T10:00:00")
                    .build());
        }
        when(statsMapper.selectOpenTodos(USER_ID)).thenReturn(openTodos);

        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        assertEquals(6, vo.getTodo().getTotal());
        assertEquals(2, vo.getTodo().getNotStarted());
        assertEquals(1, vo.getTodo().getInProgress());
        assertEquals(3, vo.getTodo().getCompleted());
        assertEquals(10, vo.getTodo().getOpenItems().size());
        assertEquals("待办1", vo.getTodo().getOpenItems().get(0).getTitle());
        assertEquals("not_started", vo.getTodo().getOpenItems().get(0).getTaskStatus());
    }

    @Test
    @DisplayName("keywordTop：SQL limit 10，透传 keyword/count")
    void stats_keywordTop() {
        stubEmpty();
        when(statsMapper.selectKeywordStats(eq(USER_ID), any(), eq(10))).thenReturn(List.of(
                row("keyword", "Three.js", "count", 12L),
                row("keyword", "Spring Boot", "count", 8L)
        ));
        MirrorStatsVO vo = mirrorService.stats(USER_ID, 30);

        assertEquals(2, vo.getKeywordTop().size());
        assertEquals("Three.js", vo.getKeywordTop().get(0).getKeyword());
        assertEquals(12, vo.getKeywordTop().get(0).getCount());
    }

    @Test
    @DisplayName("since 参数：days=30 时 since = 今天(Asia/Shanghai) 往前 29 天的 0 点")
    void stats_sinceIsTodayMinusDaysPlusOne() {
        stubEmpty();
        mirrorService.stats(USER_ID, 30);

        org.mockito.ArgumentCaptor<OffsetDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.Mockito.verify(statsMapper).selectMoodDaily(eq(USER_ID), captor.capture());

        LocalDate today = LocalDate.now(ZONE);
        OffsetDateTime expected = today.minusDays(29).atStartOfDay(ZONE).toOffsetDateTime();
        assertEquals(expected.toInstant(), captor.getValue().toInstant());
    }

    // ==================== 快照历史（GET /api/mirror/snapshots[/{id}]） ====================

    private ProfileSnapshot snapshot(long id, String type, OffsetDateTime createdAt, String summary) {
        return ProfileSnapshot.builder()
                .id(id)
                .userId(USER_ID)
                .snapshotType(type)
                .overallSummary(summary)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("快照列表：manual+monthly 合并倒序透传，monthly 算漂移、manual 漂移为 null")
    void snapshots_list_mergedOrderAndDrift() {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        when(snapshotMapper.selectAllByUser(USER_ID)).thenReturn(List.of(
                snapshot(5, "manual", now, "最近的手动画像总结"),
                snapshot(4, "monthly", now.minusDays(1), "月度画像总结"),
                snapshot(3, "manual", now.minusDays(2), null)
        ));
        when(snapshotMapper.selectDriftDistance(4L)).thenReturn(0.12);

        List<SnapshotListVO> list = mirrorService.listSnapshots(USER_ID);

        assertEquals(3, list.size());
        assertEquals(5L, list.get(0).getId());
        assertEquals("manual", list.get(0).getSnapshotType());
        assertEquals(now.toInstant(), list.get(0).getCreatedAt().toInstant());
        assertEquals("最近的手动画像总结", list.get(0).getOverallSummary());
        assertNull(list.get(0).getDriftDistance());

        assertEquals(4L, list.get(1).getId());
        assertEquals("monthly", list.get(1).getSnapshotType());
        assertEquals(0.12, list.get(1).getDriftDistance());

        assertNull(list.get(2).getOverallSummary()); // null summary 不截断不拼 ...
        // manual 快照不触发漂移计算
        org.mockito.Mockito.verify(snapshotMapper, org.mockito.Mockito.never()).selectDriftDistance(5L);
        org.mockito.Mockito.verify(snapshotMapper, org.mockito.Mockito.never()).selectDriftDistance(3L);
    }

    @Test
    @DisplayName("快照列表：overallSummary 超 50 字截断并追加 ...")
    void snapshots_list_summaryTruncatedTo50() {
        String longSummary = "字".repeat(80);
        when(snapshotMapper.selectAllByUser(USER_ID)).thenReturn(List.of(
                snapshot(1, "manual", OffsetDateTime.now(ZONE), longSummary)
        ));

        SnapshotListVO vo = mirrorService.listSnapshots(USER_ID).get(0);

        assertEquals(53, vo.getOverallSummary().length()); // 50 字 + "..."（3 个字符）
        assertTrue(vo.getOverallSummary().endsWith("..."));
        assertEquals("字".repeat(50) + "...", vo.getOverallSummary());
    }

    @Test
    @DisplayName("快照列表：空数据返回空数组")
    void snapshots_list_empty() {
        when(snapshotMapper.selectAllByUser(USER_ID)).thenReturn(List.of());

        assertTrue(mirrorService.listSnapshots(USER_ID).isEmpty());
    }

    @Test
    @DisplayName("快照详情：存在且属于本人 → 返回完整 VO（含漂移信息）")
    void snapshot_detail_owned() {
        ProfileSnapshot snap = snapshot(4, "monthly",
                OffsetDateTime.now(ZONE), "月度画像");
        snap.setUserTags(List.of("技术学习"));
        when(snapshotMapper.selectById(4L)).thenReturn(snap);
        when(snapshotMapper.selectDriftDistance(4L)).thenReturn(0.34);
        when(snapshotMapper.selectList(any())).thenReturn(List.of()); // 无上一份 monthly

        MirrorProfileVO vo = mirrorService.getSnapshot(4L, USER_ID);

        assertEquals(4L, vo.getId());
        assertEquals("monthly", vo.getSnapshotType());
        assertEquals("月度画像", vo.getOverallSummary());
        assertEquals(List.of("技术学习"), vo.getUserTags());
        assertEquals(0.34, vo.getDriftDistance());
        assertNull(vo.getDriftBaselineAt());
    }

    @Test
    @DisplayName("快照详情：不存在 → RECORD_NOT_FOUND")
    void snapshot_detail_missing() {
        when(snapshotMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mirrorService.getSnapshot(99L, USER_ID));
        assertEquals(4041, ex.getCode());
    }

    @Test
    @DisplayName("快照详情：他人快照 → RECORD_NOT_FOUND（不区分存在性）")
    void snapshot_detail_otherUser() {
        UUID other = UUID.randomUUID();
        when(snapshotMapper.selectById(7L)).thenReturn(ProfileSnapshot.builder()
                .id(7L)
                .userId(other)
                .snapshotType("manual")
                .overallSummary("别人的画像")
                .createdAt(OffsetDateTime.now(ZONE))
                .build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mirrorService.getSnapshot(7L, USER_ID));
        assertEquals(4041, ex.getCode());
    }
}
