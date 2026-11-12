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

/** MirrorRolling keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorRolling {
    private final MirrorServiceImpl owner;
    MirrorRolling(MirrorServiceImpl owner) { this.owner=owner; }


    protected RollingContext collectRollingInputs(UUID userId, YearMonth month, ProfileStatsDTO stats) {
        // ---- 档位：设置透传（0-3，缺省/越界兜底 1）----
        UserSettings settings = owner.settingsMapper.selectOne(
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
            windowUntil = month.plusMonths(1).atDay(1).atStartOfDay(owner.ZONE).toOffsetDateTime();
            windowSince = month.plusMonths(1).minusMonths(lookback).atDay(1)
                    .atStartOfDay(owner.ZONE).toOffsetDateTime();
        } else {
            windowUntil = LocalDate.now(owner.ZONE).plusDays(1).atStartOfDay(owner.ZONE).toOffsetDateTime();
            windowSince = LocalDate.now(owner.ZONE).minusDays(30).atStartOfDay(owner.ZONE).toOffsetDateTime();
        }

        // ---- ① 上期镜子全文（递归链）----
        LinkedHashSet<SnapshotSourceRef> sourceRefs = new LinkedHashSet<>();
        owner.addSources(sourceRefs, owner.statsMapper.selectTodoSourceRefs(userId));
        owner.addSources(sourceRefs, owner.statsMapper.selectRecentLearningSourceRefs(userId));

        PrevMirrorContext previous = owner.buildPrevMirror(userId, month);
        for (Long id : previous.snapshotIds()) {
            sourceRefs.add(SnapshotSourceRef.builder().inputKind("snapshot").inputId(id)
                    .inputRole("parent").build());
        }

        // ---- ③ 校正索引：仅 lookback=0 时带（低档用户防误差累积的补偿）----
        // 覆盖上期镜子涉及的记录窗口（上月整月）：0 档不带原文，靠这份 title+日期清单对账
        String correctionIndex = "";
        if (lookback == 0) {
            OffsetDateTime idxSince = month != null
                    ? month.atDay(1).atStartOfDay(owner.ZONE).toOffsetDateTime()
                    : LocalDate.now(owner.ZONE).minusDays(30).atStartOfDay(owner.ZONE).toOffsetDateTime();
            OffsetDateTime idxUntil = month != null
                    ? month.plusMonths(1).atDay(1).atStartOfDay(owner.ZONE).toOffsetDateTime()
                    : LocalDate.now(owner.ZONE).plusDays(1).atStartOfDay(owner.ZONE).toOffsetDateTime();
            List<Map<String, Object>> rows = owner.chunkMapper.selectCorrectionIndex(userId, idxSince, idxUntil);
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("- ").append(row.get("title")).append("（").append(row.get("record_date")).append("）");
                owner.addSource(sourceRefs, "record", row.get("recordId"), "correction_index");
                owner.addSource(sourceRefs, "chunk", row.get("chunkId"), "correction_index");
            }
            correctionIndex = sb.toString();
        }

        // ---- ② 本月原始记录（四道防洪闸）----
        boolean truncated = false;
        List<Chunk> chunks = owner.chunkMapper.selectLookbackChunks(userId, windowSince, windowUntil,
                owner.mirrorProperties.getLookbackMaxChunks() + 1);
        if (chunks.size() > owner.mirrorProperties.getLookbackMaxChunks()) {
            // 条数闸：超限取最近的（SQL 升序，砍头部旧记录）
            chunks = chunks.subList(chunks.size() - owner.mirrorProperties.getLookbackMaxChunks(), chunks.size());
            truncated = true;
            owner.log.warn("回看原文触发条数闸（lookback_max_chunks={}），已截取最近部分，用户: {}",
                    owner.mirrorProperties.getLookbackMaxChunks(), userId);
        }
        StringBuilder records = new StringBuilder();
        int perChunkMax = owner.mirrorProperties.getPerChunkMaxChars();
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
        for (Chunk c : chunks) {
            OffsetDateTime at = c.getCreatedAt() == null ? null : c.getCreatedAt().atZoneSameInstant(owner.ZONE).toOffsetDateTime();
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
        int maxChars = owner.mirrorProperties.getLookbackMaxChars();
        if (records.length() > maxChars) {
            truncated = true;
            String all = records.toString();
            String tail = all.substring(all.length() - maxChars);
            int nl = tail.indexOf('\n');
            if (nl >= 0 && nl + 1 < tail.length()) {
                tail = tail.substring(nl + 1); // 从下一条完整记录起
            }
            records = new StringBuilder(tail);
            owner.log.warn("回看原文触发总字符闸（lookback_max_chars={}），已按旧→新裁剪到 {} 字符，用户: {}",
                    maxChars, records.length(), userId);
        }

        // ---- ④ 待办/统计实况直查（数据库实时值；LLM 只叙事不记账）----
        StringBuilder facts = new StringBuilder();
        int total = 0;
        int completed = 0;
        int notStarted = 0;
        int inProgress = 0;
        for (Map<String, Object> row : owner.statsMapper.selectTodoStatusCounts(userId)) {
            int count = (int) owner.toLong(row.get("count"));
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
        List<ProfileStatsDTO.TodoItemDTO> openTodos = owner.statsMapper.selectOpenTodos(userId);
        facts.append("当前未完成待办（").append(openTodos.size()).append(" 条）：\n");
        for (ProfileStatsDTO.TodoItemDTO t : openTodos) {
            facts.append("- [").append(t.getCreatedAt() == null ? "" : t.getCreatedAt()).append("] ")
                    .append(owner.nullToEmpty(t.getTitle()))
                    .append("：").append(owner.nullToEmpty(t.getSummary()))
                    .append("（状态：").append(owner.nullToEmpty(t.getTaskStatus())).append("）\n");
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
    protected PrevMirrorContext buildPrevMirror(UUID userId, YearMonth month) {
        List<ProfileSnapshot> monthly = owner.snapshotMapper.selectList(
                new QueryWrapper<ProfileSnapshot>()
                        .eq("user_id", userId)
                        .eq("snapshot_type", "monthly")
                        .eq("invalidated", false)
                        .eq("is_current", true)
                        .orderByAsc("period_month"));
        if (monthly.isEmpty()) {
            // 无 monthly 时回退最新 manual（首月 genesis 的常见形态：只有手动画像）
            ProfileSnapshot latestManual = owner.snapshotMapper.selectLatest(userId, "manual");
            return latestManual == null ? new PrevMirrorContext("", Set.of())
                    : new PrevMirrorContext(owner.fullMirrorText(latestManual), Set.of(latestManual.getId()));
        }

        String targetKey = month == null ? LocalDate.now(owner.ZONE).toString().substring(0, 7) : month.toString();
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
                String inferred = owner.inferPeriodMonth(s);
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
        sb.append(owner.fullMirrorText(prev));
        // 递归链：更早的镜子只带一行压缩摘要（超 mirror_summary_after_months 个月的也不省略摘要行——
        // 设计稿 §1 "超 12 个月的更早镜子只带一行压缩摘要"，即链上全部更早镜子都只保留一行）
        int summaryMonths = owner.mirrorProperties.getMirrorSummaryAfterMonths();
        for (int i = monthly.size() - 1; i >= 0; i--) {
            ProfileSnapshot s = monthly.get(i);
            if (s.getId().equals(prev.getId())) {
                break;
            }
            String key = s.getPeriodMonth() != null ? s.getPeriodMonth() : owner.inferPeriodMonth(s);
            if (key == null) {
                continue;
            }
            long monthsAgo = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.parse(key),
                    month == null ? YearMonth.now(owner.ZONE) : month);
            if (monthsAgo <= 0) {
                continue;
            }
            String line = owner.oneLineSummary(key, s);
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
}
