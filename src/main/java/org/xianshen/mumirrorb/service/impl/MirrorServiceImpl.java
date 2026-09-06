package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.GlossaryProtoMapper;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;
import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.pojo.VO.MirrorStatsVO;
import org.xianshen.mumirrorb.pojo.VO.SnapshotListVO;
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
import java.util.List;
import java.util.Locale;
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
    private static final int KEYWORD_TOP = 10;
    /** 待办明细最多条数（前端协议） */
    private static final int TODO_ITEMS_LIMIT = 10;
    /** 快照历史列表条数上限（manual 保 2 + monthly 保 12，分层保留后全量） */
    private static final int SNAPSHOT_HISTORY_LIMIT = 14;
    /** 列表 overallSummary 截断长度（前端协议：前 50 字） */
    private static final int SUMMARY_PREVIEW_LENGTH = 50;

    private final ProfileSnapshotMapper snapshotMapper;
    private final ProfileStatsMapper statsMapper;
    private final SettingsMapper settingsMapper;
    private final ChunkMapper chunkMapper;
    private final AiGrpcClient aiGrpcClient;
    private final GlossaryService glossaryService;
    private final MirrorProperties mirrorProperties;

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

    /**
     * 快照历史列表（manual + monthly 合并全量，时间倒序，上限 14）
     */
    @Override
    @Transactional(readOnly = true)
    public List<SnapshotListVO> listSnapshots(UUID userId) {
        List<ProfileSnapshot> snapshots = snapshotMapper.selectAllByUser(userId);
        List<SnapshotListVO> result = new ArrayList<>(snapshots.size());
        for (ProfileSnapshot snapshot : snapshots) {
            // driftDistance 仅 monthly 快照计算（与 toVO 口径一致），manual 无对比基线为 null
            Double drift = "monthly".equals(snapshot.getSnapshotType())
                    ? snapshotMapper.selectDriftDistance(snapshot.getId())
                    : null;
            result.add(SnapshotListVO.builder()
                    .id(snapshot.getId())
                    .snapshotType(snapshot.getSnapshotType())
                    .createdAt(snapshot.getCreatedAt())
                    .driftDistance(drift)
                    .overallSummary(truncateSummary(snapshot.getOverallSummary()))
                    .build());
        }
        log.debug("快照历史列表：用户 {} 返回 {} 份", userId, result.size());
        return result;
    }

    /**
     * 单份完整快照（归属校验 + 复用 toVO 组装，结构与 GET /api/mirror 一致）
     */
    @Override
    @Transactional(readOnly = true)
    public MirrorProfileVO getSnapshot(Long snapshotId, UUID userId) {
        ProfileSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        // 归属校验：不存在或不属于当前用户一律按 RECORD_NOT_FOUND 处理（不暴露他人快照存在性）
        if (snapshot == null || !snapshot.getUserId().equals(userId)) {
            log.warn("快照查询被拒：ID {}，用户 {}（不存在或非本人）", snapshotId, userId);
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND);
        }
        return toVO(snapshot);
    }

    /**
     * overallSummary 前 50 字截断（超出追加 ...）
     */
    private static String truncateSummary(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() <= SUMMARY_PREVIEW_LENGTH
                ? summary
                : summary.substring(0, SUMMARY_PREVIEW_LENGTH) + "...";
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
        // manual 快照语义为"截至现在的累计画像"：rolling mirror 四块输入同样生效
        // （上期镜子=最新 monthly 或 manual，回看窗口=近 30 天）
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, recentChats, null));

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

    /**
     * 镜子页图表统计（五维底层数据 + 按日聚合，Service 层补零）
     */
    @Override
    @Transactional(readOnly = true)
    public MirrorStatsVO stats(UUID userId, int days) {
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(days - 1L).atStartOfDay(ZONE).toOffsetDateTime();

        // 按日情绪：SQL 只返回有数据的天，按日期序列补零（moods 空数组）
        Map<String, List<MirrorStatsVO.MoodCountVO>> moodsByDate = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectMoodDaily(userId, since, null)) {
            moodsByDate.computeIfAbsent(String.valueOf(row.get("date")), k -> new ArrayList<>())
                    .add(MirrorStatsVO.MoodCountVO.builder()
                            .mood((String) row.get("mood"))
                            .count((int) toLong(row.get("count")))
                            .build());
        }
        List<MirrorStatsVO.MoodDayVO> moodDaily = new ArrayList<>();
        for (LocalDate d : dateWindow(days)) {
            moodDaily.add(MirrorStatsVO.MoodDayVO.builder()
                    .date(d.toString())
                    .moods(moodsByDate.getOrDefault(d.toString(), List.of()))
                    .build());
        }

        // 小时分布补零 0-23
        Map<Integer, Long> hourByBucket = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectHourDistribution(userId, since, null)) {
            hourByBucket.put((int) toLong(row.get("bucket")), toLong(row.get("count")));
        }
        List<MirrorStatsVO.BucketCountVO> hourDist = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hourDist.add(MirrorStatsVO.BucketCountVO.builder()
                    .bucket(h).count(hourByBucket.getOrDefault(h, 0L).intValue()).build());
        }

        // 星期分布补零 0-6，DOW（周日=0）→ 前端协议（周一=0）
        Map<Integer, Long> weekdayByBucket = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectWeekdayDistribution(userId, since, null)) {
            int dow = (int) toLong(row.get("bucket"));
            weekdayByBucket.put((dow + 6) % 7, toLong(row.get("count")));
        }
        List<MirrorStatsVO.BucketCountVO> weekdayDist = new ArrayList<>();
        for (int w = 0; w < 7; w++) {
            weekdayDist.add(MirrorStatsVO.BucketCountVO.builder()
                    .bucket(w).count(weekdayByBucket.getOrDefault(w, 0L).intValue()).build());
        }

        // 关键词 Top 10（与画像统计同口径，取前 10）
        List<MirrorStatsVO.KeywordCountVO> keywordTop = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectKeywordStats(userId, since, null, KEYWORD_TOP)) {
            keywordTop.add(MirrorStatsVO.KeywordCountVO.builder()
                    .keyword((String) row.get("keyword"))
                    .count((int) toLong(row.get("count")))
                    .build());
        }

        // 待办：状态计数（COALESCE 归 not_started）+ 未完成明细截 10 条
        int total = 0;
        int notStarted = 0;
        int inProgress = 0;
        int completed = 0;
        for (Map<String, Object> row : statsMapper.selectTodoStatusCounts(userId)) {
            String status = String.valueOf(row.get("status"));
            int count = (int) toLong(row.get("count"));
            total += count;
            switch (status) {
                case "in_progress" -> inProgress += count;
                case "completed" -> completed += count;
                default -> notStarted += count; // not_started 及未知值兜底
            }
        }
        List<MirrorStatsVO.TodoItemVO> openItems = statsMapper.selectOpenTodos(userId).stream()
                .limit(TODO_ITEMS_LIMIT)
                .map(t -> MirrorStatsVO.TodoItemVO.builder()
                        .recordId(t.getRecordId())
                        .title(t.getTitle())
                        .summary(t.getSummary())
                        .taskStatus(t.getTaskStatus())
                        .build())
                .toList();

        // 按日记录数：SQL 只返回有记录的天，按日期序列补零
        Map<String, Long> recordsByDate = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectRecordDaily(userId, since, null)) {
            recordsByDate.put(String.valueOf(row.get("date")), toLong(row.get("count")));
        }
        List<MirrorStatsVO.DayCountVO> recordDaily = new ArrayList<>();
        for (LocalDate d : dateWindow(days)) {
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
    private List<LocalDate> dateWindow(int days) {
        LocalDate today = LocalDate.now(ZONE);
        List<LocalDate> result = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            result.add(today.minusDays(i));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Double driftDistance(Long snapshotId) {
        return snapshotMapper.selectDriftDistance(snapshotId);
    }

    /**
     * 定时生成 monthly 快照 + 清理（每月 1 号 02:00 Asia/Shanghai，设计文档 6.5）
     *
     * <p>顺路任务（lexicon-design.md 第 3 节）：月度画像后做个人词典维护——
     * ① 词条合并（重复/矛盾词 aliases 合并建议 → pending 复核）
     * ② 漂移审计（confirmed 解释 vs 近 30 天语料，不一致打回 pending + 新解释建议）。
     * 维护失败只打日志，不影响月度画像主流程。</p>
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
            // 顺路：个人词典月度维护（合并建议 + 漂移审计，内部全量 try-catch）
            glossaryService.monthlyMaintenance(userId);
        }
        log.info("============ 月度画像定时任务结束 ============");
    }

    /**
     * monthly 快照生成（与 manual 共用统计/调用逻辑，仅类型与保留策略不同）
     *
     * <p>【行为变化 2026-09-06】定时任务语义从"统计最近 30 天"改为"生成上一个自然月的完整画像"
     * （1 号 02:00 触发时传 {@code YearMonth.now(ZONE).minusMonths(1)}）。此前 8/31 写的日记
     * 要到 9/30 才进入统计窗口，现在月初即完整覆盖上月语料。手动触发指定月份走
     * {@link #generateMonthlyFor(UUID, String)}。</p>
     */
    @Transactional
    public void generateMonthly(UUID userId) {
        YearMonth lastMonth = YearMonth.now(ZONE).minusMonths(1);
        log.info("月度画像（定时）生成上个月 [{}] 的完整画像，用户: {}", lastMonth, userId);
        generateMonthly(userId, lastMonth);
    }

    /**
     * monthly 快照生成（指定月份版本，统计窗口=该自然月）
     *
     * <p>【行为变化 2026-09-06 递归累计镜子】语义从"当月切片"升级为"截至 N 月的累计画像"
     * （rolling-mirror-design.md §0/§1）：生成时携带四块输入——
     * ① 上期镜子全文（period_month = N-1 的 monthly 快照五维+总结拼接，递归链超
     * {@code mirror.mirror-summary-after-months} 个月的更早镜子只带一行压缩摘要）；
     * ② 按 mirror_lookback 档位带原始记录（四道防洪闸限流）；
     * ③ 校正索引（仅 lookback=0 时带，防误差累积）；
     * ④ 待办/统计实况直查（数据库实时值，LLM 只叙事不记账）。
     * 快照落库写 period_month；幂等判断走精确列。</p>
     */
    @Transactional
    public MirrorProfileVO generateMonthly(UUID userId, YearMonth month) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            log.info("用户 {} 未配置 LLM，跳过月度画像", userId);
            return MirrorProfileVO.builder().build();
        }

        ProfileStatsDTO stats = collectStats(userId, month);
        List<Map<String, Object>> recentChats = statsMapper.selectRecentChats(userId, RECENT_CHATS_LIMIT);
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, recentChats, month));

        ProfileSnapshot snapshot = ProfileSnapshot.builder()
                .userId(userId)
                .snapshotType("monthly")
                .periodMonth(month.toString())
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
        log.info("用户 {} 月度画像已生成，月份: [{}]，快照ID: {}", userId, month, snapshot.getId());
        return toVO(snapshot);
    }

    // ==================== 内部方法 ====================

    /**
     * 按月生成 monthly 快照（对外入口，接口 {@code MirrorService#generateMonthlyFor}）
     *
     * <p>幂等：同一 (user, month) 已有 monthly 快照时重新生成则替换（删除该月份的旧快照后插入新份，
     * 月度快照按月份语义唯一，重生成即覆盖）。month 为空=上个月；不允许当前月与未来月份
     * （当前月数据还不完整，用 manual 语义；未来月份没有语料）。</p>
     *
     * @param month "2026-08" 格式；null/空 = 上个月
     */
    @Override
    @Transactional
    public MirrorProfileVO generateMonthlyFor(UUID userId, String month) {
        YearMonth target;
        if (month == null || month.isBlank()) {
            target = YearMonth.now(ZONE).minusMonths(1);
        } else {
            try {
                target = YearMonth.parse(month.trim());
            } catch (Exception e) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "月份格式应为 yyyy-MM，如 2026-08");
            }
        }
        YearMonth current = YearMonth.now(ZONE);
        if (!target.isBefore(current)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只能生成历史月份的月度画像（当前月请用「生成画像」，未来月份无数据）");
        }
        log.info("按月生成月度画像，用户: {}, 月份: [{}]", userId, target);

        // 幂等：同一 (user, month) 的 monthly 快照重生成即替换。
        // 【2026-09-06 递归累计镜子】profile_snapshots 新增 period_month 精确列（生成时写入归属月份），
        // 幂等判断从旧"createdAt 落入定时窗口"近似方案切换为精确列匹配；uq_snapshots_user_period
        // 部分唯一索引兜底（user_id + period_month，仅 monthly 非空行）。
        List<ProfileSnapshot> existing = snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", "monthly")
                        .eq("period_month", target.toString())
                        .orderByDesc("created_at"));
        if (!existing.isEmpty()) {
            for (ProfileSnapshot old : existing) {
                snapshotMapper.deleteById(old.getId());
            }
            log.info("月度画像重生成：删除月份 [{}] 的 {} 份旧快照（period_month 精确匹配）",
                    target, existing.size());
        }

        return generateMonthly(userId, target);
    }

    /**
     * 五维统计（最近 30 天口径）。兼容旧签名：generate() 的 manual 快照语义。
     */
    private ProfileStatsDTO collectStats(UUID userId) {
        return collectStats(userId, null);
    }

    /**
     * 五维统计（指定月份或最近 30 天）组装
     *
     * <p>month 为 null → 最近 30 天（manual 快照语义，timeRange="最近30天"）；
     * month 非空 → 该自然月 [1 号 0 点, 次月 1 号 0 点)（Asia/Shanghai），
     * timeRange="2026年8月" 形式。月度窗口带 until 截断，防止把月后/未来的日记算进去。</p>
     */
    private ProfileStatsDTO collectStats(UUID userId, YearMonth month) {
        final OffsetDateTime since;
        final OffsetDateTime until; // 开区间上界，null=不限
        final String timeRange;
        if (month == null) {
            since = LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toOffsetDateTime();
            until = null;
            timeRange = "最近30天";
        } else {
            since = month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
            until = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
            timeRange = month.getYear() + "年" + month.getMonthValue() + "月";
        }

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
        List<Map<String, Object>> moodRows = statsMapper.selectMoodStats(userId, since, until);
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
        for (Map<String, Object> row : statsMapper.selectKeywordStats(userId, since, until, 20)) {
            keywords.add(ProfileStatsDTO.KeywordStatDTO.builder()
                    .keyword((String) row.get("keyword"))
                    .count((int) toLong(row.get("count")))
                    .build());
        }

        List<ProfileStatsDTO.HourCountDTO> hours = new ArrayList<>();
        int peakBucket = -1;
        int peakCount = 0;
        for (Map<String, Object> row : statsMapper.selectHourDistribution(userId, since, until)) {
            int bucket = (int) toLong(row.get("bucket"));
            int count = (int) toLong(row.get("count"));
            hours.add(ProfileStatsDTO.HourCountDTO.builder().bucket(bucket).count(count).build());
            if (count > peakCount) {
                peakCount = count;
                peakBucket = bucket;
            }
        }

        List<ProfileStatsDTO.HourCountDTO> weekdays = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectWeekdayDistribution(userId, since, until)) {
            weekdays.add(ProfileStatsDTO.HourCountDTO.builder()
                    .bucket((int) toLong(row.get("bucket")))
                    .count((int) toLong(row.get("count")))
                    .build());
        }

        long totalRecords = statsMapper.countUserRecords(userId, since, until);

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

    /**
     * 组装 GenerateProfileRequest（llm_config 由 AiGrpcClient 补齐）+ 个人词典注入（第 4 节 top 30 全量）
     *
     * <p>递归累计镜子（rolling-mirror-design.md §1）：month 非空（monthly 快照）时携带四块输入；
     * month 为 null（manual 快照）时同样按"截至现在的累计画像"组装——
     * 上期镜子取最新 monthly（无则最新 manual），回看窗口取近 30 天。</p>
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(
            UUID userId, ProfileStatsDTO stats, List<Map<String, Object>> recentChats, YearMonth month) {
        MirrorProfileProto.GenerateProfileRequest.Builder request = buildRequest(stats, recentChats).toBuilder();

        RollingContext rolling = collectRollingInputs(userId, month, stats);
        // mirror_lookback 为 proto3 optional（shared-protocol 2026-09-06 修正）：显式 set 区分
        // "未传=AI 侧缺省 1 档"与"显式 0=纯继承档"；B 侧解析后总是显式传（兜底 1 档也 set）
        request.setPrevMirror(rolling.prevMirror())
                .setCorrectionIndex(rolling.correctionIndex())
                .setMirrorLookback(rolling.lookback())
                .setStatsFacts(rolling.statsFacts());

        log.info("递归累计镜子输入组装：lookback={}, prevMirror={} 字符, correctionIndex={} 条, statsFacts={} 字符, lookbackTruncated={}",
                rolling.lookback(), rolling.prevMirror().length(),
                rolling.correctionIndex().isEmpty() ? 0 : rolling.correctionIndex().split("\n").length,
                rolling.statsFacts().length(), rolling.lookbackTruncated());

        MirrorProfileProto.GenerateProfileRequest base = request.build();
        try {
            return base.toBuilder()
                    .addAllGlossary(GlossaryProtoMapper.toProtoList(glossaryService.confirmedForInjection(userId)))
                    .build();
        } catch (Exception e) {
            log.warn("GenerateProfile 词表注入失败（按无词表继续），用户: {}, 原因: {}", userId, e.getMessage());
            return base;
        }
    }

    /**
     * 递归累计镜子四块输入（① prev_mirror / ③ correction_index / ② lookback 档位 / ④ stats_facts）
     *
     * <p>闸门（MirrorProperties，全配置化）：条数闸与总字符闸先触发者生效，旧→新裁剪；
     * 触发时打 WARN 日志（F 侧 meta toast 透出待联调接线）。</p>
     */
    private record RollingContext(String prevMirror, String correctionIndex, int lookback,
                                  String statsFacts, boolean lookbackTruncated) {
    }

    private RollingContext collectRollingInputs(UUID userId, YearMonth month, ProfileStatsDTO stats) {
        // ---- 档位：设置透传（0-3，缺省/越界兜底 1）----
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        int lookback = 1;
        if (settings != null && settings.getMirrorLookback() != null
                && settings.getMirrorLookback() >= 0 && settings.getMirrorLookback() <= 3) {
            lookback = settings.getMirrorLookback();
        }

        // ---- 回看窗口：monthly=按档位回看 N 个自然月；manual=近 30 天 ----
        OffsetDateTime windowSince;
        OffsetDateTime windowUntil;
        if (month != null) {
            // 档位 N → [until - N 个月, until)，until = 目标月次月 1 号 0 点。
            // N=0（纯继承）时窗口退化为空区间 [9/1, 9/1)——原文不带、校正索引也不带
            // （设计稿 §2：0 档"无原文"，防误差靠镜子链本身的继承）。genesis 低档窗口无数据自然为空。
            windowUntil = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
            windowSince = month.plusMonths(1).minusMonths(lookback).atDay(1)
                    .atStartOfDay(ZONE).toOffsetDateTime();
        } else {
            windowUntil = LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
            windowSince = LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toOffsetDateTime();
        }

        // ---- ① 上期镜子全文（递归链）----
        String prevMirror = buildPrevMirror(userId, month);

        // ---- ③ 校正索引：仅 lookback=0 时带（低档用户防误差累积的补偿）----
        // 覆盖上期镜子涉及的记录窗口（上月整月）：0 档不带原文，靠这份 title+日期清单对账
        String correctionIndex = "";
        if (lookback == 0) {
            OffsetDateTime idxSince = month != null
                    ? month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime()
                    : LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toOffsetDateTime();
            OffsetDateTime idxUntil = month != null
                    ? month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime()
                    : LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
            List<Map<String, Object>> rows = chunkMapper.selectCorrectionIndex(userId, idxSince, idxUntil);
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("- ").append(row.get("title")).append("（").append(row.get("record_date")).append("）");
            }
            correctionIndex = sb.toString();
        }

        // ---- ② 本月原始记录（四道防洪闸）----
        boolean truncated = false;
        List<Chunk> chunks = chunkMapper.selectLookbackChunks(userId, windowSince, windowUntil,
                mirrorProperties.getLookbackMaxChunks() + 1);
        if (chunks.size() > mirrorProperties.getLookbackMaxChunks()) {
            // 条数闸：超限取最近的（SQL 升序，砍头部旧记录）
            chunks = chunks.subList(chunks.size() - mirrorProperties.getLookbackMaxChunks(), chunks.size());
            truncated = true;
            log.warn("回看原文触发条数闸（lookback_max_chunks={}），已截取最近部分，用户: {}",
                    mirrorProperties.getLookbackMaxChunks(), userId);
        }
        StringBuilder records = new StringBuilder();
        int perChunkMax = mirrorProperties.getPerChunkMaxChars();
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
        for (Chunk c : chunks) {
            OffsetDateTime at = c.getCreatedAt() == null ? null : c.getCreatedAt().atZoneSameInstant(ZONE).toOffsetDateTime();
            String day = at == null ? "" : at.format(dayFmt);
            String body = c.getSegment() == null || c.getSegment().isBlank() ? c.getContent() : c.getSegment();
            if (body == null) {
                body = "";
            }
            if (body.length() > perChunkMax) {
                body = body.substring(0, perChunkMax) + "…（已截取最近部分）";
            }
            records.append("[").append(day).append("] ").append(body).append('\n');
        }
        // 总字符闸：与条数闸先触发者生效，旧→新裁剪直到塞下（保尾部新记录）
        int maxChars = mirrorProperties.getLookbackMaxChars();
        if (records.length() > maxChars) {
            truncated = true;
            String all = records.toString();
            String tail = all.substring(all.length() - maxChars);
            int nl = tail.indexOf('\n');
            if (nl >= 0 && nl + 1 < tail.length()) {
                tail = tail.substring(nl + 1); // 从下一条完整记录起
            }
            records = new StringBuilder(tail);
            log.warn("回看原文触发总字符闸（lookback_max_chars={}），已按旧→新裁剪到 {} 字符，用户: {}",
                    maxChars, records.length(), userId);
        }

        // ---- ④ 待办/统计实况直查（数据库实时值；LLM 只叙事不记账）----
        StringBuilder facts = new StringBuilder();
        int total = 0;
        int completed = 0;
        int notStarted = 0;
        int inProgress = 0;
        for (Map<String, Object> row : statsMapper.selectTodoStatusCounts(userId)) {
            int count = (int) toLong(row.get("count"));
            total += count;
            switch (String.valueOf(row.get("status"))) {
                case "in_progress" -> inProgress += count;
                case "completed" -> completed += count;
                default -> notStarted += count;
            }
        }
        facts.append("待办实况（数据库实时值，以此为准，不要继承上文镜子的旧说法）：")
                .append("共 ").append(total)
                .append(" 条，已完成 ").append(completed)
                .append("，进行中 ").append(inProgress)
                .append("，未开始 ").append(notStarted).append("。\n");
        List<ProfileStatsDTO.TodoItemDTO> openTodos = statsMapper.selectOpenTodos(userId);
        facts.append("当前未完成待办（").append(openTodos.size()).append(" 条）：\n");
        for (ProfileStatsDTO.TodoItemDTO t : openTodos) {
            facts.append("- [").append(t.getCreatedAt() == null ? "" : t.getCreatedAt()).append("] ")
                    .append(nullToEmpty(t.getTitle()))
                    .append("：").append(nullToEmpty(t.getSummary()))
                    .append("（状态：").append(nullToEmpty(t.getTaskStatus())).append("）\n");
        }
        facts.append("本月记录数：").append(stats.getTotalRecords() == null ? 0 : stats.getTotalRecords())
                .append("（").append(stats.getTimeRange() == null ? "" : stats.getTimeRange()).append("）\n");
        facts.append("情绪分布计数：");
        if (stats.getMoodStats() == null || stats.getMoodStats().isEmpty()) {
            facts.append("（无情绪数据）");
        } else {
            boolean first = true;
            for (ProfileStatsDTO.MoodStatDTO m : stats.getMoodStats()) {
                if (!first) {
                    facts.append("、");
                }
                facts.append(m.getMood()).append(" ").append(m.getCount());
                first = false;
            }
        }

        return new RollingContext(prevMirror, correctionIndex, lookback, facts.toString(), truncated);
    }

    /**
     * ① 上期镜子全文（递归链压缩）：
     * 上一个月（N-1）快照全文（五维+总结拼接）；距目标月超过 mirror_summary_after_months 的
     * 更早镜子只带一行压缩摘要。genesis 月（无任何更早镜子）返回提示文案（AI 侧空块不留孤儿节头）。
     */
    private String buildPrevMirror(UUID userId, YearMonth month) {
        List<ProfileSnapshot> monthly = snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", "monthly")
                        .orderByAsc("period_month"));
        if (monthly.isEmpty()) {
            // 无 monthly 时回退最新 manual（首月 genesis 的常见形态：只有手动画像）
            ProfileSnapshot latestManual = snapshotMapper.selectLatest(userId, "manual");
            return latestManual == null ? "" : fullMirrorText(latestManual);
        }

        String targetKey = month == null ? LocalDate.now(ZONE).toString().substring(0, 7) : month.toString();
        // 归属月份严格小于目标月的最近一份 = 上期镜子
        ProfileSnapshot prev = null;
        for (int i = monthly.size() - 1; i >= 0; i--) {
            ProfileSnapshot s = monthly.get(i);
            if (s.getPeriodMonth() != null && s.getPeriodMonth().compareTo(targetKey) < 0) {
                prev = s;
                break;
            }
            // period_month 为 NULL 的历史行按 createdAt 推断归属（补值前的兜底）
            if (s.getPeriodMonth() == null) {
                String inferred = inferPeriodMonth(s);
                if (inferred != null && inferred.compareTo(targetKey) < 0) {
                    prev = s;
                    break;
                }
            }
        }
        if (prev == null) {
            return ""; // genesis：无上月镜子（AI 侧 {prev_mirror} 空块不留孤儿节头）
        }

        StringBuilder sb = new StringBuilder();
        sb.append(fullMirrorText(prev));
        // 递归链：更早的镜子只带一行压缩摘要（超 mirror_summary_after_months 个月的也不省略摘要行——
        // 设计稿 §1 "超 12 个月的更早镜子只带一行压缩摘要"，即链上全部更早镜子都只保留一行）
        int summaryMonths = mirrorProperties.getMirrorSummaryAfterMonths();
        for (int i = monthly.size() - 1; i >= 0; i--) {
            ProfileSnapshot s = monthly.get(i);
            if (s.getId().equals(prev.getId())) {
                break;
            }
            String key = s.getPeriodMonth() != null ? s.getPeriodMonth() : inferPeriodMonth(s);
            if (key == null) {
                continue;
            }
            long monthsAgo = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.parse(key),
                    month == null ? YearMonth.now(ZONE) : month);
            if (monthsAgo <= 0) {
                continue;
            }
            String line = oneLineSummary(key, s);
            // 压缩行仍受"超过 N 个月才压缩"约束（N 以内更早镜子已有全文链则跳过重复——
            // 设计稿语义：N-1 全文，其余一行；这里统一为一行摘要，链路清晰）
            if (monthsAgo > summaryMonths) {
                line = "【" + key + "】" + line;
            }
            sb.insert(0, line + '\n');
        }
        return sb.toString().trim();
    }

    /**
     * 快照五维+总结拼接（① 上期镜子全文的标准拼法）
     */
    private static String fullMirrorText(ProfileSnapshot s) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "情绪", s.getMoodAnalysis());
        appendSection(sb, "学习", s.getLearningAnalysis());
        appendSection(sb, "待办", s.getTodoAnalysis());
        appendSection(sb, "节奏", s.getRhythmAnalysis());
        if (s.getOverallSummary() != null && !s.getOverallSummary().isBlank()) {
            sb.append("总体总结：").append(s.getOverallSummary());
        }
        return sb.toString().trim();
    }

    private static void appendSection(StringBuilder sb, String label, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(label).append("：").append(text).append('\n');
        }
    }

    /**
     * 更早镜子的一行压缩摘要（【月份】总结前 60 字）
     */
    private static String oneLineSummary(String key, ProfileSnapshot s) {
        String summary = s.getOverallSummary() == null ? "" : s.getOverallSummary();
        if (summary.length() > 60) {
            summary = summary.substring(0, 60) + "…";
        }
        return "【" + key + " 镜子摘要】" + summary;
    }

    /**
     * 历史 NULL period_month 行按 createdAt 推断归属月份（定时语义：次月生成上月画像）
     */
    private static String inferPeriodMonth(ProfileSnapshot s) {
        if (s.getCreatedAt() == null) {
            return null;
        }
        return s.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate().minusMonths(1).toString().substring(0, 7);
    }

    /**
     * 组装 GenerateProfileRequest 基础块（五维统计 + recent_chats；llm_config 由 AiGrpcClient 补齐）
     *
     * <p>④ 待办统计实况不在 proto 结构化字段里重复——设计稿 §1 "④用现有统计字段，不加 proto"，
     * 另以 stats_facts（field 14，B 提案）携带渲染好的实况文本。</p>
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(
            ProfileStatsDTO stats, List<Map<String, Object>> recentChats) {

        MirrorProfileProto.GenerateProfileRequest.Builder builder =
                MirrorProfileProto.GenerateProfileRequest.newBuilder()
                        .setTotalRecords(stats.getTotalRecords() == null ? 0 : stats.getTotalRecords())
                        .setTimeRange(stats.getTimeRange() == null ? "" : stats.getTimeRange());

        // 个人词典注入（lexicon-design.md 第 4 节）：GenerateProfile 传 confirmed top 30 全量（软约束）
        // userId 在 generate/generateMonthly 调用链上无法直接传入（stats 不含），经 ThreadLocal 上下文省略——
        // 改为在 generate()/generateMonthly() 组装后补齐（见 buildRequest(userId, stats, recentChats) 重载）
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
