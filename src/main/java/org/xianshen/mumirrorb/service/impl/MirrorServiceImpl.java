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

@Service
@RequiredArgsConstructor
public class MirrorServiceImpl implements MirrorService {
    protected static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MirrorServiceImpl.class);
    private final MirrorArchive mirrorArchive = new MirrorArchive(this);
    private final MirrorGeneration mirrorGeneration = new MirrorGeneration(this);
    private final MirrorStatistics mirrorStatistics = new MirrorStatistics(this);
    private final MirrorReplay mirrorReplay = new MirrorReplay(this);
    private final MirrorLineage mirrorLineage = new MirrorLineage(this);
    private final MirrorRolling mirrorRolling = new MirrorRolling(this);



    protected static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    protected static final int KEYWORD_TOP = 10;
    /** 待办明细最多条数（前端协议） */
    protected static final int TODO_ITEMS_LIMIT = 10;
    /** 列表 overallSummary 截断长度（前端协议：前 50 字） */
    protected static final int SUMMARY_PREVIEW_LENGTH = 50;

    protected final ProfileSnapshotMapper snapshotMapper;
    protected final SnapshotInputMapper snapshotInputMapper;
    protected final ProfileStatsMapper statsMapper;
    protected final SettingsMapper settingsMapper;
    protected final ChunkMapper chunkMapper;
    protected final AiGrpcClient aiGrpcClient;
    protected final GlossaryService glossaryService;
    protected final MirrorProperties mirrorProperties;
    protected final TransactionTemplate transactionTemplate;
    protected final SnapshotLineageService snapshotLineageService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected org.xianshen.mumirrorb.service.AssistantContextService assistantContextService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    protected org.xianshen.mumirrorb.achievement.AchievementActivity achievementActivity;

    @Override
    @Transactional(readOnly = true)
    public MirrorProfileVO getMirror(UUID userId) {
        return mirrorArchive.getMirror(userId);
    }


    @Override
    @Transactional(readOnly = true)
    public SnapshotPageVO listSnapshots(UUID userId, OffsetDateTime beforeCreatedAt, Long beforeId, int limit) {
        return mirrorArchive.listSnapshots(userId, beforeCreatedAt, beforeId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> snapshotMonths(UUID userId) {
        return mirrorArchive.snapshotMonths(userId);
    }

    protected static void addValidMonths(Set<YearMonth> target, List<String> values, YearMonth currentMonth) {
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

    protected Map<Long, Integer> selectDeletedSourceCounts(List<ProfileSnapshot> snapshots) {
        return mirrorArchive.selectDeletedSourceCounts(snapshots);
    }

    protected SnapshotListVO toSnapshotListVO(ProfileSnapshot snapshot, int deletedSourceCount) {
        return mirrorArchive.toSnapshotListVO(snapshot, deletedSourceCount);
    }


    @Override
    @Transactional(readOnly = true)
    public MirrorProfileVO getSnapshot(Long snapshotId, UUID userId) {
        return mirrorArchive.getSnapshot(snapshotId, userId);
    }

    /**
     * overallSummary 前 50 字截断（超出追加 ...）
     */
    protected static String truncateSummary(String summary) {
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
        return mirrorGeneration.generate(userId);
    }


    @Override
    @Transactional(readOnly = true)
    public MirrorStatsVO stats(UUID userId, int days) {
        return mirrorStatistics.stats(userId, days);
    }


    protected List<LocalDate> dateWindow(int days) {
        return mirrorStatistics.dateWindow(days);
    }

    @Override
    @Transactional(readOnly = true)
    public Double driftDistance(Long snapshotId) {
        return mirrorArchive.driftDistance(snapshotId);
    }


    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 2 1 * ?", zone = "Asia/Shanghai")
    public void monthlySnapshot() {
        mirrorGeneration.monthlySnapshot();
    }


    @Transactional
    public void generateMonthly(UUID userId) {
        mirrorGeneration.generateMonthly(userId);
    }


    @Transactional
    public MirrorProfileVO generateMonthly(UUID userId, YearMonth month) {
        return mirrorGeneration.generateMonthly(userId, month);
    }

    protected MirrorProfileVO generateMonthlyVersion(UUID userId, YearMonth month, boolean replayDiscontinuity) {
        return mirrorGeneration.generateMonthlyVersion(userId, month, replayDiscontinuity);
    }




    @Override
    @Transactional
    public MirrorProfileVO generateMonthlyFor(UUID userId, String month) {
        return mirrorReplay.generateMonthlyFor(userId, month);
    }


    @Transactional
    public MirrorProfileVO generateMonthlyThrough(UUID userId, YearMonth target) {
        return mirrorReplay.generateMonthlyThrough(userId, target);
    }


    protected void replayInvalidatedMonthlyChainThrough(UUID userId, YearMonth target) {
        mirrorReplay.replayInvalidatedMonthlyChainThrough(userId, target);
    }


    protected MirrorProfileVO replayMonthlyChainThrough(UUID userId, YearMonth target, YearMonth earliestInvalidated) {
        return mirrorReplay.replayMonthlyChainThrough(userId, target, earliestInvalidated);
    }

    protected boolean hasDiscontinuousParent(List<SnapshotSourceRef> sourceRefs) {
        return mirrorReplay.hasDiscontinuousParent(sourceRefs);
    }


    protected ProfileStatsDTO collectStats(UUID userId) {
        return mirrorStatistics.collectStats(userId);
    }


    protected ProfileStatsDTO collectStats(UUID userId, YearMonth month) {
        return mirrorStatistics.collectStats(userId, month);
    }

    protected record StatsWindow(OffsetDateTime since, OffsetDateTime until, String label) { }

    protected StatsWindow statsWindow(YearMonth month) {
        return mirrorStatistics.statsWindow(month);
    }

    protected List<SnapshotSourceRef> selectStatsSourceRefs(UUID userId, YearMonth month) {
        return mirrorStatistics.selectStatsSourceRefs(userId, month);
    }

    protected void saveInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
        mirrorLineage.saveInputs(snapshot, sourceRefs);
    }

    protected void recordChatMemoryInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
        mirrorLineage.recordChatMemoryInputs(snapshot, sourceRefs);
    }

    protected static void addSources(Set<SnapshotSourceRef> target, List<SnapshotSourceRef> refs) {
        if (refs != null) refs.stream().filter(java.util.Objects::nonNull).forEach(target::add);
    }

    protected static void addSource(Set<SnapshotSourceRef> target, String kind, Object rawId, String role) {
        Long id = toNullableLong(rawId);
        if (id != null) {
            target.add(SnapshotSourceRef.builder().inputKind(kind).inputId(id).inputRole(role).build());
        }
    }

    protected static Long toNullableLong(Object value) {
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


    protected MirrorProfileProto.GenerateProfileRequest buildRequest(
            UUID userId, ProfileStatsDTO stats, YearMonth month,
            List<SnapshotSourceRef> sourceRefs) {
        return mirrorLineage.buildRequest(userId, stats, month, sourceRefs);
    }

    /**
     * 递归累计镜子四块输入（① prev_mirror / ③ correction_index / ② lookback 档位 / ④ stats_facts）
     *
     * <p>闸门（MirrorProperties，全配置化）：条数闸与总字符闸先触发者生效，旧→新裁剪；
     * 触发时打 WARN 日志（F 侧 meta toast 透出待联调接线）。</p>
     */
    protected record RollingContext(String prevMirror, String correctionIndex, int lookback, String records,
                                  String statsFacts, boolean lookbackTruncated,
                                  List<SnapshotSourceRef> sourceRefs) {
    }

    protected record PrevMirrorContext(String text, Set<Long> snapshotIds) { }

    protected RollingContext collectRollingInputs(UUID userId, YearMonth month, ProfileStatsDTO stats) {
        return mirrorRolling.collectRollingInputs(userId, month, stats);
    }


    protected PrevMirrorContext buildPrevMirror(UUID userId, YearMonth month) {
        return mirrorRolling.buildPrevMirror(userId, month);
    }

    /**
     * 快照五维+总结拼接（① 上期镜子全文的标准拼法）
     */
    protected static String fullMirrorText(ProfileSnapshot s) {
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

    protected static void appendSection(StringBuilder sb, String label, String text) {
        if (text != null && !text.isBlank()) {
            sb.append(label).append("：").append(text).append('\n');
        }
    }

    /**
     * 更早镜子的一行压缩摘要（【月份】总结前 60 字）
     */
    protected static String oneLineSummary(String key, ProfileSnapshot s) {
        String summary = s.getOverallSummary() == null ? "" : s.getOverallSummary();
        if (summary.length() > 60) {
            summary = summary.substring(0, 60) + "…";
        }
        return "【" + key + " 镜子摘要】" + summary;
    }

    /**
     * 历史 NULL period_month 行按 createdAt 推断归属月份（定时语义：次月生成上月画像）
     */
    protected static String inferPeriodMonth(ProfileSnapshot s) {
        if (s.getCreatedAt() == null) {
            return null;
        }
        return s.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate().minusMonths(1).toString().substring(0, 7);
    }


    protected MirrorProfileProto.GenerateProfileRequest buildRequest(ProfileStatsDTO stats) {
        return mirrorLineage.buildRequest(stats);
    }

    protected MirrorProfileVO toVO(ProfileSnapshot snapshot) {
        return mirrorArchive.toVO(snapshot);
    }

    protected static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    protected static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    protected static List<String> parseStringList(Object json) {
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
