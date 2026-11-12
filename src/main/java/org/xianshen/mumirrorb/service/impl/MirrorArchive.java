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

/** MirrorArchive keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorArchive {
    private final MirrorServiceImpl owner;
    MirrorArchive(MirrorServiceImpl owner) { this.owner=owner; }

    @Transactional(readOnly = true)
    public MirrorProfileVO getMirror(UUID userId) {
        // 最新 manual，无则最新 monthly（前端"查看镜子"默认展示最近一次画像）
        ProfileSnapshot snapshot = owner.snapshotMapper.selectLatest(userId, "manual");
        if (snapshot == null) {
            snapshot = owner.snapshotMapper.selectLatest(userId, "monthly");
        }
        if (snapshot == null) {
            return MirrorProfileVO.builder().build(); // 从未生成：空 VO，前端引导生成
        }
        return owner.toVO(snapshot);
    }



    /** Keyset-paginated archive; invalidated snapshots remain visible. */

    @Transactional(readOnly = true)
    public SnapshotPageVO listSnapshots(UUID userId, OffsetDateTime beforeCreatedAt, Long beforeId, int limit) {
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分页游标时间和快照 ID 必须同时提供");
        }
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "每页数量必须在 1 到 100 之间");
        }
        List<ProfileSnapshot> fetched = owner.snapshotMapper.selectSnapshotPage(userId, beforeCreatedAt, beforeId,
                limit + 1);
        if (fetched == null) fetched = List.of();
        boolean hasMore = fetched.size() > limit;
        List<ProfileSnapshot> snapshots = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, Integer> deletedSourceCounts = owner.selectDeletedSourceCounts(snapshots);
        List<SnapshotListVO> items = snapshots.stream()
                .map(snapshot -> owner.toSnapshotListVO(snapshot,
                        deletedSourceCounts.getOrDefault(snapshot.getId(), 0)))
                .toList();
        SnapshotListVO last = hasMore && !items.isEmpty() ? items.get(items.size() - 1) : null;
        SnapshotPageVO page = SnapshotPageVO.builder()
                .items(items)
                .hasMore(hasMore)
                .nextCreatedAt(last == null ? null : last.getCreatedAt())
                .nextId(last == null ? null : last.getId())
                .build();
        owner.log.debug("快照归档分页：用户 {} 返回 {} 份，hasMore={}", userId, items.size(), hasMore);
        return page;
    }


    @Transactional(readOnly = true)
    public List<String> snapshotMonths(UUID userId) {
        YearMonth currentMonth = YearMonth.now(owner.ZONE);
        Set<YearMonth> candidates = new TreeSet<>();
        List<String> recordMonths = owner.statsMapper.selectAuthorizedRecordMonths(userId);
        List<String> snapshotMonths = owner.snapshotMapper.selectMonthlyPeriodMonths(userId);
        owner.addValidMonths(candidates, recordMonths, currentMonth);
        owner.addValidMonths(candidates, snapshotMonths, currentMonth);

        return candidates.stream().map(YearMonth::toString).toList();
    }



    protected Map<Long, Integer> selectDeletedSourceCounts(List<ProfileSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return Map.of();
        List<Long> ids = snapshots.stream().map(ProfileSnapshot::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = owner.snapshotInputMapper.selectDeletedSourceCounts(ids);
        if (rows == null || rows.isEmpty()) return Map.of();
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long snapshotId = owner.toNullableLong(row.get("snapshotId"));
            if (snapshotId != null) result.put(snapshotId, (int) owner.toLong(row.get("deletedSourceCount")));
        }
        return result;
    }



    protected SnapshotListVO toSnapshotListVO(ProfileSnapshot snapshot, int deletedSourceCount) {
        boolean current = snapshot.getIsCurrent() == null || snapshot.getIsCurrent();
        boolean invalidated = Boolean.TRUE.equals(snapshot.getInvalidated());
        String contextStatus = invalidated || ("monthly".equals(snapshot.getSnapshotType()) && !current)
                ? "archive_only" : "usable";
        Double drift = "monthly".equals(snapshot.getSnapshotType()) && "usable".equals(contextStatus)
                ? owner.snapshotMapper.selectDriftDistance(snapshot.getId()) : null;
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
                .overallSummary(owner.truncateSummary(snapshot.getOverallSummary()))
                .build();
    }



    /**
     * 单份完整快照（归属校验 + 复用 toVO 组装，结构与 GET /api/mirror 一致）
     */

    @Transactional(readOnly = true)
    public MirrorProfileVO getSnapshot(Long snapshotId, UUID userId) {
        ProfileSnapshot snapshot = owner.snapshotMapper.selectById(snapshotId);
        // 归属校验：不存在或不属于当前用户一律按 RECORD_NOT_FOUND 处理（不暴露他人快照存在性）
        if (snapshot == null || !snapshot.getUserId().equals(userId)) {
            owner.log.warn("快照查询被拒：ID {}，用户 {}（不存在或非本人）", snapshotId, userId);
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND);
        }
        return owner.toVO(snapshot);
    }


    @Transactional(readOnly = true)
    public Double driftDistance(Long snapshotId) {
        return owner.snapshotMapper.selectDriftDistance(snapshotId);
    }



    protected MirrorProfileVO toVO(ProfileSnapshot snapshot) {
        Double drift = null;
        OffsetDateTime baselineAt = null;
        if ("monthly".equals(snapshot.getSnapshotType())) {
            drift = owner.snapshotMapper.selectDriftDistance(snapshot.getId());
        }
        ProfileSnapshot prevMonthly = owner.snapshotMapper.selectList(
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
                : owner.selectDeletedSourceCounts(List.of(snapshot)).getOrDefault(snapshot.getId(), 0);
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
}
