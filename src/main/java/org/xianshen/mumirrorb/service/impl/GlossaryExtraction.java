package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryCreateDTO;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryUpdateDTO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.xianshen.mumirrorb.service.impl.GlossaryServiceImpl.*;

/** GlossaryExtraction keeps the service entry points and transaction boundaries in GlossaryServiceImpl. */
final class GlossaryExtraction {
    private final GlossaryServiceImpl owner;
    GlossaryExtraction(GlossaryServiceImpl owner) { this.owner=owner; }


    // ==================== 抽取（第 3 节：B 调度，AI 实现 RPC） ====================

    @Transactional
    public List<UserTermVO> extractForUser(UUID userId) {
        return owner.doExtract(userId, owner.EXTRACT_WINDOW_DAYS);
    }


    public void extractScheduled(UUID userId) {
        try {
            List<UserTermVO> created = owner.doExtract(userId, owner.EXTRACT_WINDOW_DAYS);
            if (!created.isEmpty()) {
                owner.log.info("词典定时抽取完成，用户: {}, 新增候选 {} 条", userId, created.size());
            }
        } catch (Exception e) {
            // 防御（任务书要求）：抽取失败只打日志，绝不影响每日总结主流程
            owner.log.warn("词典定时抽取失败（不影响每日总结），用户: {}, 原因: {}", userId, e.getMessage());
        }
    }



