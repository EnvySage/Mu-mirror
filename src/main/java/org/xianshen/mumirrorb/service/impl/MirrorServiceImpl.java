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

/**
 * 镜子画像服务实现（设计文档 6.5）
 *
 * <p>生成流程：Java SQL 统计五维数据（未完成待办/最近学习/情绪分布/关键词/活跃时段）
 * + 最近会话 → gRPC GenerateProfile → 存 profile_snapshots → 五维文本固定顺序拼接 → Embed → 向量存快照。</p>
 * <p>镜子作为长期归档保存；月度重生成以新版本替代当前版，输入来源与父镜子关系随版本保存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MirrorServiceImpl implements MirrorService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int KEYWORD_TOP = 10;
    /** 待办明细最多条数（前端协议） */
    private static final int TODO_ITEMS_LIMIT = 10;
    /** 列表 overallSummary 截断长度（前端协议：前 50 字） */
    private static final int SUMMARY_PREVIEW_LENGTH = 50;

    private final ProfileSnapshotMapper snapshotMapper;
    private final SnapshotInputMapper snapshotInputMapper;
    private final ProfileStatsMapper statsMapper;
    private final SettingsMapper settingsMapper;
    private final ChunkMapper chunkMapper;
    private final AiGrpcClient aiGrpcClient;
    private final GlossaryService glossaryService;
    private final MirrorProperties mirrorProperties;
    private final TransactionTemplate transactionTemplate;
    private final SnapshotLineageService snapshotLineageService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.xianshen.mumirrorb.service.AssistantContextService assistantContextService;

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

    /** Keyset-paginated archive; invalidated snapshots remain visible. */
    @Override
    @Transactional(readOnly = true)
    public SnapshotPageVO listSnapshots(UUID userId, OffsetDateTime beforeCreatedAt, Long beforeId, int limit) {
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分页游标时间和快照 ID 必须同时提供");
        }
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "每页数量必须在 1 到 100 之间");
        }
        List<ProfileSnapshot> fetched = snapshotMapper.selectSnapshotPage(userId, beforeCreatedAt, beforeId,
                limit + 1);
        if (fetched == null) fetched = List.of();
        boolean hasMore = fetched.size() > limit;
        List<ProfileSnapshot> snapshots = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, Integer> deletedSourceCounts = selectDeletedSourceCounts(snapshots);
        List<SnapshotListVO> items = snapshots.stream()
                .map(snapshot -> toSnapshotListVO(snapshot,
                        deletedSourceCounts.getOrDefault(snapshot.getId(), 0)))
                .toList();
        SnapshotListVO last = hasMore && !items.isEmpty() ? items.get(items.size() - 1) : null;
        SnapshotPageVO page = SnapshotPageVO.builder()
                .items(items)
                .hasMore(hasMore)
                .nextCreatedAt(last == null ? null : last.getCreatedAt())
                .nextId(last == null ? null : last.getId())
                .build();
        log.debug("快照归档分页：用户 {} 返回 {} 份，hasMore={}", userId, items.size(), hasMore);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> snapshotMonths(UUID userId) {
        YearMonth currentMonth = YearMonth.now(ZONE);
        Set<YearMonth> candidates = new TreeSet<>();
        List<String> recordMonths = statsMapper.selectAuthorizedRecordMonths(userId);
        List<String> snapshotMonths = snapshotMapper.selectMonthlyPeriodMonths(userId);
        addValidMonths(candidates, recordMonths, currentMonth);
        addValidMonths(candidates, snapshotMonths, currentMonth);

        return candidates.stream().map(YearMonth::toString).toList();
    }

    private static void addValidMonths(Set<YearMonth> target, List<String> values, YearMonth currentMonth) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            try {
                YearMonth month = YearMonth.parse(value);
                if (month.isBefore(currentMonth)) target.add(month);
            } catch (java.time.format.DateTimeParseException ignored) {
                log.warn("忽略格式错误的镜子归属月份: {}", value);
            }
        }
    }

    private Map<Long, Integer> selectDeletedSourceCounts(List<ProfileSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return Map.of();
        List<Long> ids = snapshots.stream().map(ProfileSnapshot::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = snapshotInputMapper.selectDeletedSourceCounts(ids);
        if (rows == null || rows.isEmpty()) return Map.of();
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long snapshotId = toNullableLong(row.get("snapshotId"));
            if (snapshotId != null) result.put(snapshotId, (int) toLong(row.get("deletedSourceCount")));
        }
        return result;
    }

    private SnapshotListVO toSnapshotListVO(ProfileSnapshot snapshot, int deletedSourceCount) {
        boolean current = snapshot.getIsCurrent() == null || snapshot.getIsCurrent();
        boolean invalidated = Boolean.TRUE.equals(snapshot.getInvalidated());
        String contextStatus = invalidated || ("monthly".equals(snapshot.getSnapshotType()) && !current)
                ? "archive_only" : "usable";
        Double drift = "monthly".equals(snapshot.getSnapshotType()) && "usable".equals(contextStatus)
                ? snapshotMapper.selectDriftDistance(snapshot.getId()) : null;
        return SnapshotListVO.builder()
                .id(snapshot.getId())
                .snapshotType(snapshot.getSnapshotType())
                .periodMonth(snapshot.getPeriodMonth())
                .versionNo(snapshot.getVersionNo())
                .isCurrent(current)
                .contextStatus(contextStatus)
                .invalidatedAt(snapshot.getInvalidatedAt())
                .invalidatedReason(snapshot.getInvalidatedReason())
                .deletedSourceCount(deletedSourceCount)
                .archiveDiscontinuity(Boolean.TRUE.equals(snapshot.getArchiveDiscontinuity()))
                .createdAt(snapshot.getCreatedAt())
                .driftDistance(drift)
                .overallSummary(truncateSummary(snapshot.getOverallSummary()))
                .build();
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
        snapshotLineageService.lockUserLineage(userId);

        // 0. 校验用户已配置 LLM（否则 Python 端无法调用）
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先在设置中配置 AI 模型");
        }

        // 生成手动镜子前，先补齐已有失效月度链；没有失效链时不额外生成月度镜子。
        replayInvalidatedMonthlyChainThrough(userId, YearMonth.now(ZONE).minusMonths(1));

        // 1. 五维统计（最近 30 天口径）；聊天语料只通过可信记忆上下文授权注入。
        ProfileStatsDTO stats = collectStats(userId);
        List<SnapshotSourceRef> sourceRefs = new ArrayList<>(selectStatsSourceRefs(userId, null));

        // 2. gRPC GenerateProfile（AiGrpcClient 内部补 llm_config）
        // manual 快照语义为"截至现在的累计画像"：rolling mirror 四块输入同样生效
        // （上期镜子=最新 monthly 或 manual，回看窗口=近 30 天）
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, null, sourceRefs));
        boolean archiveDiscontinuity = hasDiscontinuousParent(sourceRefs);

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
                .versionNo(1)
                .isCurrent(true)
                .lineageTracked(true)
                .archiveDiscontinuity(archiveDiscontinuity)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        snapshotMapper.insert(snapshot);
        saveInputs(snapshot, sourceRefs);
        recordChatMemoryInputs(snapshot, sourceRefs);
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

        // 待办：状态计数（registry 口径，见 todo-status-removal-design.md §11）+ 未完成明细截 10 条
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
     * monthly 快照生成（与 manual 共用统计与调用逻辑，保留每次生成的归档版本）
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
        // monthlySnapshot 调用本类方法时会绕过 Spring 的方法代理；显式事务确保重放链
        // 任一月份失败时，前面已写入的新版本和依赖行一起回滚。
        transactionTemplate.execute(status -> {
            snapshotLineageService.lockUserLineage(userId);
            generateMonthlyThrough(userId, lastMonth);
            return null;
        });
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
     * 快照落库写 period_month；重复生成递增版本并保留旧归档。</p>
     */
    @Transactional
    public MirrorProfileVO generateMonthly(UUID userId, YearMonth month) {
        snapshotLineageService.lockUserLineage(userId);
        return generateMonthlyVersion(userId, month, false);
    }

    private MirrorProfileVO generateMonthlyVersion(UUID userId, YearMonth month, boolean replayDiscontinuity) {
        UserSettings settings = settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            log.info("用户 {} 未配置 LLM，跳过月度画像", userId);
            return MirrorProfileVO.builder().build();
        }

        ProfileStatsDTO stats = collectStats(userId, month);
        List<SnapshotSourceRef> sourceRefs = new ArrayList<>(selectStatsSourceRefs(userId, month));
        ProfileSnapshot previousCurrent = snapshotMapper.selectCurrentMonthlyForUpdate(userId, month.toString());
        int versionNo = snapshotMapper.selectMaxVersionNo(userId, month.toString()) + 1;
        MirrorProfileProto.GenerateProfileResponse response =
                aiGrpcClient.generateProfile(userId, buildRequest(userId, stats, month, sourceRefs));
        boolean archiveDiscontinuity = replayDiscontinuity || hasDiscontinuousParent(sourceRefs);

        if (previousCurrent != null) {
            previousCurrent.setIsCurrent(false);
            snapshotMapper.updateById(previousCurrent);
        }

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
                .versionNo(versionNo)
                .isCurrent(true)
                .lineageTracked(true)
                .archiveDiscontinuity(archiveDiscontinuity)
                .invalidated(false)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        snapshotMapper.insert(snapshot);
        saveInputs(snapshot, sourceRefs);
        recordChatMemoryInputs(snapshot, sourceRefs);

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

        log.info("用户 {} 月度镜子版本已生成，月份: [{}]，版本: {}，快照ID: {}",
                userId, month, versionNo, snapshot.getId());
        return toVO(snapshot);
    }

    // ==================== 内部方法 ====================

    /**
     * 按月生成 monthly 快照（对外入口，接口 {@code MirrorService#generateMonthlyFor}）
     *
     * <p>同一 (user, month) 重新生成会递增版本并保留旧版本；如该月之前存在失效的当前月度链，
     * 则先从最早失效月份懒重放到目标月。month 为空=上个月；不允许当前月与未来月份
     * （当前月数据还不完整，用 manual 语义；未来月份没有语料）。</p>
     *
     * @param month "2026-08" 格式；null/空 = 上个月
     */
    @Override
    @Transactional
    public MirrorProfileVO generateMonthlyFor(UUID userId, String month) {
        snapshotLineageService.lockUserLineage(userId);
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

        return generateMonthlyThrough(userId, target);
    }

    /**
     * 为指定历史月份生成镜子；若更早存在失效的当前月度版本，则从最早失效月份懒重放到目标月。
     */
    @Transactional
    public MirrorProfileVO generateMonthlyThrough(UUID userId, YearMonth target) {
        snapshotLineageService.lockUserLineage(userId);
        List<String> invalidatedPeriods = snapshotMapper.selectInvalidatedCurrentMonthlyPeriods(
                userId, target.toString());
        if (invalidatedPeriods == null || invalidatedPeriods.isEmpty()) {
            return generateMonthlyVersion(userId, target, false);
        }

        YearMonth earliestInvalidated = invalidatedPeriods.stream()
                .map(YearMonth::parse)
                .min(YearMonth::compareTo)
                .orElse(target);
        return replayMonthlyChainThrough(userId, target, earliestInvalidated);
    }

    /** Manual generation replays only when an existing monthly chain is stale. */
    private void replayInvalidatedMonthlyChainThrough(UUID userId, YearMonth target) {
        List<String> invalidatedPeriods = snapshotMapper.selectInvalidatedCurrentMonthlyPeriods(
                userId, target.toString());
        if (invalidatedPeriods == null || invalidatedPeriods.isEmpty()) return;

        YearMonth earliestInvalidated = invalidatedPeriods.stream()
                .map(YearMonth::parse)
                .min(YearMonth::compareTo)
                .orElse(target);
        replayMonthlyChainThrough(userId, target, earliestInvalidated);
    }

    /** Rebuild every authorized input month after the latest safe monthly checkpoint. */
    private MirrorProfileVO replayMonthlyChainThrough(UUID userId, YearMonth target, YearMonth earliestInvalidated) {
        String checkpointValue = snapshotMapper.selectLatestUsableMonthlyPeriodBefore(
                userId, earliestInvalidated.toString());
        YearMonth checkpoint = checkpointValue == null ? null : YearMonth.parse(checkpointValue);
        boolean hasSafeCheckpoint = checkpoint != null;
        YearMonth replayStart;
        boolean archiveDiscontinuity = false;
        if (hasSafeCheckpoint) {
            replayStart = checkpoint.plusMonths(1);
        } else {
            List<String> authorizedMonthValues = statsMapper.selectAuthorizedRecordMonths(userId);
            List<YearMonth> authorizedMonths = (authorizedMonthValues == null ? List.<String>of() : authorizedMonthValues).stream()
                    .map(YearMonth::parse)
                    .filter(month -> !month.isAfter(target))
                    .toList();
            replayStart = authorizedMonths.stream().min(YearMonth::compareTo).orElse(earliestInvalidated);
            String earliestArchivedValue = snapshotMapper.selectEarliestArchivedMonthlyPeriod(userId);
            if (earliestArchivedValue == null || authorizedMonths.isEmpty()) {
                archiveDiscontinuity = true;
            } else {
                archiveDiscontinuity = replayStart.isAfter(YearMonth.parse(earliestArchivedValue));
            }
        }

        Set<YearMonth> periods = new TreeSet<>();
        List<String> existingPeriods = snapshotMapper.selectCurrentMonthlyPeriods(
                userId, replayStart.toString(), target.toString());
        if (existingPeriods != null) existingPeriods.stream().map(YearMonth::parse).forEach(periods::add);
        List<String> authorizedMonths = statsMapper.selectAuthorizedRecordMonths(userId);
        if (authorizedMonths != null) {
            authorizedMonths.stream().map(YearMonth::parse)
                    .filter(month -> !month.isBefore(replayStart) && !month.isAfter(target))
                    .forEach(periods::add);
        }
        periods.add(target);

        MirrorProfileVO result = MirrorProfileVO.builder().build();
        for (YearMonth period : periods) {
            result = generateMonthlyVersion(userId, period, archiveDiscontinuity);
        }
        if (result.getId() != null && replayStart.isAfter(earliestInvalidated)) {
            // These invalidated versions precede the first authorized month and cannot be rebuilt.
            // Keep them as archives, but remove their stale "current" marker so future generations
            // start from the new discontinuous baseline instead of replaying the same gap forever.
            snapshotMapper.retireUnreconstructableInvalidatedCurrentMonthlyVersions(
                    userId, earliestInvalidated.toString(), replayStart.toString());
        }
        return result;
    }

    private boolean hasDiscontinuousParent(List<SnapshotSourceRef> sourceRefs) {
        if (sourceRefs == null) return false;
        for (SnapshotSourceRef ref : sourceRefs) {
            if (ref != null && "snapshot".equals(ref.getInputKind()) && ref.getInputId() != null) {
                ProfileSnapshot parent = snapshotMapper.selectById(ref.getInputId());
                if (parent != null && Boolean.TRUE.equals(parent.getArchiveDiscontinuity())) return true;
            }
        }
        return false;
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
        StatsWindow window = statsWindow(month);
        OffsetDateTime since = window.since();
        OffsetDateTime until = window.until();
        String timeRange = window.label();

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

    private record StatsWindow(OffsetDateTime since, OffsetDateTime until, String label) { }

    private StatsWindow statsWindow(YearMonth month) {
        if (month == null) {
            return new StatsWindow(LocalDate.now(ZONE).minusDays(30).atStartOfDay(ZONE).toOffsetDateTime(),
                    null, "最近30天");
        }
        return new StatsWindow(month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime(),
                month.getYear() + "年" + month.getMonthValue() + "月");
    }

    private List<SnapshotSourceRef> selectStatsSourceRefs(UUID userId, YearMonth month) {
        StatsWindow window = statsWindow(month);
        List<SnapshotSourceRef> refs = statsMapper.selectStatsSourceRefs(userId, window.since(), window.until());
        return refs == null ? List.of() : refs;
    }

    private void saveInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) return;
        List<SnapshotInput> inputs = sourceRefs.stream()
                .filter(ref -> ref != null && ref.getInputKind() != null && ref.getInputId() != null
                        && ref.getInputRole() != null)
                .map(ref -> SnapshotInput.builder()
                        .inputKind(ref.getInputKind())
                        .inputId(ref.getInputId())
                        .inputRole(ref.getInputRole())
                        .build())
                .toList();
        if (!inputs.isEmpty()) snapshotInputMapper.insertAll(snapshot.getId(), inputs);
    }

    private void recordChatMemoryInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
        List<Long> messageIds = sourceRefs.stream()
                .filter(ref -> ref != null && "chat_message".equals(ref.getInputKind())
                        && "memory_source".equals(ref.getInputRole()) && ref.getInputId() != null)
                .map(SnapshotSourceRef::getInputId).distinct().toList();
        if (!messageIds.isEmpty()) snapshotLineageService.recordChatMessageInputs(snapshot.getId(), messageIds);
    }

    private static void addSources(Set<SnapshotSourceRef> target, List<SnapshotSourceRef> refs) {
        if (refs != null) refs.stream().filter(java.util.Objects::nonNull).forEach(target::add);
    }

    private static void addSource(Set<SnapshotSourceRef> target, String kind, Object rawId, String role) {
        Long id = toNullableLong(rawId);
        if (id != null) {
            target.add(SnapshotSourceRef.builder().inputKind(kind).inputId(id).inputRole(role).build());
        }
    }

    private static Long toNullableLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 组装 GenerateProfileRequest（llm_config 由 AiGrpcClient 补齐）+ 可信聊天记忆和个人词典
     *
     * <p>递归累计镜子（rolling-mirror-design.md §1）：month 非空（monthly 快照）时携带四块输入；
     * month 为 null（manual 快照）时同样按"截至现在的累计画像"组装——
     * 上期镜子取最新 monthly（无则最新 manual），回看窗口取近 30 天。</p>
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(
            UUID userId, ProfileStatsDTO stats, YearMonth month,
            List<SnapshotSourceRef> sourceRefs) {
        MirrorProfileProto.GenerateProfileRequest.Builder request = buildRequest(stats).toBuilder();

        org.xianshen.mumirrorb.service.AssistantContextService.BuiltContext profileContext = assistantContextService == null
                ? new org.xianshen.mumirrorb.service.AssistantContextService.BuiltContext(
                        org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext.getDefaultInstance(), List.of())
                : assistantContextService.buildForProfile(userId, "");
        request.setAssistantContext(profileContext.context());
        profileContext.sourceMessageIds().stream().distinct().forEach(messageId -> sourceRefs.add(
                SnapshotSourceRef.builder().inputKind("chat_message").inputId(messageId)
                        .inputRole("memory_source").build()));

        RollingContext rolling = collectRollingInputs(userId, month, stats);
        sourceRefs.addAll(rolling.sourceRefs());
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
                                  String statsFacts, boolean lookbackTruncated,
                                  List<SnapshotSourceRef> sourceRefs) {
    }

    private record PrevMirrorContext(String text, Set<Long> snapshotIds) { }

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
        LinkedHashSet<SnapshotSourceRef> sourceRefs = new LinkedHashSet<>();
        addSources(sourceRefs, statsMapper.selectTodoSourceRefs(userId));
        addSources(sourceRefs, statsMapper.selectRecentLearningSourceRefs(userId));

        PrevMirrorContext previous = buildPrevMirror(userId, month);
        for (Long id : previous.snapshotIds()) {
            sourceRefs.add(SnapshotSourceRef.builder().inputKind("snapshot").inputId(id)
                    .inputRole("parent").build());
        }

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
                addSource(sourceRefs, "record", row.get("recordId"), "correction_index");
                addSource(sourceRefs, "chunk", row.get("chunkId"), "correction_index");
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
            String body = c.getSegment();
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

        return new RollingContext(previous.text(), correctionIndex, lookback, facts.toString(), truncated,
                List.copyOf(sourceRefs));
    }

    /**
     * ① 上期镜子全文（递归链压缩）：
     * 上一个月（N-1）快照全文（五维+总结拼接）；距目标月超过 mirror_summary_after_months 的
     * 更早镜子只带一行压缩摘要。genesis 月（无任何更早镜子）返回提示文案（AI 侧空块不留孤儿节头）。
     */
    private PrevMirrorContext buildPrevMirror(UUID userId, YearMonth month) {
        List<ProfileSnapshot> monthly = snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", "monthly")
                        .eq("invalidated", false)
                        .eq("is_current", true)
                        .orderByAsc("period_month"));
        if (monthly.isEmpty()) {
            // 无 monthly 时回退最新 manual（首月 genesis 的常见形态：只有手动画像）
            ProfileSnapshot latestManual = snapshotMapper.selectLatest(userId, "manual");
            return latestManual == null ? new PrevMirrorContext("", Set.of())
                    : new PrevMirrorContext(fullMirrorText(latestManual), Set.of(latestManual.getId()));
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
            return new PrevMirrorContext("", Set.of()); // genesis：无上月镜子
        }

        StringBuilder sb = new StringBuilder();
        LinkedHashSet<Long> parentIds = new LinkedHashSet<>();
        parentIds.add(prev.getId());
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
            parentIds.add(s.getId());
        }
        return new PrevMirrorContext(sb.toString().trim(), Set.copyOf(parentIds));
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
     * 组装 GenerateProfileRequest 基础块（五维统计；llm_config 由 AiGrpcClient 补齐）
     *
     * <p>④ 待办统计实况不在 proto 结构化字段里重复——设计稿 §1 "④用现有统计字段，不加 proto"，
     * 另以 stats_facts（field 14，B 提案）携带渲染好的实况文本。</p>
     */
    private MirrorProfileProto.GenerateProfileRequest buildRequest(ProfileStatsDTO stats) {

        MirrorProfileProto.GenerateProfileRequest.Builder builder =
                MirrorProfileProto.GenerateProfileRequest.newBuilder()
                        .setTotalRecords(stats.getTotalRecords() == null ? 0 : stats.getTotalRecords())
                        .setTimeRange(stats.getTimeRange() == null ? "" : stats.getTimeRange());

        // 个人词典注入（lexicon-design.md 第 4 节）：GenerateProfile 传 confirmed top 30 全量（软约束）
        // userId 在 generate/generateMonthly 调用链上无法直接传入（stats 不含），经 ThreadLocal 上下文省略——
        // 由 buildRequest(userId, stats, month, sourceRefs) 注入个人词典。
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

        return builder.build();
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
                                .eq("invalidated", false)
                                .eq("is_current", true)
                                .lt("created_at", snapshot.getCreatedAt())
                                .orderByDesc("created_at")
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (prevMonthly != null) {
            baselineAt = prevMonthly.getCreatedAt();
        }

        int deletedSourceCount = snapshot.getId() == null ? 0
                : selectDeletedSourceCounts(List.of(snapshot)).getOrDefault(snapshot.getId(), 0);
        boolean current = snapshot.getIsCurrent() == null || snapshot.getIsCurrent();
        boolean invalidated = Boolean.TRUE.equals(snapshot.getInvalidated());
        String contextStatus = invalidated || ("monthly".equals(snapshot.getSnapshotType()) && !current)
                ? "archive_only" : "usable";

        return MirrorProfileVO.builder()
                .id(snapshot.getId())
                .snapshotType(snapshot.getSnapshotType())
                .periodMonth(snapshot.getPeriodMonth())
                .versionNo(snapshot.getVersionNo())
                .isCurrent(current)
                .contextStatus(contextStatus)
                .invalidatedAt(snapshot.getInvalidatedAt())
                .invalidatedReason(snapshot.getInvalidatedReason())
                .deletedSourceCount(deletedSourceCount)
                .archiveDiscontinuity(Boolean.TRUE.equals(snapshot.getArchiveDiscontinuity()))
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
