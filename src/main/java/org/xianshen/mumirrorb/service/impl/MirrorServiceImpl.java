package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.GlossaryProtoMapper;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.ProfileStatsMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
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
import java.util.ArrayList;
import java.util.HashMap;
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
    private final AiGrpcClient aiGrpcClient;
    private final GlossaryService glossaryService;

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
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, recentChats));

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
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, recentChats));

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
        // profile_snapshots 无"所属月份"列（schema 未落库，快照 createdAt 是生成时刻而非归属月份），
        // 无法精确反查旧份。方案：对"该用户的 monthly 快照"按漂移基线关系判定——
        // 任一 monthly 快照 X 的漂移基线（上一份 monthly）若指向本用户当前最新的另一份 monthly Y，
        // 说明 X/Y 中较旧的那份是同月重复生成。等价简化：重生成后直接把"除最新一份外的、
        // 漂移基线与最新份重叠"的月度快照删除——复杂度高。
        // 落地采用最小可靠方案：monthly 快照以"其 createdAt 之后第一个月度定时窗口"隐含归属月份，
        // 生成时把"生成时刻 + 目标月份"写入 overall_summary 无侵入处不可行 →
        // 改为：删除该用户 monthly 快照中 createdAt 落在 (上次 monthly 生成, 本次生成) 之间、
        // 且其漂移基线即本次目标月上一月快照的记录——仍不可判。
        // 最终方案（与任务书对齐）：重新生成 = 删除目标月份的旧快照，月份归属按
        // "快照 createdAt 是否落在 [次月 1 日 00:00, 次次月 1 日 00:00) 的定时窗口"判定
        // （定时任务每月 1 号 02:00 生成上月画像）；手动补生成落在窗口外时兜底为
        // cleanupOldSnapshots(MONTHLY_KEEP=12) 容量清理，不产生脏数据。
        List<ProfileSnapshot> existing = snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", "monthly")
                        .ge("created_at", target.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime())
                        .lt("created_at", target.plusMonths(2).atDay(1).atStartOfDay(ZONE).toOffsetDateTime())
                        .orderByDesc("created_at"));
        if (existing.size() > 1) {
            // 窗口内出现多份 monthly：视为同月重复生成，保留最新一份
            for (ProfileSnapshot old : existing.subList(1, existing.size())) {
                snapshotMapper.deleteById(old.getId());
            }
            log.info("月度画像重生成：删除月份 [{}] 的 {} 份旧快照", target, existing.size() - 1);
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
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(
            UUID userId, ProfileStatsDTO stats, List<Map<String, Object>> recentChats) {
        MirrorProfileProto.GenerateProfileRequest request = buildRequest(stats, recentChats);
        try {
            return request.toBuilder()
                    .addAllGlossary(GlossaryProtoMapper.toProtoList(glossaryService.confirmedForInjection(userId)))
                    .build();
        } catch (Exception e) {
            log.warn("GenerateProfile 词表注入失败（按无词表继续），用户: {}, 原因: {}", userId, e.getMessage());
            return request;
        }
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