    /**
     * 抽取主流程：语料收集 → 去重 → gRPC ExtractTerms（失败降级跳过）→ 候选落 pending
     *
     * @param windowDays 语料窗口（日常 14 天）
     * @return 本次新增 pending 候选词条卡（C5 F 契约：{candidates:[...]}；evidence/update 不计入）
     */
    protected List<UserTermVO> doExtract(UUID userId, int windowDays) {
        // 0. 未配置 LLM 直接跳过（与每日总结幂等口径一致）
        if (!owner.hasLlmConfig(userId)) {
            return List.of();
        }
        // 1. 语料：近 N 天真实用户日记 chunks——fix-batch B2（Y2）收口：
        // status='done' AND source='user'（不含系统总结/vault 产物/未确认记录）；
        // MP wrapper 无 join，按 record 白名单两段式预过滤，user_edited 优先（#3）
        OffsetDateTime since = LocalDate.now(owner.ZONE).minusDays(windowDays).atStartOfDay(owner.ZONE).toOffsetDateTime();
        List<Long> userRecordIds = owner.recordMapper.selectList(new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")
                .eq(Record::getStatus, RecordStatus.DONE)
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, since)
                .last("LIMIT " + owner.AUDIT_CORPUS_RECORD_LIMIT))
                .stream().map(Record::getId).toList();
        if (userRecordIds.isEmpty()) {
            return List.of();
        }
        List<Chunk> corpus = owner.chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getUserId, userId)
                .in(Chunk::getRecordId, userRecordIds)
                .eq(Chunk::getAiMemoryApproved, true)
                .isNotNull(Chunk::getSegment)
                .orderByDesc(Chunk::getUserEdited)
                .orderByDesc(Chunk::getCreatedAt)
                .last("LIMIT " + owner.CORPUS_LIMIT));
        if (corpus.isEmpty()) {
            return List.of();
        }
        // 2. 去重：近 30 天已处理（confirmed/dismissed/pending 已存在）的 term 跳过；dismissed 30 天后可重新浮现
        Set<String> known = owner.knownTermNames(userId);
        // 3. 组装 gRPC 请求（现有 confirmed 词条作合并依据）
        List<UserTerm> existingConfirmed = owner.termMapper.selectConfirmedTop(userId, owner.INJECTION_LIMIT);
        RecordProcessorProto.ExtractTermsRequest.Builder request =
                RecordProcessorProto.ExtractTermsRequest.newBuilder()
                        .setLlmConfig(owner.aiGrpcClient.buildLlmConfigFor(userId));
        for (Chunk chunk : corpus) {
            request.addChunks(owner.toProtoChunk(chunk));
        }
        for (UserTerm term : existingConfirmed) {
            request.addExistingTerms(owner.toProtoTerm(term));
        }

        // 4. 调 RPC（Python 未上线时 UNAVAILABLE → 降级路径）
        List<RecordProcessorProto.ExtractTermsReply.TermCandidate> candidates;
        try {
            RecordProcessorProto.ExtractTermsReply reply = owner.aiGrpcClient.extractTerms(userId, request.build());
            candidates = reply.getCandidatesList();
            owner.log.info("ExtractTerms 返回 {} 条候选，用户: {}", candidates.size(), userId);
        } catch (Exception e) {
            // 防御：Python 侧未上线/调用失败 → 打日志，本轮跳过（词表错了退化为普通检索，不是灾难 #0.2）
            owner.log.warn("ExtractTerms 调用失败，本轮抽取跳过（Python 侧可能未上线），用户: {}, 原因: {}",
                    userId, e.getMessage());
            return List.of();
        }

        // 5. 候选落库：new → pending 新行；evidence → 已有 pending 行计数 +1；update → 打回 pending 等确认
        OffsetDateTime now = OffsetDateTime.now(owner.ZONE);
        List<UserTermVO> created = new ArrayList<>();
        for (RecordProcessorProto.ExtractTermsReply.TermCandidate c : candidates) {
            if (c.getTerm() == null || c.getTerm().isBlank()) {
                continue;
            }
            String term = c.getTerm().trim();
            if (!"new".equals(c.getKind()) || known.contains(term)) {
                // evidence/update 只对已有词生效；update 打回 pending（第 3 节分级）
                if ("update".equals(c.getKind())) {
                    owner.applyUpdateCandidate(userId, term, c, known, now);
                } else if ("evidence".equals(c.getKind())) {
                    owner.applyEvidence(userId, term, c, now);
                }
                continue;
            }
            // 佐证归属校验（2026-09-23）：LLM 给的 chunk_id 必须真实存在且属于该用户，
            // 否则两列一起留空——宁可没有"查看原文"链接，不可跳到别人的记录上
            Chunk source = owner.ownedSourceChunk(userId, c.getSourceChunkId());
            UserTerm entity = UserTerm.builder()
                    .userId(userId)
                    .term(term)
                    .aliases(owner.normalizeAliases(c.getAliasesList()))
                    .description(c.getDescription())
                    .status("pending")
                    .queryHitCount(0)
                    .contentHitCount(1)
                    .lastSeenAt(now)
                    .sourceChunkId(source == null ? null : source.getId())
                    .sourceRecordId(source == null ? null : source.getRecordId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            owner.termMapper.insert(entity);
            known.add(term);
            created.add(owner.toVO(entity, c.getEvidence() == null || c.getEvidence().isBlank()
                    ? null : c.getEvidence()));
        }
        if (!created.isEmpty()) {
            owner.invalidateCache(userId);
        }
        return created;
    }



    /**
     * update 候选：confirmed 词解释过时 → 打回 pending + 新解释建议（保留原词与别名）；
     * fix-batch C7：不再把建议追加进 description（改存 lastSeenAt 供 F 展示合并/更新预填）——
     * description 保持用户原文语义，候选卡片按 status=pending + 新解释覆盖展示
     */
    protected void applyUpdateCandidate(UUID userId, String term,
                                     RecordProcessorProto.ExtractTermsReply.TermCandidate c,
                                     Set<String> known, OffsetDateTime now) {
        UserTerm existing = owner.termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing == null || !"confirmed".equals(existing.getStatus())) {
            return; // 只对 confirmed 打回；pending 词走 evidence 通道
        }
        existing.setStatus("pending");
        if (c.getDescription() != null && !c.getDescription().isBlank()) {
            existing.setDescription(c.getDescription()); // 新解释建议，确认时生效（覆盖，不追加——C7）
        }
        Chunk source = owner.ownedSourceChunk(userId, c.getSourceChunkId());
        if (source != null) {
            existing.setSourceChunkId(source.getId());
            existing.setSourceRecordId(source.getRecordId());
        }
        existing.setUpdatedAt(now);
        owner.termMapper.updateById(existing);
        owner.invalidateCache(userId);
        owner.log.info("漂移/update 候选打回 pending，词: {}, 用户: {}", term, userId);
    }



    /**
     * evidence 候选：已有 pending 词条证据加强（content_hit_count +1，刷新 last_seen_at 与佐证）
     */
    protected void applyEvidence(UUID userId, String term,
                               RecordProcessorProto.ExtractTermsReply.TermCandidate c, OffsetDateTime now) {
        UserTerm existing = owner.termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing == null || !"pending".equals(existing.getStatus())) {
            return;
        }
        existing.setContentHitCount((existing.getContentHitCount() == null ? 0 : existing.getContentHitCount()) + 1);
        existing.setLastSeenAt(now);
        Chunk source = owner.ownedSourceChunk(userId, c.getSourceChunkId());
        if (source != null) {
            existing.setSourceChunkId(source.getId());
            existing.setSourceRecordId(source.getRecordId());
        }
        existing.setUpdatedAt(now);
        owner.termMapper.updateById(existing);
    }



    // ==================== 内部工具 ====================

    protected boolean hasLlmConfig(UUID userId) {
        return owner.aiGrpcClient.hasLlmConfig(userId);
    }



    /**
     * 近 30 天已处理的 term 名集合（去重窗：confirmed/dismissed/pending 全算已处理）
     */
    protected Set<String> knownTermNames(UUID userId) {
        OffsetDateTime windowStart = LocalDate.now(owner.ZONE).minusDays(owner.DEDUP_WINDOW_DAYS).atStartOfDay(owner.ZONE).toOffsetDateTime();
        Set<String> names = new LinkedHashSet<>();
        for (UserTerm t : owner.termMapper.selectByUser(userId)) {
            // dismissed 超过 30 天 → 允许重新浮现（不进 known）
            if ("dismissed".equals(owner.statusOf(t.getStatus()))
                    && t.getUpdatedAt() != null && t.getUpdatedAt().isBefore(windowStart)) {
                continue;
            }
            names.add(t.getTerm());
        }
        return names;
    }
}
