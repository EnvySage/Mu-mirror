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

/** MirrorLineage keeps the service entry points and transaction boundaries in MirrorServiceImpl. */
final class MirrorLineage {
    private final MirrorServiceImpl owner;
    MirrorLineage(MirrorServiceImpl owner) { this.owner=owner; }


    protected void saveInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
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
        if (!inputs.isEmpty()) owner.snapshotInputMapper.insertAll(snapshot.getId(), inputs);
    }



    protected void recordChatMemoryInputs(ProfileSnapshot snapshot, List<SnapshotSourceRef> sourceRefs) {
        List<Long> messageIds = sourceRefs.stream()
                .filter(ref -> ref != null && "chat_message".equals(ref.getInputKind())
                        && "memory_source".equals(ref.getInputRole()) && ref.getInputId() != null)
                .map(SnapshotSourceRef::getInputId).distinct().toList();
        if (!messageIds.isEmpty()) owner.snapshotLineageService.recordChatMessageInputs(snapshot.getId(), messageIds);
    }



    /**
     * 组装 GenerateProfileRequest（llm_config 由 AiGrpcClient 补齐）+ 可信聊天记忆和个人词典
     *
     * <p>递归累计镜子（rolling-mirror-design.md §1）：month 非空（monthly 快照）时携带四块输入；
     * month 为 null（manual 快照）时同样按"截至现在的累计画像"组装——
     * 上期镜子取最新 monthly（无则最新 manual），回看窗口取近 30 天。</p>
     */
    protected MirrorProfileProto.GenerateProfileRequest buildRequest(
            UUID userId, ProfileStatsDTO stats, YearMonth month,
            List<SnapshotSourceRef> sourceRefs) {
        MirrorProfileProto.GenerateProfileRequest.Builder request = owner.buildRequest(stats).toBuilder();

        org.xianshen.mumirrorb.service.AssistantContextService.BuiltContext profileContext = owner.assistantContextService == null
                ? new org.xianshen.mumirrorb.service.AssistantContextService.BuiltContext(
                        org.xianshen.mumirrorb.grpc.gen.CommonProto.AssistantContext.getDefaultInstance(), List.of())
                : owner.assistantContextService.buildForProfile(userId, "");
        request.setAssistantContext(profileContext.context());
        profileContext.sourceMessageIds().stream().distinct().forEach(messageId -> sourceRefs.add(
                SnapshotSourceRef.builder().inputKind("chat_message").inputId(messageId)
                        .inputRole("memory_source").build()));

        RollingContext rolling = owner.collectRollingInputs(userId, month, stats);
        sourceRefs.addAll(rolling.sourceRefs());
        // mirror_lookback 为 proto3 optional（shared-protocol 2026-09-06 修正）：显式 set 区分
        // "未传=AI 侧缺省 1 档"与"显式 0=纯继承档"；B 侧解析后总是显式传（兜底 1 档也 set）
        request.setPrevMirror(rolling.prevMirror())
                .setCorrectionIndex(rolling.correctionIndex())
                .setMirrorLookback(rolling.lookback())
                .setRecords(rolling.records())
                .setStatsFacts(rolling.statsFacts());

        owner.log.info("递归累计镜子输入组装：lookback={}, prevMirror={} 字符, correctionIndex={} 条, statsFacts={} 字符, lookbackTruncated={}",
                rolling.lookback(), rolling.prevMirror().length(),
                rolling.correctionIndex().isEmpty() ? 0 : rolling.correctionIndex().split("\n").length,
                rolling.statsFacts().length(), rolling.lookbackTruncated());

        MirrorProfileProto.GenerateProfileRequest base = request.build();
        try {
            return base.toBuilder()
                    .addAllGlossary(GlossaryProtoMapper.toProtoList(owner.glossaryService.confirmedForInjection(userId)))
                    .build();
        } catch (Exception e) {
            owner.log.warn("GenerateProfile 词表注入失败（按无词表继续），用户: {}, 原因: {}", userId, e.getMessage());
            return base;
        }
    }



    /**
     * 组装 GenerateProfileRequest 基础块（五维统计；llm_config 由 AiGrpcClient 补齐）
     *
     * <p>④ 待办统计实况不在 proto 结构化字段里重复——设计稿 §1 "④用现有统计字段，不加 proto"，
     * 另以 stats_facts（field 14，B 提案）携带渲染好的实况文本。</p>
     */
    protected MirrorProfileProto.GenerateProfileRequest buildRequest(ProfileStatsDTO stats) {

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
                    .setTitle(owner.nullToEmpty(t.getTitle()))
                    .setSummary(owner.nullToEmpty(t.getSummary()))
                    .setCreatedAt(owner.nullToEmpty(t.getCreatedAt())));
        }
        for (ProfileStatsDTO.LearningItemDTO l : stats.getLearnings()) {
            MirrorProfileProto.LearningItem.Builder lb = MirrorProfileProto.LearningItem.newBuilder()
                    .setRecordId(l.getRecordId() == null ? 0 : l.getRecordId())
                    .setTitle(owner.nullToEmpty(l.getTitle()))
                    .setSummary(owner.nullToEmpty(l.getSummary()))
                    .setCreatedAt(owner.nullToEmpty(l.getCreatedAt()));
            if (l.getKeywords() != null) {
                lb.addAllKeywords(l.getKeywords());
            }
            builder.addLearnings(lb);
        }
        for (ProfileStatsDTO.MoodStatDTO m : stats.getMoodStats()) {
            builder.addMoodStats(MirrorProfileProto.MoodStat.newBuilder()
                    .setMood(owner.nullToEmpty(m.getMood()))
                    .setCount(m.getCount() == null ? 0 : m.getCount())
                    .setPercentage(m.getPercentage() == null ? 0f : m.getPercentage()));
        }
        for (ProfileStatsDTO.KeywordStatDTO k : stats.getKeywords()) {
            builder.addKeywords(MirrorProfileProto.KeywordStat.newBuilder()
                    .setKeyword(owner.nullToEmpty(k.getKeyword()))
                    .setCount(k.getCount() == null ? 0 : k.getCount()));
        }

        MirrorProfileProto.ActiveTimeStats.Builder active = MirrorProfileProto.ActiveTimeStats.newBuilder()
                .setPeakHour(owner.nullToEmpty(stats.getPeakHour()));
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
}
