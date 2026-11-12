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

/** MirrorGeneration keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorGeneration {
    private final MirrorServiceImpl owner;
    MirrorGeneration(MirrorServiceImpl owner) { this.owner=owner; }

    @Transactional
    public MirrorProfileVO generate(UUID userId) {
        owner.log.info("============ 画像生成开始，用户: {} ============", userId);
        owner.snapshotLineageService.lockUserLineage(userId);

        // 0. 校验用户已配置 LLM（否则 Python 端无法调用）
        UserSettings settings = owner.settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先在设置中配置 AI 模型");
        }

        // 生成手动镜子前，先补齐已有失效月度链；没有失效链时不额外生成月度镜子。
        owner.replayInvalidatedMonthlyChainThrough(userId, YearMonth.now(owner.ZONE).minusMonths(1));

        // 1. 五维统计（最近 30 天口径）；聊天语料只通过可信记忆上下文授权注入。
        ProfileStatsDTO stats = owner.collectStats(userId);
        List<SnapshotSourceRef> sourceRefs = new ArrayList<>(owner.selectStatsSourceRefs(userId, null));

        // 2. gRPC GenerateProfile（AiGrpcClient 内部补 llm_config）
        // manual 快照语义为"截至现在的累计画像"：rolling mirror 四块输入同样生效
        // （上期镜子=最新 monthly 或 manual，回看窗口=近 30 天）
        var generationRequest = owner.buildRequest(userId, stats, null, sourceRefs);
        MirrorProfileProto.GenerateProfileResponse response =
                owner.aiGrpcClient.generateProfile(userId, generationRequest);
        boolean archiveDiscontinuity = owner.hasDiscontinuousParent(sourceRefs);

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
                .createdAt(OffsetDateTime.now(owner.ZONE))
                .build();
        owner.snapshotMapper.insert(snapshot);
        owner.saveInputs(snapshot, sourceRefs);
        owner.recordChatMemoryInputs(snapshot, sourceRefs);
        owner.log.info("画像快照已保存，ID: {}, 类型: manual", snapshot.getId());
        if (owner.achievementActivity != null)
            owner.achievementActivity.preferenceUsed(userId, "lookback", String.valueOf(generationRequest.getMirrorLookback()));

        // 4. 五维文本固定顺序拼接 → Embed → 回写向量（失败不阻断：向量缺失仅影响漂移检测）
        try {
            String text = snapshot.joinedAnalysisText();
            if (!text.isBlank()) {
                var embedResponse = owner.aiGrpcClient.embed(userId, text);
                snapshot.setEmbedding(embedResponse.getVectorList());
                owner.snapshotMapper.updateById(snapshot);
            }
        } catch (Exception e) {
            owner.log.warn("画像向量生成失败（不阻断），快照ID: {}，原因: {}", snapshot.getId(), e.getMessage());
        }

        owner.log.info("============ 画像生成结束，快照ID: {} ============", snapshot.getId());
        return owner.toVO(snapshot);
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
        owner.log.info("============ 月度画像定时任务开始 ============");
        // 注册用户逐个生成（单实例小规模，用户量 ≤ 6，串行即可）
        List<UserSettings> all = owner.settingsMapper.selectList(new QueryWrapper<>());
        for (UserSettings settings : all) {
            UUID userId = settings.getUserId();
            try {
                owner.generateMonthly(userId);
            } catch (Exception e) {
                // 单用户失败不影响其他用户
                owner.log.error("用户 {} 月度画像生成失败", userId, e);
            }
            // 顺路：个人词典月度维护（合并建议 + 漂移审计，内部全量 try-catch）
            owner.glossaryService.monthlyMaintenance(userId);
        }
        owner.log.info("============ 月度画像定时任务结束 ============");
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
        YearMonth lastMonth = YearMonth.now(owner.ZONE).minusMonths(1);
        owner.log.info("月度画像（定时）生成上个月 [{}] 的完整画像，用户: {}", lastMonth, userId);
        // monthlySnapshot 调用本类方法时会绕过 Spring 的方法代理；显式事务确保重放链
        // 任一月份失败时，前面已写入的新版本和依赖行一起回滚。
        owner.transactionTemplate.execute(status -> {
            owner.snapshotLineageService.lockUserLineage(userId);
            owner.generateMonthlyThrough(userId, lastMonth);
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
        owner.snapshotLineageService.lockUserLineage(userId);
        return owner.generateMonthlyVersion(userId, month, false);
    }



    protected MirrorProfileVO generateMonthlyVersion(UUID userId, YearMonth month, boolean replayDiscontinuity) {
        UserSettings settings = owner.settingsMapper.selectOne(
                new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, userId));
        if (settings == null || settings.getAiApiKey() == null || settings.getAiApiKey().isBlank()) {
            owner.log.info("用户 {} 未配置 LLM，跳过月度画像", userId);
            return MirrorProfileVO.builder().build();
        }

        ProfileStatsDTO stats = owner.collectStats(userId, month);
        List<SnapshotSourceRef> sourceRefs = new ArrayList<>(owner.selectStatsSourceRefs(userId, month));
        ProfileSnapshot previousCurrent = owner.snapshotMapper.selectCurrentMonthlyForUpdate(userId, month.toString());
        int versionNo = owner.snapshotMapper.selectMaxVersionNo(userId, month.toString()) + 1;
        var generationRequest = owner.buildRequest(userId, stats, month, sourceRefs);
        MirrorProfileProto.GenerateProfileResponse response =
                owner.aiGrpcClient.generateProfile(userId, generationRequest);
        boolean archiveDiscontinuity = replayDiscontinuity || owner.hasDiscontinuousParent(sourceRefs);

        if (previousCurrent != null) {
            previousCurrent.setIsCurrent(false);
            owner.snapshotMapper.updateById(previousCurrent);
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
                .createdAt(OffsetDateTime.now(owner.ZONE))
                .build();
        owner.snapshotMapper.insert(snapshot);
        owner.saveInputs(snapshot, sourceRefs);
        owner.recordChatMemoryInputs(snapshot, sourceRefs);

        if (owner.achievementActivity != null)
            owner.achievementActivity.preferenceUsed(userId, "lookback", String.valueOf(generationRequest.getMirrorLookback()));

        try {
            String text = snapshot.joinedAnalysisText();
            if (!text.isBlank()) {
                var embedResponse = owner.aiGrpcClient.embed(userId, text);
                snapshot.setEmbedding(embedResponse.getVectorList());
                owner.snapshotMapper.updateById(snapshot);
            }
        } catch (Exception e) {
            owner.log.warn("月度画像向量生成失败，快照ID: {}，原因: {}", snapshot.getId(), e.getMessage());
        }

        owner.log.info("用户 {} 月度镜子版本已生成，月份: [{}]，版本: {}，快照ID: {}",
                userId, month, versionNo, snapshot.getId());
        return owner.toVO(snapshot);
    }
}
