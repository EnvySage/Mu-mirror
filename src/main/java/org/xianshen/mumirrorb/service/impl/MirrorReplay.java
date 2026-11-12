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

/** MirrorReplay keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorReplay {
    private final MirrorServiceImpl owner;
    MirrorReplay(MirrorServiceImpl owner) { this.owner=owner; }


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

    @Transactional
    public MirrorProfileVO generateMonthlyFor(UUID userId, String month) {
        owner.snapshotLineageService.lockUserLineage(userId);
        YearMonth target;
        if (month == null || month.isBlank()) {
            target = YearMonth.now(owner.ZONE).minusMonths(1);
        } else {
            try {
                target = YearMonth.parse(month.trim());
            } catch (Exception e) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "月份格式应为 yyyy-MM，如 2026-08");
            }
        }
        YearMonth current = YearMonth.now(owner.ZONE);
        if (!target.isBefore(current)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只能生成历史月份的月度画像（当前月请用「生成画像」，未来月份无数据）");
        }
        owner.log.info("按月生成月度画像，用户: {}, 月份: [{}]", userId, target);

        return owner.generateMonthlyThrough(userId, target);
    }



    /**
     * 为指定历史月份生成镜子；若更早存在失效的当前月度版本，则从最早失效月份懒重放到目标月。
     */
    @Transactional
    public MirrorProfileVO generateMonthlyThrough(UUID userId, YearMonth target) {
        owner.snapshotLineageService.lockUserLineage(userId);
        List<String> invalidatedPeriods = owner.snapshotMapper.selectInvalidatedCurrentMonthlyPeriods(
                userId, target.toString());
        if (invalidatedPeriods == null || invalidatedPeriods.isEmpty()) {
            return owner.generateMonthlyVersion(userId, target, false);
        }

        YearMonth earliestInvalidated = invalidatedPeriods.stream()
                .map(YearMonth::parse)
                .min(YearMonth::compareTo)
                .orElse(target);
        return owner.replayMonthlyChainThrough(userId, target, earliestInvalidated);
    }



    /** Manual generation replays only when an existing monthly chain is stale. */
    protected void replayInvalidatedMonthlyChainThrough(UUID userId, YearMonth target) {
        List<String> invalidatedPeriods = owner.snapshotMapper.selectInvalidatedCurrentMonthlyPeriods(
                userId, target.toString());
        if (invalidatedPeriods == null || invalidatedPeriods.isEmpty()) return;

        YearMonth earliestInvalidated = invalidatedPeriods.stream()
                .map(YearMonth::parse)
                .min(YearMonth::compareTo)
                .orElse(target);
        owner.replayMonthlyChainThrough(userId, target, earliestInvalidated);
    }



    /** Rebuild every authorized input month after the latest safe monthly checkpoint. */
    protected MirrorProfileVO replayMonthlyChainThrough(UUID userId, YearMonth target, YearMonth earliestInvalidated) {
        String checkpointValue = owner.snapshotMapper.selectLatestUsableMonthlyPeriodBefore(
                userId, earliestInvalidated.toString());
        YearMonth checkpoint = checkpointValue == null ? null : YearMonth.parse(checkpointValue);
        boolean hasSafeCheckpoint = checkpoint != null;
        YearMonth replayStart;
        boolean archiveDiscontinuity = false;
        if (hasSafeCheckpoint) {
            replayStart = checkpoint.plusMonths(1);
        } else {
            List<String> authorizedMonthValues = owner.statsMapper.selectAuthorizedRecordMonths(userId);
            List<YearMonth> authorizedMonths = (authorizedMonthValues == null ? List.<String>of() : authorizedMonthValues).stream()
                    .map(YearMonth::parse)
                    .filter(month -> !month.isAfter(target))
                    .toList();
            replayStart = authorizedMonths.stream().min(YearMonth::compareTo).orElse(earliestInvalidated);
            String earliestArchivedValue = owner.snapshotMapper.selectEarliestArchivedMonthlyPeriod(userId);
            if (earliestArchivedValue == null || authorizedMonths.isEmpty()) {
                archiveDiscontinuity = true;
            } else {
                archiveDiscontinuity = replayStart.isAfter(YearMonth.parse(earliestArchivedValue));
            }
        }

        Set<YearMonth> periods = new TreeSet<>();
        List<String> existingPeriods = owner.snapshotMapper.selectCurrentMonthlyPeriods(
                userId, replayStart.toString(), target.toString());
        if (existingPeriods != null) existingPeriods.stream().map(YearMonth::parse).forEach(periods::add);
        List<String> authorizedMonths = owner.statsMapper.selectAuthorizedRecordMonths(userId);
        if (authorizedMonths != null) {
            authorizedMonths.stream().map(YearMonth::parse)
                    .filter(month -> !month.isBefore(replayStart) && !month.isAfter(target))
                    .forEach(periods::add);
        }
        periods.add(target);

        MirrorProfileVO result = MirrorProfileVO.builder().build();
        for (YearMonth period : periods) {
            result = owner.generateMonthlyVersion(userId, period, archiveDiscontinuity);
        }
        if (result.getId() != null && replayStart.isAfter(earliestInvalidated)) {
            // These invalidated versions precede the first authorized month and cannot be rebuilt.
            // Keep them as archives, but remove their stale "current" marker so future generations
            // start from the new discontinuous baseline instead of replaying the same gap forever.
            owner.snapshotMapper.retireUnreconstructableInvalidatedCurrentMonthlyVersions(
                    userId, earliestInvalidated.toString(), replayStart.toString());
        }
        return result;
    }



    protected boolean hasDiscontinuousParent(List<SnapshotSourceRef> sourceRefs) {
        if (sourceRefs == null) return false;
        for (SnapshotSourceRef ref : sourceRefs) {
            if (ref != null && "snapshot".equals(ref.getInputKind()) && ref.getInputId() != null) {
                ProfileSnapshot parent = owner.snapshotMapper.selectById(ref.getInputId());
                if (parent != null && Boolean.TRUE.equals(parent.getArchiveDiscontinuity())) return true;
            }
        }
        return false;
    }
}
