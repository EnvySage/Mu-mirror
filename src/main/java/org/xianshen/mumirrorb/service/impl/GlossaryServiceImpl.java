package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
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

/**
 * 个人词典服务实现（lexicon-design.md 第 2/3/4/5c 节）
 *
 * <p>核心哲学落地：</p>
 * <ul>
 *   <li>pending 只展示不注入，confirmed 才生效（#0.1）</li>
 *   <li>注入 = confirmed 按 query_hit_count 排序 top 30 截断 + 60s 进程内缓存（#4）</li>
 *   <li>ExtractIntent query 侧匹配：term/alias 字符串包含，命中 query_hit_count++，
 *       只把命中的传 Python（省 prompt）；未命中传 top 高频词 grounding（调用方拼）</li>
 *   <li>抽取挂定时任务顺路，不新增用户等待路径（#0.5）</li>
 * </ul>
 *
 * <p><strong>ExtractTerms 降级策略</strong>（Python RPC 本轮由 AI Agent 实现，未上线时不能炸）：
 * gRPC 调用失败 → 落日志 → 降级为本地语料包含计数（term=语料高频专有词的保守近似只做
 * evidence 通道关闭，完全跳过本轮抽取），保证每日总结主流程零影响（任务书防御要求）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlossaryServiceImpl implements GlossaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 注入上限（lexicon-design.md 参数表：prompt 膨胀防线） */
    static final int INJECTION_LIMIT = 30;
    /** 缓存时长（参数表：60s，词条低频变） */
    static final long CACHE_TTL_MS = 60_000L;
    /** 抽取语料窗口（参数表：14 天） */
    static final int EXTRACT_WINDOW_DAYS = 14;
    /** 去重窗（参数表：30 天；dismissed 复活窗同值） */
    static final int DEDUP_WINDOW_DAYS = 30;
    /** 月度审计语料窗口（第 3 节：近 30 天含该词语料） */
    static final int AUDIT_WINDOW_DAYS = 30;
    /** 抽取语料上限（14 天 confirmed chunks，防止 prompt 爆炸） */
    static final int CORPUS_LIMIT = 200;
    /** 审计语料 record 扫描上限（先 record 后 chunk 两段式，防止 IN 列表爆炸） */
    static final int AUDIT_CORPUS_RECORD_LIMIT = 500;
    /** confirmed 卡片"近30天相关记录n条"统计上限（控制 ILIKE 成本） */
    private static final int RECENT_HITS_TERMS_LIMIT = 30;
    /** 降级抽取：语料包含计数达到该次数才生成候选（噪音防线） */
    static final int FALLBACK_MIN_HITS = 3;

    private final UserTermMapper termMapper;
    private final ChunkMapper chunkMapper;
    private final RecordMapper recordMapper;
    private final AiGrpcClient aiGrpcClient;

    /**
     * confirmed 注入列表缓存：userId → (加载时间, 词条列表)
     *
     * <p>进程内 60s TTL；writeThrough 在 confirm/dismiss/update 后主动失效本用户缓存。</p>
     */
    private final Map<UUID, CacheEntry> injectionCache = new ConcurrentHashMap<>();

    private record CacheEntry(long loadedAt, List<UserTermVO> terms) {
    }

    // ==================== 查询（5b 三组分好） ====================

    @Override
    @Transactional(readOnly = true)
    public List<UserTermVO> list(UUID userId) {
        List<UserTermVO> all = toVOList(termMapper.selectByUser(userId));
        attachRecentHitCounts(userId, all);
        return all;
    }

    @Override
    @Transactional(readOnly = true)
    public GlossaryGroupVO listGrouped(UUID userId) {
        List<UserTermVO> all = list(userId);
        List<UserTermVO> pending = new ArrayList<>();
        List<UserTermVO> confirmed = new ArrayList<>();
        List<UserTermVO> dismissed = new ArrayList<>();
        for (UserTermVO vo : all) {
            switch (statusOf(vo.getStatus())) {
                case "pending" -> pending.add(vo);
                case "confirmed" -> confirmed.add(vo);
                default -> dismissed.add(vo);
            }
        }
        return GlossaryGroupVO.builder()
                .pending(pending).confirmed(confirmed).dismissed(dismissed)
                .build();
    }

    // ==================== CRUD（5c） ====================

    @Override
    @Transactional
    public UserTermVO create(UUID userId, GlossaryCreateDTO dto) {
        String term = dto.getTerm().trim();
        // 唯一约束（user_id, term）先行校验，报可读错误而非 DB 异常
        UserTerm existing = termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已存在：" + term);
        }
        UserTerm entity = UserTerm.builder()
                .userId(userId)
                .term(term)
                .aliases(normalizeAliases(dto.getAliases()))
                .description(dto.getDescription().trim())
                .status("confirmed") // 手动新增 = 用户亲口教，直接生效（5c）
                .queryHitCount(0)
                .contentHitCount(0)
                .lastConfirmedAt(OffsetDateTime.now(ZONE))
                .createdAt(OffsetDateTime.now(ZONE))
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        termMapper.insert(entity);
        invalidateCache(userId);
        log.info("词条手动新增（confirmed），用户: {}, 词: {}", userId, term);
        return toVO(entity, null);
    }

    @Override
    @Transactional
    public UserTermVO update(Long id, GlossaryUpdateDTO dto, UUID userId) {
        UserTerm entity = requireOwned(id, userId);
        String term = dto.getTerm().trim();
        if (!term.equals(entity.getTerm())) {
            UserTerm dup = termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                    .eq(UserTerm::getUserId, userId)
                    .eq(UserTerm::getTerm, term));
            if (dup != null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "词条已存在：" + term);
            }
            entity.setTerm(term);
        }
        entity.setAliases(normalizeAliases(dto.getAliases()));
        entity.setDescription(dto.getDescription().trim());
        // 编辑解释视为一次重新确认：刷新 last_confirmed_at（"最后确认于x日"口径）
        if ("confirmed".equals(entity.getStatus())) {
            entity.setLastConfirmedAt(OffsetDateTime.now(ZONE));
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZONE));
        termMapper.updateById(entity);
        invalidateCache(userId);
        return toVO(entity, null);
    }

    @Override
    @Transactional
    public void delete(Long id, UUID userId) {
        UserTerm entity = requireOwned(id, userId);
        termMapper.deleteById(entity.getId());
        invalidateCache(userId);
        log.info("词条已删除，ID: {}, 词: {}, 用户: {}", id, entity.getTerm(), userId);
    }

    // ==================== 状态机（confirm / dismiss） ====================

    @Override
    @Transactional
    public UserTermVO confirm(Long id, UUID userId, String newDescription, List<String> newAliases) {
        UserTerm entity = requireOwned(id, userId);
        String from = statusOf(entity.getStatus());
        if ("confirmed".equals(from)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已是 confirmed 状态");
        }
        // update 候选确认：覆盖为新解释建议；合并建议确认：并入别名
        if (newDescription != null && !newDescription.isBlank()) {
            entity.setDescription(newDescription.trim());
        }
        if (newAliases != null && !newAliases.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(norm(entity.getAliases()));
            merged.addAll(normalizeAliases(newAliases));
            entity.setAliases(new ArrayList<>(merged));
        }
        entity.setStatus("confirmed");
        entity.setLastConfirmedAt(OffsetDateTime.now(ZONE));
        entity.setUpdatedAt(OffsetDateTime.now(ZONE));
        termMapper.updateById(entity);
        invalidateCache(userId);
        log.info("词条已确认（{} → confirmed），ID: {}, 词: {}, 用户: {}", from, id, entity.getTerm(), userId);
        return toVO(entity, null);
    }

    @Override
    @Transactional
    public UserTermVO dismiss(Long id, UUID userId) {
        UserTerm entity = requireOwned(id, userId);
        if ("dismissed".equals(statusOf(entity.getStatus()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已是 dismissed 状态");
        }
        // dismissed 不删行（#1：30 天后可重新浮现），沉底
        entity.setStatus("dismissed");
        entity.setUpdatedAt(OffsetDateTime.now(ZONE));
        termMapper.updateById(entity);
        invalidateCache(userId);
        log.info("词条已忽略（{} → dismissed），ID: {}, 词: {}, 用户: {}", statusOf(entity.getStatus()), id, entity.getTerm(), userId);
        return toVO(entity, null);
    }

    // ==================== 注入（第 4 节） ====================

    @Override
    @Transactional(readOnly = true)
    public List<UserTermVO> confirmedForInjection(UUID userId) {
        CacheEntry entry = injectionCache.get(userId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < CACHE_TTL_MS) {
            return entry.terms();
        }
        List<UserTermVO> terms = toVOList(termMapper.selectConfirmedTop(userId, INJECTION_LIMIT));
        injectionCache.put(userId, new CacheEntry(now, terms));
        return terms;
    }

    @Override
    @Transactional
    public List<UserTermVO> matchQueryTerms(UUID userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query;
        List<UserTermVO> matched = new ArrayList<>();
        for (UserTermVO vo : confirmedForInjection(userId)) {
            if (matches(vo, q)) {
                matched.add(vo);
                // 命中即计数（query 侧命中管注入优先级；flush 落库）
                termMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserTerm>()
                        .eq(UserTerm::getId, vo.getId())
                        .setSql("query_hit_count = COALESCE(query_hit_count, 0) + 1"));
            }
        }
        if (!matched.isEmpty()) {
            invalidateCache(userId); // hit_count 变了，缓存里的排序过期
            log.debug("ExtractIntent query 侧命中 {} 个词条，用户: {}", matched.size(), userId);
        }
        return matched;
    }

    /**
     * term/alias 与 query 字符串包含匹配（几十条数据，不搞花活，#4）
     */
    public static boolean matches(UserTermVO vo, String query) {
        if (vo.getTerm() != null && !vo.getTerm().isBlank() && query.contains(vo.getTerm())) {
            return true;
        }
        if (vo.getAliases() != null) {
            for (String alias : vo.getAliases()) {
                if (alias != null && !alias.isBlank() && query.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 抽取（第 3 节：B 调度，AI 实现 RPC） ====================

    @Override
    @Transactional
    public List<UserTermVO> extractForUser(UUID userId) {
        return doExtract(userId, EXTRACT_WINDOW_DAYS);
    }

    @Override
    public void extractScheduled(UUID userId) {
        try {
            List<UserTermVO> created = doExtract(userId, EXTRACT_WINDOW_DAYS);
            if (!created.isEmpty()) {
                log.info("词典定时抽取完成，用户: {}, 新增候选 {} 条", userId, created.size());
            }
        } catch (Exception e) {
            // 防御（任务书要求）：抽取失败只打日志，绝不影响每日总结主流程
            log.warn("词典定时抽取失败（不影响每日总结），用户: {}, 原因: {}", userId, e.getMessage());
        }
    }

    /**
     * 抽取主流程：语料收集 → 去重 → gRPC ExtractTerms（失败降级跳过）→ 候选落 pending
     *
     * @param windowDays 语料窗口（日常 14 天）
     * @return 本次新增 pending 候选词条卡（C5 F 契约：{candidates:[...]}；evidence/update 不计入）
     */
    private List<UserTermVO> doExtract(UUID userId, int windowDays) {
        // 0. 未配置 LLM 直接跳过（与每日总结幂等口径一致）
        if (!hasLlmConfig(userId)) {
            return List.of();
        }
        // 1. 语料：近 N 天真实用户日记 chunks——fix-batch B2（Y2）收口：
        // status='done' AND source='user'（不含系统总结/vault 产物/未确认记录）；
        // MP wrapper 无 join，按 record 白名单两段式预过滤，user_edited 优先（#3）
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(windowDays).atStartOfDay(ZONE).toOffsetDateTime();
        List<Long> userRecordIds = recordMapper.selectList(new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")
                .eq(Record::getStatus, RecordStatus.DONE)
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, since)
                .last("LIMIT " + AUDIT_CORPUS_RECORD_LIMIT))
                .stream().map(Record::getId).toList();
        if (userRecordIds.isEmpty()) {
            return List.of();
        }
        List<Chunk> corpus = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getUserId, userId)
                .in(Chunk::getRecordId, userRecordIds)
                .orderByDesc(Chunk::getUserEdited)
                .orderByDesc(Chunk::getCreatedAt)
                .last("LIMIT " + CORPUS_LIMIT));
        if (corpus.isEmpty()) {
            return List.of();
        }
        // 2. 去重：近 30 天已处理（confirmed/dismissed/pending 已存在）的 term 跳过；dismissed 30 天后可重新浮现
        Set<String> known = knownTermNames(userId);
        // 3. 组装 gRPC 请求（现有 confirmed 词条作合并依据）
        List<UserTerm> existingConfirmed = termMapper.selectConfirmedTop(userId, INJECTION_LIMIT);
        RecordProcessorProto.ExtractTermsRequest.Builder request =
                RecordProcessorProto.ExtractTermsRequest.newBuilder()
                        .setLlmConfig(aiGrpcClient.buildLlmConfigFor(userId));
        for (Chunk chunk : corpus) {
            request.addChunks(toProtoChunk(chunk));
        }
        for (UserTerm term : existingConfirmed) {
            request.addExistingTerms(toProtoTerm(term));
        }

        // 4. 调 RPC（Python 未上线时 UNAVAILABLE → 降级路径）
        List<RecordProcessorProto.ExtractTermsReply.TermCandidate> candidates;
        try {
            RecordProcessorProto.ExtractTermsReply reply = aiGrpcClient.extractTerms(userId, request.build());
            candidates = reply.getCandidatesList();
            log.info("ExtractTerms 返回 {} 条候选，用户: {}", candidates.size(), userId);
        } catch (Exception e) {
            // 防御：Python 侧未上线/调用失败 → 打日志，本轮跳过（词表错了退化为普通检索，不是灾难 #0.2）
            log.warn("ExtractTerms 调用失败，本轮抽取跳过（Python 侧可能未上线），用户: {}, 原因: {}",
                    userId, e.getMessage());
            return List.of();
        }

        // 5. 候选落库：new → pending 新行；evidence → 已有 pending 行计数 +1；update → 打回 pending 等确认
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        List<UserTermVO> created = new ArrayList<>();
        for (RecordProcessorProto.ExtractTermsReply.TermCandidate c : candidates) {
            if (c.getTerm() == null || c.getTerm().isBlank()) {
                continue;
            }
            String term = c.getTerm().trim();
            if (!"new".equals(c.getKind()) || known.contains(term)) {
                // evidence/update 只对已有词生效；update 打回 pending（第 3 节分级）
                if ("update".equals(c.getKind())) {
                    applyUpdateCandidate(userId, term, c, known, now);
                } else if ("evidence".equals(c.getKind())) {
                    applyEvidence(userId, term, c, now);
                }
                continue;
            }
            UserTerm entity = UserTerm.builder()
                    .userId(userId)
                    .term(term)
                    .aliases(normalizeAliases(c.getAliasesList()))
                    .description(c.getDescription())
                    .status("pending")
                    .queryHitCount(0)
                    .contentHitCount(1)
                    .lastSeenAt(now)
                    .sourceChunkId(c.getSourceChunkId() > 0 ? c.getSourceChunkId() : null)
                    .sourceRecordId(resolveSourceRecordId(c.getSourceChunkId(), 0))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            termMapper.insert(entity);
            known.add(term);
            created.add(toVO(entity, c.getEvidence() == null || c.getEvidence().isBlank()
                    ? null : c.getEvidence()));
        }
        if (!created.isEmpty()) {
            invalidateCache(userId);
        }
        return created;
    }

    /**
     * update 候选：confirmed 词解释过时 → 打回 pending + 新解释建议（保留原词与别名）；
     * fix-batch C7：不再把建议追加进 description（改存 lastSeenAt 供 F 展示合并/更新预填）——
     * description 保持用户原文语义，候选卡片按 status=pending + 新解释覆盖展示
     */
    private void applyUpdateCandidate(UUID userId, String term,
                                     RecordProcessorProto.ExtractTermsReply.TermCandidate c,
                                     Set<String> known, OffsetDateTime now) {
        UserTerm existing = termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing == null || !"confirmed".equals(existing.getStatus())) {
            return; // 只对 confirmed 打回；pending 词走 evidence 通道
        }
        existing.setStatus("pending");
        if (c.getDescription() != null && !c.getDescription().isBlank()) {
            existing.setDescription(c.getDescription()); // 新解释建议，确认时生效（覆盖，不追加——C7）
        }
        if (c.getSourceChunkId() > 0) {
            existing.setSourceChunkId(c.getSourceChunkId());
            existing.setSourceRecordId(resolveSourceRecordId(c.getSourceChunkId(), 0));
        }
        existing.setUpdatedAt(now);
        termMapper.updateById(existing);
        invalidateCache(userId);
        log.info("漂移/update 候选打回 pending，词: {}, 用户: {}", term, userId);
    }

    /**
     * evidence 候选：已有 pending 词条证据加强（content_hit_count +1，刷新 last_seen_at 与佐证）
     */
    private void applyEvidence(UUID userId, String term,
                               RecordProcessorProto.ExtractTermsReply.TermCandidate c, OffsetDateTime now) {
        UserTerm existing = termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing == null || !"pending".equals(existing.getStatus())) {
            return;
        }
        existing.setContentHitCount((existing.getContentHitCount() == null ? 0 : existing.getContentHitCount()) + 1);
        existing.setLastSeenAt(now);
        if (c.getSourceChunkId() > 0) {
            existing.setSourceChunkId(c.getSourceChunkId());
            existing.setSourceRecordId(resolveSourceRecordId(c.getSourceChunkId(), 0));
        }
        existing.setUpdatedAt(now);
        termMapper.updateById(existing);
    }

    // ==================== 月度维护（第 3 节：合并 + 漂移审计） ====================

    @Override
    public void monthlyMaintenance(UUID userId) {
        try {
            doMonthlyMaintenance(userId);
        } catch (Exception e) {
            log.warn("词典月度维护失败（不影响月度画像主流程），用户: {}, 原因: {}", userId, e.getMessage());
        }
    }

    /**
     * 月度两项（失败互不影响）：
     * ① 词条合并：term 互为子串的重复词（"RAG那个设计" 与 "论文"同指场景的保守近似）
     *    生成合并候选（短词并入长词的别名），落 pending 复核
     * ② 漂移审计：confirmed 词解释 vs 近 30 天语料包含度——词已不再出现于语料 →
     *    打回 pending（语义一致性判断由 ExtractTerms RPC 的 update 分支承担，Python 侧实现；
     *    此处做无 LLM 的确定性兜底：语料 30 天 0 命中的 confirmed 词视为"疑似过时"打回）
     */
    private void doMonthlyMaintenance(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);

        // ① 词条合并：互为包含的 confirmed 词对（保守近似：完全包含关系才建议合并）
        List<UserTerm> confirmed = termMapper.selectConfirmedTop(userId, 100);
        int merged = 0;
        for (UserTerm a : confirmed) {
            for (UserTerm b : confirmed) {
                if (a.getId().equals(b.getId())) {
                    continue;
                }
                String ta = a.getTerm(), tb = b.getTerm();
                if (ta.length() > tb.length() && ta.contains(tb) && !isAliasOf(a, tb)) {
                    // b 是 a 的子串：建议把 b 并为 a 的别名（b 保留待用户确认合并建议）
                    if (ensureMergeCandidate(userId, b, a, now)) {
                        merged++;
                    }
                }
            }
        }

        // ② 漂移审计（确定性兜底）：confirmed 词近 30 天语料 0 命中 → 打回 pending
        // fix-batch B2（Y2）：审计语料同口径收口（status='done' AND source='user'）
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(AUDIT_WINDOW_DAYS).atStartOfDay(ZONE).toOffsetDateTime();
        List<Long> userRecordIds = recordMapper.selectList(new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")
                .eq(Record::getStatus, RecordStatus.DONE)
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, since)
                .last("LIMIT " + AUDIT_CORPUS_RECORD_LIMIT))
                .stream().map(Record::getId).toList();
        List<Chunk> corpus = userRecordIds.isEmpty() ? List.of()
                : chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getUserId, userId)
                        .in(Chunk::getRecordId, userRecordIds)
                        .last("LIMIT " + CORPUS_LIMIT));
        int drifted = 0;
        for (UserTerm term : confirmed) {
            if (!corpusMentions(corpus, term)) {
                // 语料长期不出现：疑似漂移/过时。打回 pending 主动递到用户面前（#3：漂移最多活一个月）
                term.setStatus("pending");
                term.setUpdatedAt(now);
                termMapper.updateById(term);
                drifted++;
            }
        }
        if (merged > 0 || drifted > 0) {
            invalidateCache(userId);
        }
        log.info("词典月度维护完成，用户: {}: 合并建议 {} 条, 漂移打回 {} 条", userId, merged, drifted);
    }

    private boolean isAliasOf(UserTerm term, String name) {
        return term.getAliases() != null && term.getAliases().contains(name);
    }

    /**
     * 合并建议：给"被包含的短词"打 pending + 建议并入的别名预填进 aliases（fix-batch C7：
     * 不再追加进 description——描述保持用户原文，别名候选由 confirm 的 newAliases 并入语义天然支持）
     *
     * @return 是否新建了建议
     */
    private boolean ensureMergeCandidate(UUID userId, UserTerm shortTerm, UserTerm longTerm, OffsetDateTime now) {
        // 短词已是 pending（有未处理建议）不重复堆
        if (!"confirmed".equals(shortTerm.getStatus())) {
            return false;
        }
        shortTerm.setStatus("pending");
        // C7：合并建议目标词预填进 aliases（用户确认时 confirm 的 newAliases 通道并入；
        // 忽略则 dismiss，aliases 不留痕）
        Set<String> prefill = new LinkedHashSet<>(norm(shortTerm.getAliases()));
        prefill.add(longTerm.getTerm());
        shortTerm.setAliases(new ArrayList<>(prefill));
        shortTerm.setUpdatedAt(now);
        termMapper.updateById(shortTerm);
        log.info("词条合并建议已生成：{} → {}（pending 复核，别名预填 {}），用户: {}",
                shortTerm.getTerm(), longTerm.getTerm(), longTerm.getTerm(), userId);
        return true;
    }

    /**
     * 语料是否提及该词（term 或任一别名出现在 segment/content）
     */
    static boolean corpusMentions(List<Chunk> corpus, UserTerm term) {
        for (Chunk chunk : corpus) {
            String text = (chunk.getSegment() == null || chunk.getSegment().isBlank()
                    ? "" : chunk.getSegment()) + " " + (chunk.getContent() == null ? "" : chunk.getContent());
            if (text.contains(term.getTerm())) {
                return true;
            }
            if (term.getAliases() != null) {
                for (String alias : term.getAliases()) {
                    if (alias != null && !alias.isBlank() && text.contains(alias)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==================== 内部工具 ====================

    private boolean hasLlmConfig(UUID userId) {
        return aiGrpcClient.hasLlmConfig(userId);
    }

    /**
     * 近 30 天已处理的 term 名集合（去重窗：confirmed/dismissed/pending 全算已处理）
     */
    private Set<String> knownTermNames(UUID userId) {
        OffsetDateTime windowStart = LocalDate.now(ZONE).minusDays(DEDUP_WINDOW_DAYS).atStartOfDay(ZONE).toOffsetDateTime();
        Set<String> names = new LinkedHashSet<>();
        for (UserTerm t : termMapper.selectByUser(userId)) {
            // dismissed 超过 30 天 → 允许重新浮现（不进 known）
            if ("dismissed".equals(statusOf(t.getStatus()))
                    && t.getUpdatedAt() != null && t.getUpdatedAt().isBefore(windowStart)) {
                continue;
            }
            names.add(t.getTerm());
        }
        return names;
    }

    /**
     * confirmed 卡片"近30天相关记录n条"（仅已生效词，前 30 条控制成本）
     */
    private void attachRecentHitCounts(UUID userId, List<UserTermVO> terms) {
        int budget = RECENT_HITS_TERMS_LIMIT;
        OffsetDateTime since = LocalDate.now(ZONE).minusDays(AUDIT_WINDOW_DAYS).atStartOfDay(ZONE).toOffsetDateTime();
        for (UserTermVO vo : terms) {
            if (!"confirmed".equals(statusOf(vo.getStatus())) || budget-- <= 0) {
                continue;
            }
            List<String> patterns = new ArrayList<>();
            if (vo.getTerm() != null && !vo.getTerm().isBlank()) {
                patterns.add("%" + escapeLike(vo.getTerm()) + "%");
            }
            if (vo.getAliases() != null) {
                for (String alias : vo.getAliases()) {
                    if (alias != null && !alias.isBlank()) {
                        patterns.add("%" + escapeLike(alias) + "%");
                    }
                }
            }
            if (patterns.isEmpty()) {
                continue;
            }
            Integer cnt = termMapper.countRecentRecordHits(userId, patterns, since);
            vo.setQueryHitCount(vo.getQueryHitCount() == null ? 0 : vo.getQueryHitCount());
            vo.setContentHitCount(cnt == null ? 0 : cnt);
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private UserTerm requireOwned(Long id, UUID userId) {
        UserTerm entity = termMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "词条不存在");
        }
        return entity;
    }

    private static String statusOf(String status) {
        return status == null ? "pending" : status.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析佐证 record id（F 契约：跳记录详情用 record id）
     *
     * <p>proto TermCandidate 只有 source_chunk_id（chunk → record 归属在 B 侧唯一真源）；
     * 由 chunk 反查 record_id，chunk 不存在时为 null。</p>
     */
    private Long resolveSourceRecordId(long sourceChunkId, long fallbackRecordId) {
        if (fallbackRecordId > 0) {
            return fallbackRecordId;
        }
        if (sourceChunkId > 0) {
            Chunk chunk = chunkMapper.selectById(sourceChunkId);
            if (chunk != null) {
                return chunk.getRecordId();
            }
        }
        return null;
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        if (aliases == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String a : aliases) {
            if (a != null && !a.isBlank()) {
                set.add(a.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private static List<String> norm(List<String> aliases) {
        return aliases == null ? List.of() : aliases;
    }

    private List<UserTermVO> toVOList(List<UserTerm> entities) {
        List<UserTermVO> result = new ArrayList<>(entities.size());
        for (UserTerm e : entities) {
            result.add(toVO(e, null));
        }
        return result;
    }

    private static UserTermVO toVO(UserTerm e, String evidence) {
        return UserTermVO.builder()
                .id(e.getId())
                .term(e.getTerm())
                .aliases(e.getAliases())
                .description(e.getDescription())
                .status(e.getStatus())
                .queryHitCount(e.getQueryHitCount())
                .contentHitCount(e.getContentHitCount())
                .lastConfirmedAt(e.getLastConfirmedAt())
                .lastSeenAt(e.getLastSeenAt())
                .sourceChunkId(e.getSourceChunkId())
                .sourceRecordId(e.getSourceRecordId())
                .evidence(evidence)
                .createdAt(e.getCreatedAt())
                .build();
    }

    /**
     * UserTerm → proto GlossaryTerm（confirmed_at 格式 yyyy-MM-dd，Python prompt 标注"x月确认"）
     */
    public static CommonProto.GlossaryTerm toProtoTerm(UserTerm term) {
        CommonProto.GlossaryTerm.Builder builder = CommonProto.GlossaryTerm.newBuilder()
                .setTerm(nullSafe(term.getTerm()))
                .setDescription(nullSafe(term.getDescription()));
        if (term.getAliases() != null) {
            builder.addAllAliases(term.getAliases());
        }
        if (term.getLastConfirmedAt() != null) {
            builder.setConfirmedAt(term.getLastConfirmedAt().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return builder.build();
    }

    /**
     * Chunk → proto ChunkDTO（定义在 common.proto；metadata JSONB 展平为具名字段）
     */
    public static CommonProto.ChunkDTO toProtoChunk(Chunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
        CommonProto.ChunkDTO.Builder builder = CommonProto.ChunkDTO.newBuilder()
                .setChunkId(chunk.getId() == null ? 0 : chunk.getId())
                .setRecordId(chunk.getRecordId() == null ? 0 : chunk.getRecordId())
                .setSegment(nullSafe(chunk.getSegment()))
                .setContent(nullSafe(chunk.getContent()))
                .setCreatedAt(chunk.getCreatedAt() == null
                        ? "" : chunk.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")))
                .setUserEdited(Boolean.TRUE.equals(chunk.getUserEdited()));
        Object title = metadata.get("title");
        if (title != null) {
            builder.setTitle(String.valueOf(title));
        }
        Object summary = metadata.get("summary");
        if (summary != null) {
            builder.setSummary(String.valueOf(summary));
        }
        Object contentType = metadata.get("contentType");
        if (contentType != null) {
            builder.setContentType(String.valueOf(contentType));
        }
        Object taskStatus = metadata.get("taskStatus");
        if (taskStatus != null) {
            builder.setTaskStatus(String.valueOf(taskStatus));
        }
        addStrings(builder::addMoods, metadata.get("mood"));
        addStrings(builder::addKeywords, metadata.get("keywords"));
        return builder.build();
    }

    private static void addStrings(java.util.function.Consumer<String> adder, Object value) {
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    adder.accept(String.valueOf(o));
                }
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private void invalidateCache(UUID userId) {
        injectionCache.remove(userId);
    }
}
