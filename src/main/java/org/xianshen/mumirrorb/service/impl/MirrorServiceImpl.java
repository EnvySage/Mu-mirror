package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;
import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.service.MirrorService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 镜子画像服务实现（设计文档 6.5）
 *
 * <p>生成流程：Java SQL 统计五维数据（未完成待办/最近学习/情绪分布/关键词/活跃时段）
 * + 最近会话 → gRPC GenerateProfile → 存 profile_snapshots → 五维文本固定顺序拼接 → Embed → 向量存快照。</p>
 * <p>分层保留：manual 保最近 2 份，monthly 保 12 份；每月定时生成后执行清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MirrorServiceImpl implements MirrorService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MANUAL_KEEP = 2;
    private static final int MONTHLY_KEEP = 12;
    private static final int RECENT_CHATS_LIMIT = 20;

    private final ProfileSnapshotMapper snapshotMapper;
    private final ProfileStatsMapper statsMapper;
    private final SettingsMapper settingsMapper;
    private final AiGrpcClient aiGrpcClient;

    @Override
    @Transactional(readOnly = true)
    public MirrorProfileVO getMirror(UUID userId) {
        // 最新 manual，无则最新 monthly（前端"查看镜子"默认展示最近一次画像）
        ProfileSnapshot snapshot = snapshotMapper.selectLatest(userId, "manual");
        if (snapshot == null) {
            snapshot = snapshotMapper.selectLatest(userId, "monthly");
        }
        if (snapshot == null) {
            return MirrorProfileVO.builder().build(); // 从未生成：空 VO，前端引导生成
        }
        return toVO(snapshot);
    }

    @Override
    @Transactional
    public MirrorProfileVO generate(UUID userId) {
        log.info("============ 画像生成开始，用户: {} ============", userId);

        // 0. 校验用户已配置 LLM（否则 Python 端无法调用）
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先在设置中配置 AI 模型");
        }

        // 1. 五维统计（最近 30 天口径）+ recent_chats
        ProfileStatsDTO stats = collectStats(userId);
        List<Map<String, Object>> recentChats = statsMapper.selectRecentChats(userId, RECENT_CHATS_LIMIT);

        // 2. gRPC GenerateProfile（AiGrpcClient 内部补 llm_config）
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(stats, recentChats));

        // 3. 存快照
        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("manual")
                .moodAnalysis(response.getMoodAnalysis())
                .learningAnalysis(response.getLearningAnalysis())
                .todoAnalysis(response.getTodoAnalysis())
                .rhythmAnalysis(response.getRhythmAnalysis())
                .userTags(response.getUserTagsList())
                .overallSummary(response.getOverallSummary())
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        snapshotMapper.insert(snapshot);
        log.info("画像快照已保存，ID: {}, 类型: manual", snapshot.getId());

        // 4. 五维文本固定顺序拼接 → Embed → 回写向量（失败不阻断：向量缺失仅影响漂移检测）
        try {
            String text = snapshot.joinedAnalysisText();
            if (!text.isBlank()) {
                var embedResponse = aiGrpcClient.embed(userId, text);
                snapshot.setEmbedding(embedResponse.getVectorList());
                snapshotMapper.updateById(snapshot);
            }
        } catch (Exception e) {
            log.warn("画像向量生成失败（不阻断），快照ID: {}，原因: {}", snapshot.getId(), e.getMessage());
        }

        // 5. 分层保留清理（manual 保 2 份）
        cleanupOldSnapshots(userId, "manual", MANUAL_KEEP);

        log.info("============ 画像生成结束，快照ID: {} ============", snapshot.getId());
        return toVO(snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Double driftDistance(Long snapshotId) {
        return snapshotMapper.selectDriftDistance(snapshotId);
    }

    /**
     * 定时生成 monthly 快照 + 清理（每月 1 号 02:00 Asia/Shanghai，设计文档 6.5）
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 2 1 * ?", zone = "Asia/Shanghai")
    public void monthlySnapshot() {
        log.info("============ 月度画像定时任务开始 ============");
        // 注册用户逐个生成（单实例小规模，用户量 ≤ 6，串行即可）
        List<UserSettings> all = settingsMapper.selectList(new QueryWrapper<>());
        for (UserSettings settings : all) {
            UUID userId = settings.getUserId();
            try {
                generateMonthly(userId);
            } catch (Exception e) {
                // 单用户失败不影响其他用户
                log.error("用户 {} 月度画像生成失败", userId, e);
            }
        }
        log.info("============ 月度画像定时任务结束 ============");
    }

    /**
     * monthly 快照生成（与 manual 共用统计/调用逻辑，仅类型与保留策略不同）
     */
    @Transactional
    public void generateMonthly(UUID userId) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            log.info("用户 {} 未配置 LLM，跳过月度画像", userId);
            return;
        }

        ProfileStatsDTO stats = collectStats(userId);
        List<Map<String, Object>> recentChats = statsMapper.selectRecentChats(userId, RECENT_CHATS_LIMIT);
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(stats, recentChats));

        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .moodAnalysis(response.getMoodAnalysis())
                .learningAnalysis(response.getLearningAnalysis())
                .todoAnalysis(response.getTodoAnalysis())
                .rhythmAnalysis(response.getRhythmAnalysis())
                .userTags(response.getUserTagsList())
                .overallSummary(response.getOverallSummary())
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        snapshotMapper.insert(snapshot);

        try {
            String text = snapshot.joinedAnalysisText();
            if (!text.isBlank()) {
                var embedResponse = aiGrpcClient.embed(userId, text);
                snapshot.setEmbedding(embedResponse.getVectorList());
                snapshotMapper.updateById(snapshot);
            }
        } catch (Exception e) {
            log.warn("月度画像向量生成失败，快照ID: {}，原因: {}", snapshot.getId(), e.getMessage());
        }

        cleanupOldSnapshots(userId, "monthly", MONTHLY_KEEP);
        log.info("用户 {} 月度画像已生成，快照ID: {}", userId, snapshot.getId());
    }

    // ==================== 内部方法 ====================

    /**
     * 五维统计（最近 30 天）组装
     */
    private ProfileStatsDTO collectStats(UUID userId) {
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toOffsetDateTime();

        List<ProfileStatsDTO.TodoItemDTO> todos = statsMapper.selectOpenTodos(userId);
        List<ProfileStatsDTO.LearningItemDTO> learnings = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectRecentLearningsRaw(userId)) {
            learnings.add(ProfileStatsDTO.LearningItemDTO.builder()
                    .recordId(toLong(row.get("recordId")))
                    .title((String) row.get("title"))
                    .summary((String) row.get("summary"))
                    .keywords(parseStringList(row.get("keywordsJson")))
                    .createdAt((String) row.get("createdAt"))
                    .build());
        }

        List<ProfileStatsDTO.MoodStatDTO> moodStats = new ArrayList<>();
        List<Map<String, Object>> moodRows = statsMapper.selectMoodStats(userId, since);
        long moodTotal = moodRows.stream().mapToLong(r -> toLong(r.get("count"))).sum();
        for (Map<String, Object> row : moodRows) {
            long count = toLong(row.get("count"));
            moodStats.add(ProfileStatsDTO.MoodStatDTO.builder()
                    .mood((String) row.get("mood"))
                    .count((int) count)
                    .percentage(moodTotal > 0 ? (float) (count * 100.0 / moodTotal) : 0f)
                    .build());
        }

        List<ProfileStatsDTO.KeywordStatDTO> keywords = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectKeywordStats(userId, since, 20)) {
            keywords.add(ProfileStatsDTO.KeywordStatDTO.builder()
                    .keyword((String) row.get("keyword"))
                    .count((int) toLong(row.get("count")))
                    .build());
        }

        List<ProfileStatsDTO.HourCountDTO> hours = new ArrayList<>();
        int peakBucket = -1;
        int peakCount = 0;
        for (Map<String, Object> row : statsMapper.selectHourDistribution(userId, since)) {
            int bucket = (int) toLong(row.get("bucket"));
            int count = (int) toLong(row.get("count"));
            hours.add(ProfileStatsDTO.HourCountDTO.builder().bucket(bucket).count(count).build());
            if (count > peakCount) {
                peakCount = count;
                peakBucket = bucket;
            }
        }

        List<ProfileStatsDTO.HourCountDTO> weekdays = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectWeekdayDistribution(userId, since)) {
            weekdays.add(ProfileStatsDTO.HourCountDTO.builder()
                    .bucket((int) toLong(row.get("bucket")))
                    .count((int) toLong(row.get("count")))
                    .build());
        }

        long totalRecords = statsMapper.countUserRecords(userId, since);

        return ProfileStatsDTO.builder()
                .todos(todos)
                .learnings(learnings)
                .moodStats(moodStats)
                .keywords(keywords)
                .hourDistribution(hours)
                .weekdayDistribution(weekdays)
                .peakHour(peakBucket >= 0 ? peakBucket + "点" : "暂无数据")
                .totalRecords((int) totalRecords)
                .timeRange("最近30天")
                .build();
    }

    /**
     * 组装 GenerateProfileRequest（llm_config 由 AiGrpcClient 补齐）
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(
            ProfileStatsDTO stats, List<Map<String, Object>> recentChats) {

        MirrorProfileProto.GenerateProfileRequest.Builder builder =
                MirrorProfileProto.GenerateProfileRequest.newBuilder()
                        .setTotalRecords(stats.getTotalRecords() == null ? 0 : stats.getTotalRecords())
                        .setTimeRange(stats.getTimeRange() == null ? "" : stats.getTimeRange());

        for (ProfileStatsDTO.TodoItemDTO t : stats.getTodos()) {
            builder.addTodos(MirrorProfileProto.TodoItem.newBuilder()
                    .setRecordId(t.getRecordId() == null ? 0 : t.getRecordId())
                    .setTitle(nullToEmpty(t.getTitle()))
                    .setSummary(nullToEmpty(t.getSummary()))
                    .setCreatedAt(nullToEmpty(t.getCreatedAt())));
        }
        for (ProfileStatsDTO.LearningItemDTO l : stats.getLearnings()) {
            MirrorProfileProto.LearningItem.Builder lb = MirrorProfileProto.LearningItem.newBuilder()
                    .setRecordId(l.getRecordId() == null ? 0 : l.getRecordId())
                    .setTitle(nullToEmpty(l.getTitle()))
                    .setSummary(nullToEmpty(l.getSummary()))
                    .setCreatedAt(nullToEmpty(l.getCreatedAt()));
            if (l.getKeywords() != null) {
                lb.addAllKeywords(l.getKeywords());
            }
            builder.addLearnings(lb);
        }
        for (ProfileStatsDTO.MoodStatDTO m : stats.getMoodStats()) {
            builder.addMoodStats(MirrorProfileProto.MoodStat.newBuilder()
                    .setMood(nullToEmpty(m.getMood()))
                    .setCount(m.getCount() == null ? 0 : m.getCount())
                    .setPercentage(m.getPercentage() == null ? 0f : m.getPercentage()));
        }
        for (ProfileStatsDTO.KeywordStatDTO k : stats.getKeywords()) {
            builder.addKeywords(MirrorProfileProto.KeywordStat.newBuilder()
                    .setKeyword(nullToEmpty(k.getKeyword()))
                    .setCount(k.getCount() == null ? 0 : k.getCount()));
        }

        MirrorProfileProto.ActiveTimeStats.Builder active = MirrorProfileProto.ActiveTimeStats.newBuilder()
                .setPeakHour(nullToEmpty(stats.getPeakHour()));
        if (stats.getHourDistribution() != null) {
            for (ProfileStatsDTO.HourCountDTO h : stats.getHourDistribution()) {
                active.putHourDistribution(String.valueOf(h.getBucket()), h.getCount());
            }
        }
        if (stats.getWeekdayDistribution() != null) {
            for (ProfileStatsDTO.HourCountDTO w : stats.getWeekdayDistribution()) {
                active.putWeekdayDistribution(String.valueOf(w.getBucket()), w.getCount());
            }
        }
        builder.setActiveTime(active);

        // recent_chats：优先近 7 天，不足则前补（SQL 已按时间倒序取最近 N 条，天然满足优先近 7 天）
        for (Map<String, Object> row : recentChats) {
            builder.addRecentChats(MirrorProfileProto.ChatRecord.newBuilder()
                    .setRole(nullToEmpty((String) row.get("role")))
                    .setContent(nullToEmpty((String) row.get("content")))
                    .setCreatedAt(nullToEmpty((String) row.get("createdAt"))));
        }
        return builder.build();
    }

    /**
     * 分层保留清理：指定类型只保留最近 keep 份（画像用于对比，不堆数量，6.5）
     */
    private void cleanupOldSnapshots(UUID userId, String type, int keep) {
        List<ProfileSnapshot> snapshots = snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", type)
                        .orderByDesc("created_at"));
        if (snapshots.size() <= keep) {
            return;
        }
        List<Long> toDelete = snapshots.subList(keep, snapshots.size())
                .stream().map(ProfileSnapshot::getId).toList();
        for (Long id : toDelete) {
            snapshotMapper.deleteById(id);
        }
        log.info("分层保留清理：类型 {}，删除 {} 份旧快照（用户 {}）", type, toDelete.size(), userId);
    }

    private MirrorProfileVO toVO(ProfileSnapshot snapshot) {
        Double drift = null;
        OffsetDateTime baselineAt = null;
        if ("monthly".equals(snapshot.getSnapshotType())) {
            drift = snapshotMapper.selectDriftDistance(snapshot.getId());
        }
        ProfileSnapshot prevMonthly = snapshotMapper.selectList(
                        new QueryWrapper<ProfileSnapshot>()
                                .eq("user_id", snapshot.getUserId())
                                .eq("snapshot_type", "monthly")
                                .lt("created_at", snapshot.getCreatedAt())
                                .orderByDesc("created_at")
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (prevMonthly != null) {
            baselineAt = prevMonthly.getCreatedAt();
        }

        return MirrorProfileVO.builder()
                .id(snapshot.getId())
                .snapshotType(snapshot.getSnapshotType())
                .moodAnalysis(snapshot.getMoodAnalysis())
                .learningAnalysis(snapshot.getLearningAnalysis())
                .todoAnalysis(snapshot.getTodoAnalysis())
                .rhythmAnalysis(snapshot.getRhythmAnalysis())
                .userTags(snapshot.getUserTags())
                .overallSummary(snapshot.getOverallSummary())
                .driftDistance(drift)
                .driftBaselineAt(baselineAt)
                .createdAt(snapshot.getCreatedAt())
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object json) {
        if (json == null) return List.of();
        try {
            if (json instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json.toString(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
