package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.GlossaryProtoMapper;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.SnapshotInputMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;
import org.xianshen.mumirrorb.pojo.DTO.SnapshotSourceRef;
import org.xianshen.mumirrorb.pojo.DO.SnapshotInput;
import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.pojo.VO.MirrorStatsVO;
import org.xianshen.mumirrorb.pojo.VO.SnapshotListVO;
import org.xianshen.mumirrorb.pojo.VO.SnapshotPageVO;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.xianshen.mumirrorb.service.MirrorService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.xianshen.mumirrorb.service.impl.MirrorServiceImpl.*;

/** MirrorStatistics keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorStatistics {
    private final MirrorServiceImpl owner;
    MirrorStatistics(MirrorServiceImpl owner) { this.owner=owner; }


    /**
     * 镜子页图表统计（五维底层数据 + 按日聚合，Service 层补零）
     */

    @Transactional(readOnly = true)
    public MirrorStatsVO stats(UUID userId, int days) {
        OffsetDateTime since = LocalDate.now(owner.ZONE).minusDays(days - 1L).atStartOfDay(owner.ZONE).toOffsetDateTime();

        // 按日情绪：SQL 只返回有数据的天，按日期序列补零（moods 空数组）
        Map<String, List<MirrorStatsVO.MoodCountVO>> moodsByDate = new HashMap<>();
        for (Map<String, Object> row : owner.statsMapper.selectMoodDaily(userId, since, null)) {
            moodsByDate.computeIfAbsent(String.valueOf(row.get("date")), k -> new ArrayList<>())
                    .add(MirrorStatsVO.MoodCountVO.builder()
                            .mood((String) row.get("mood"))
                            .count((int) owner.toLong(row.get("count")))
                            .build());
        }
        List<MirrorStatsVO.MoodDayVO> moodDaily = new ArrayList<>();
        for (LocalDate d : owner.dateWindow(days)) {
            moodDaily.add(MirrorStatsVO.MoodDayVO.builder()
                    .date(d.toString())
                    .moods(moodsByDate.getOrDefault(d.toString(), List.of()))
                    .build());
        }

        // 小时分布补零 0-23
        Map<Integer, Long> hourByBucket = new HashMap<>();
        for (Map<String, Object> row : owner.statsMapper.selectHourDistribution(userId, since, null)) {
            hourByBucket.put((int) owner.toLong(row.get("bucket")), owner.toLong(row.get("count")));
        }
        List<MirrorStatsVO.BucketCountVO> hourDist = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hourDist.add(MirrorStatsVO.BucketCountVO.builder()
                    .bucket(h).count(hourByBucket.getOrDefault(h, 0L).intValue()).build());
        }

        // 星期分布补零 0-6，DOW（周日=0）→ 前端协议（周一=0）
        Map<Integer, Long> weekdayByBucket = new HashMap<>();
        for (Map<String, Object> row : owner.statsMapper.selectWeekdayDistribution(userId, since, null)) {
            int dow = (int) owner.toLong(row.get("bucket"));
            weekdayByBucket.put((dow + 6) % 7, owner.toLong(row.get("count")));
        }
        List<MirrorStatsVO.BucketCountVO> weekdayDist = new ArrayList<>();
        for (int w = 0; w < 7; w++) {
            weekdayDist.add(MirrorStatsVO.BucketCountVO.builder()
                    .bucket(w).count(weekdayByBucket.getOrDefault(w, 0L).intValue()).build());
        }

        // 关键词 Top 10（与画像统计同口径，取前 10）
        List<MirrorStatsVO.KeywordCountVO> keywordTop = new ArrayList<>();
        for (Map<String, Object> row : owner.statsMapper.selectKeywordStats(userId, since, null, owner.KEYWORD_TOP)) {
            keywordTop.add(MirrorStatsVO.KeywordCountVO.builder()
                    .keyword((String) row.get("keyword"))
                    .count((int) owner.toLong(row.get("count")))
                    .build());
        }

        // 待办：状态计数（registry 口径，见 todo-status-removal-design.md §11）+ 未完成明细截 10 条
        int total = 0;
        int notStarted = 0;
        int inProgress = 0;
        int completed = 0;
        for (Map<String, Object> row : owner.statsMapper.selectTodoStatusCounts(userId)) {
            String status = String.valueOf(row.get("status"));
            int count = (int) owner.toLong(row.get("count"));
            total += count;
            switch (status) {
                case "in_progress" -> inProgress += count;
                case "completed" -> completed += count;
                default -> notStarted += count; // not_started 及未知值兜底
            }
        }
        List<MirrorStatsVO.TodoItemVO> openItems = owner.statsMapper.selectOpenTodos(userId).stream()
                .limit(owner.TODO_ITEMS_LIMIT)
                .map(t -> MirrorStatsVO.TodoItemVO.builder()
                        .recordId(t.getRecordId())
                        .title(t.getTitle())
                        .summary(t.getSummary())
                        .taskStatus(t.getTaskStatus())
                        .build())
                .toList();

        // 按日记录数：SQL 只返回有记录的天，按日期序列补零
        Map<String, Long> recordsByDate = new HashMap<>();
        for (Map<String, Object> row : owner.statsMapper.selectRecordDaily(userId, since, null)) {
            recordsByDate.put(String.valueOf(row.get("date")), owner.toLong(row.get("count")));
        }
        List<MirrorStatsVO.DayCountVO> recordDaily = new ArrayList<>();
        for (LocalDate d : owner.dateWindow(days)) {
            recordDaily.add(MirrorStatsVO.DayCountVO.builder()
                    .date(d.toString())
                    .count(recordsByDate.getOrDefault(d.toString(), 0L).intValue())
                    .build());
        }

        return MirrorStatsVO.builder()
                .days(days)
                .moodDaily(moodDaily)
                .hourDist(hourDist)
                .weekdayDist(weekdayDist)
                .keywordTop(keywordTop)
                .todo(MirrorStatsVO.TodoStatsVO.builder()
                        .total(total)
                        .completed(completed)
                        .notStarted(notStarted)
                        .inProgress(inProgress)
                        .openItems(openItems)
                        .build())
                .recordDaily(recordDaily)
                .build();
    }



    /**
     * 统计窗口日期序列（今天往前 days 天，Asia/Shanghai，升序）
     */
    protected List<LocalDate> dateWindow(int days) {
        LocalDate today = LocalDate.now(owner.ZONE);
        List<LocalDate> result = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            result.add(today.minusDays(i));
        }
        return result;
    }



    /**
     * 五维统计（最近 30 天口径）。兼容旧签名：generate() 的 manual 快照语义。
     */
    protected ProfileStatsDTO collectStats(UUID userId) {
        return owner.collectStats(userId, null);
    }



    /**
     * 五维统计（指定月份或最近 30 天）组装
     *
     * <p>month 为 null → 最近 30 天（manual 快照语义，timeRange="最近30天"）；
     * month 非空 → 该自然月 [1 号 0 点, 次月 1 号 0 点)（Asia/Shanghai），
     * timeRange="2026年8月" 形式。月度窗口带 until 截断，防止把月后/未来的日记算进去。</p>
     */
    protected ProfileStatsDTO collectStats(UUID userId, YearMonth month) {
        StatsWindow window = owner.statsWindow(month);
        OffsetDateTime since = window.since();
        OffsetDateTime until = window.until();
        String timeRange = window.label();

        List<ProfileStatsDTO.TodoItemDTO> todos = owner.statsMapper.selectOpenTodos(userId);
        List<ProfileStatsDTO.LearningItemDTO> learnings = new ArrayList<>();
        for (Map<String, Object> row : owner.statsMapper.selectRecentLearningsRaw(userId)) {
            learnings.add(ProfileStatsDTO.LearningItemDTO.builder()
                    .recordId(owner.toLong(row.get("recordId")))
                    .title((String) row.get("title"))
                    .summary((String) row.get("summary"))
                    .keywords(owner.parseStringList(row.get("keywordsJson")))
                    .createdAt((String) row.get("createdAt"))
                    .build());
        }

        List<ProfileStatsDTO.MoodStatDTO> moodStats = new ArrayList<>();
        List<Map<String, Object>> moodRows = owner.statsMapper.selectMoodStats(userId, since, until);
        long moodTotal = moodRows.stream().mapToLong(r -> owner.toLong(r.get("count"))).sum();
        for (Map<String, Object> row : moodRows) {
            long count = owner.toLong(row.get("count"));
            moodStats.add(ProfileStatsDTO.MoodStatDTO.builder()
                    .mood((String) row.get("mood"))
                    .count((int) count)
                    .percentage(moodTotal > 0 ? (float) (count * 100.0 / moodTotal) : 0f)
                    .build());
        }

        List<ProfileStatsDTO.KeywordStatDTO> keywords = new ArrayList<>();
        for (Map<String, Object> row : owner.statsMapper.selectKeywordStats(userId, since, until, 20)) {
            keywords.add(ProfileStatsDTO.KeywordStatDTO.builder()
                    .keyword((String) row.get("keyword"))
                    .count((int) owner.toLong(row.get("count")))
                    .build());
        }

        List<ProfileStatsDTO.HourCountDTO> hours = new ArrayList<>();
        int peakBucket = -1;
        int peakCount = 0;
        for (Map<String, Object> row : owner.statsMapper.selectHourDistribution(userId, since, until)) {
            int bucket = (int) owner.toLong(row.get("bucket"));
            int count = (int) owner.toLong(row.get("count"));
            hours.add(ProfileStatsDTO.HourCountDTO.builder().bucket(bucket).count(count).build());
            if (count > peakCount) {
                peakCount = count;
                peakBucket = bucket;
            }
        }

        List<ProfileStatsDTO.HourCountDTO> weekdays = new ArrayList<>();
        for (Map<String, Object> row : owner.statsMapper.selectWeekdayDistribution(userId, since, until)) {
            weekdays.add(ProfileStatsDTO.HourCountDTO.builder()
                    .bucket((int) owner.toLong(row.get("bucket")))
                    .count((int) owner.toLong(row.get("count")))
                    .build());
        }

        long totalRecords = owner.statsMapper.countUserRecords(userId, since, until);

        return ProfileStatsDTO.builder()
                .todos(todos)
                .learnings(learnings)
                .moodStats(moodStats)
                .keywords(keywords)
                .hourDistribution(hours)
                .weekdayDistribution(weekdays)
                .peakHour(peakBucket >= 0 ? peakBucket + "点" : "暂无数据")
                .totalRecords((int) totalRecords)
                .timeRange(timeRange)
                .build();
    }



    protected StatsWindow statsWindow(YearMonth month) {
        if (month == null) {
            return new StatsWindow(LocalDate.now(owner.ZONE).minusDays(30).atStartOfDay(owner.ZONE).toOffsetDateTime(),
                    null, "最近30天");
        }
        return new StatsWindow(month.atDay(1).atStartOfDay(owner.ZONE).toOffsetDateTime(),
                month.plusMonths(1).atDay(1).atStartOfDay(owner.ZONE).toOffsetDateTime(),
                month.getYear() + "年" + month.getMonthValue() + "月");
    }



    protected List<SnapshotSourceRef> selectStatsSourceRefs(UUID userId, YearMonth month) {
        StatsWindow window = owner.statsWindow(month);
        List<SnapshotSourceRef> refs = owner.statsMapper.selectStatsSourceRefs(userId, window.since(), window.until());
        return refs == null ? List.of() : refs;
    }
}
