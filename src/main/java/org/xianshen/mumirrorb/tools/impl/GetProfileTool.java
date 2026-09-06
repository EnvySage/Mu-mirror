package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.tools.ToolDefinition;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolExecutor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * get_profile 工具：快照五维 + 上次快照时间——"我是什么样的人"类问题
 *
 * <p>fix-batch B4（Y5）：新增可选 {@code month}（"YYYY-MM"）参数——有值时取该月最新快照
 * （manual 优先 fallback monthly），支持"我8月什么样/上个月的我"类回溯问题；
 * 无值维持最新快照语义。</p>
 *
 * <p><b>近似口径声明</b>：月度归属——monthly 快照按 period_month 精确归属（递归镜子轮已落地该列，
 * 仅 monthly 有值）；manual 快照无归属月概念，用 created_at 窗口近似（生成时刻落在该月即算）。</p>
 */
@Component
@RequiredArgsConstructor
public class GetProfileTool implements ToolExecutor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ProfileSnapshotMapper snapshotMapper;

    @Override
    public String name() {
        return "get_profile";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name(),
                "获取镜子画像快照：情绪/学习/待办/节奏分析与整体总结。适合\"我是什么样的人/分析一下我\"类问题；"
                        + "也可指定月份回溯（\"我8月什么样\"→ month=\"2026-08\"）。",
                "{\"month\": \"YYYY-MM（可选，缺省=最新快照）\"}");
    }

    @Override
    public ToolExecutionResult execute(UUID userId, Map<String, Object> args) {
        String month = args == null ? null : strOf(args.get("month"));
        YearMonth target = null;
        if (month != null && !month.isBlank()) {
            try {
                target = YearMonth.parse(month.trim(), MONTH_FORMAT);
            } catch (Exception e) {
                return ToolExecutionResult.builder()
                        .success(false)
                        .summary(name() + ":month 格式非法")
                        .payload(Map.of("error", "month 需为 YYYY-MM 格式（如 2026-08）"))
                        .build();
            }
        }
        ProfileSnapshot snapshot = selectSnapshot(userId, target);
        if (snapshot == null) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .summary(name() + (target == null ? ":尚无画像" : ":" + target + " 无快照"))
                    .payload(Map.of("available", false,
                            "message", target == null
                                    ? "用户还没有生成过画像快照"
                                    : target + " 没有画像快照（该月未手动生成，也未落在月度快照窗口内）"))
                    .build();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", true);
        payload.put("snapshot_type", snapshot.getSnapshotType());
        payload.put("created_at", snapshot.getCreatedAt() == null ? null
                : snapshot.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        if (target != null) {
            payload.put("month", target.toString());
        }
        payload.put("overall_summary", snapshot.getOverallSummary());
        payload.put("mood_analysis", snapshot.getMoodAnalysis());
        payload.put("learning_analysis", snapshot.getLearningAnalysis());
        payload.put("todo_analysis", snapshot.getTodoAnalysis());
        payload.put("rhythm_analysis", snapshot.getRhythmAnalysis());
        payload.put("user_tags", snapshot.getUserTags());
        return ToolExecutionResult.builder()
                .success(true)
                .summary(name() + ":" + snapshot.getSnapshotType() + "快照")
                .payload(payload)
                .build();
    }

    /**
     * 快照选择：month 缺省 → 最新（manual 优先 fallback monthly，原语义）；
     * month 有值 → 该月窗口内最新 manual，无则该月 monthly
     *
     * <p>月度归属口径（B4 任务书声明）：monthly 优先 period_month 精确归属（递归镜子轮已落地
     * 该列，仅 monthly 有值）；manual 无归属月列，用 created_at 窗口近似。</p>
     */
    private ProfileSnapshot selectSnapshot(UUID userId, YearMonth target) {
        if (target == null) {
            ProfileSnapshot snapshot = snapshotMapper.selectLatest(userId, "manual");
            return snapshot != null ? snapshot : snapshotMapper.selectLatest(userId, "monthly");
        }
        // manual 近似窗口：快照生成时刻落在该月即算
        OffsetDateTime start = target.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = target.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
        for (String type : new String[]{"manual", "monthly"}) {
            ProfileSnapshot snapshot = snapshotMapper.selectLatestInMonth(userId, type,
                    target.toString(), start, end);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    private static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
