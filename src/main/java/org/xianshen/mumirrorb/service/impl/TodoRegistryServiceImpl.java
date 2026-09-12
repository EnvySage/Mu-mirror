package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.TodoRegistryLinkMapper;
import org.xianshen.mumirrorb.mapper.TodoRegistryMapper;
import org.xianshen.mumirrorb.mapper.TodoSuggestionMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistry;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistryLink;
import org.xianshen.mumirrorb.pojo.DO.TodoSuggestion;
import org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO;
import org.xianshen.mumirrorb.pojo.VO.TodoChainVO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;
import org.xianshen.mumirrorb.service.TodoRegistryService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 待办登记服务实现（todo-registry-design.md §3/§4-B）
 *
 * <p>核心裁决落地：</p>
 * <ul>
 *   <li>真源唯一（#33）：状态变更一律双写——chunk.metadata.taskStatus（真源，定向 SET 不触发
 *       classified_segment 重置，5.3 既有语义：元数据变更不影响文本状态机）
 *       + registry.current_status（物化索引）</li>
 *   <li>关联是用户背书的产物（#22 一脉）：origin 登记时落，evidence 只在 resolve confirmed 落</li>
 *   <li>dismissed 永久静默（#5）：同一 evidence chunk 不再提示；新证据（新 chunk）可再建议</li>
 *   <li>完成时刻 closed_at 用 Asia/Shanghai（ZONE 模式照 VaultServiceImpl）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoRegistryServiceImpl implements TodoRegistryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 注入上限（§3.2：判别期 prompt 膨胀防线） */
    static final int HINT_LIMIT = 20;
    /** 判别期原始片段摘要截断（§3.2：100 字符） */
    static final int EXCERPT_LIMIT = 100;
    /** 建议卡片段展示截断 */
    static final int CARD_EXCERPT_LIMIT = 160;
    /** 证据链片段摘录截断（GET /todos/open-chain 契约：60 字符） */
    static final int CHAIN_EXCERPT_LIMIT = 60;
    /** 证据链返回上限（侧栏全量口径；列表侧传大值，同 GET /todos 不限 20 的语义） */
    static final int CHAIN_LIMIT = 200;
    /** 三态合法值 */
    private static final Set<String> VALID_STATUSES = Set.of("not_started", "in_progress", "completed");

    private final TodoRegistryMapper registryMapper;
    private final TodoRegistryLinkMapper linkMapper;
    private final TodoSuggestionMapper suggestionMapper;
    private final ChunkMapper chunkMapper;
    private final RecordMapper recordMapper;

    // ==================== 登记期（§3.1 零门禁） ====================

    @Override
    @Transactional
    public int registerFromRecord(Long recordId, UUID userId) {
        // 记录归属校验（confirmReview 已校验过；独立调用路径防御）
        Record record = recordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return 0;
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getRecordId, recordId));
        int created = 0;
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata();
            String contentType = metadata == null ? null : str(metadata.get("contentType"));
            // 只登记 todo/plan 片段（裁决口径：待办/计划类才进登记表）
            if (!"todo".equals(contentType) && !"plan".equals(contentType)) {
                continue;
            }
            // 幂等：source_chunk_id 已登记跳过（重复 confirm / retry 重跑不重复建行）
            Long existing = registryMapper.selectCount(new LambdaQueryWrapper<TodoRegistry>()
                    .eq(TodoRegistry::getSourceChunkId, chunk.getId()));
            if (existing != null && existing > 0) {
                continue;
            }
            String title = firstNonBlank(
                    metadata == null ? null : str(metadata.get("title")),
                    trunc(chunk.getSegment(), 40), "未命名待办");
            String taskStatus = normalizeStatus(
                    metadata == null ? null : str(metadata.get("taskStatus")));
            TodoRegistry entity = TodoRegistry.builder()
                    .userId(userId)
                    .title(trunc(title, 100))
                    .currentStatus(taskStatus)
                    .sourceChunkId(chunk.getId())
                    .createdAt(OffsetDateTime.now(ZONE))
                    .updatedAt(OffsetDateTime.now(ZONE))
                    .build();
            registryMapper.insert(entity);
            // origin link（登记时原始片段，UNIQUE(todo_id, chunk_id)）
            linkMapper.insert(TodoRegistryLink.builder()
                    .todoId(entity.getId())
                    .chunkId(chunk.getId())
                    .relation("origin")
                    .createdAt(OffsetDateTime.now(ZONE))
                    .build());
            created++;
            log.info("待办已登记：todo={}, title={}, status={}, chunk={}, 用户={}",
                    entity.getId(), title, taskStatus, chunk.getId(), userId);
        }
        if (created > 0) {
            log.info("记录 {} 待办登记完成：新增 {} 条，用户: {}", recordId, created, userId);
        }
        return created;
    }

    // ==================== 判别期（§3.2） ====================

    @Override
    @Transactional(readOnly = true)
    public List<TodoRegistryDTO.TodoItem> openTodosForHint(UUID userId) {
        return registryMapper.selectOpenTodos(userId, HINT_LIMIT);
    }

    @Override
    @Transactional
    public void suggestFromChunk(UUID userId, Chunk evidenceChunk, RecordProcessorProto.TodoRef todoRef) {
        if (todoRef == null || todoRef.getTodoId() <= 0) {
            return; // 无引用（旧 Python 不回填 / LLM 未判出）——不触发建议
        }
        if (evidenceChunk == null || evidenceChunk.getId() == null) {
            return;
        }
        // 建议状态归一：非法值丢弃（脏值防线；TodoRef 语义上只可能三态）
        String suggested = normalizeStatus(todoRef.getSuggestedStatus());
        if (suggested == null) {
            log.warn("待办建议丢弃：suggested_status 非法，chunk={}, todo={}",
                    evidenceChunk.getId(), todoRef.getTodoId());
            return;
        }
        TodoRegistry todo = registryMapper.selectById(todoRef.getTodoId());
        if (todo == null || !todo.getUserId().equals(userId)) {
            return; // 清单外/他人 todo：静默丢弃（不炸分类主流程）
        }
        if ("completed".equals(todo.getCurrentStatus())) {
            return; // 已完成的不再建议（§3.2：只对未完成项判别）
        }
        // 去重：同 (todo, evidence_chunk) 已有 pending / confirmed 不重复落；
        // dismissed 永久静默（裁决 #5：同一证据不再提示）
        Long sameEvidence = suggestionMapper.selectCount(new LambdaQueryWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getTodoId, todo.getId())
                .eq(TodoSuggestion::getEvidenceChunkId, evidenceChunk.getId()));
        if (sameEvidence != null && sameEvidence > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        suggestionMapper.insert(TodoSuggestion.builder()
                .userId(userId)
                .todoId(todo.getId())
                .evidenceChunkId(evidenceChunk.getId())
                .suggestedStatus(suggested)
                .status("pending")
                .createdAt(now)
                .build());
        log.info("待办建议已产生（pending）：todo={}, evidence chunk={}, 建议={}, 用户={}",
                todo.getId(), evidenceChunk.getId(), suggested, userId);
    }

    // ==================== 裁决期（§3.3 用户主权） ====================

    @Override
    @Transactional
    public void resolve(Long suggestionId, UUID userId, String action, String status) {
        TodoSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null || !suggestion.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "建议不存在");
        }
        if (!"pending".equals(suggestion.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该建议已处理过");
        }
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (act) {
            case "confirmed" -> doConfirm(suggestion, userId, status);
            case "dismissed" -> doDismiss(suggestion);
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "action 取值非法（confirmed/dismissed）");
        }
    }

    /**
     * 确认：事务内三写——① chunk.metadata.taskStatus（真源，定向改）② registry.current_status
     * 同步 + completed 时 closed_at ③ evidence 关联落库（用户背书才落）④ 建议行 confirmed
     */
    private void doConfirm(TodoSuggestion suggestion, UUID userId, String status) {
        String newStatus = status != null && !status.isBlank()
                ? normalizeStatus(status) : suggestion.getSuggestedStatus();
        if (newStatus == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "status 取值非法（not_started/in_progress/completed）");
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Chunk evidenceChunk = chunkMapper.selectById(suggestion.getEvidenceChunkId());

        // ① chunk.metadata.taskStatus（真源；定向 SET 不触发 classified_segment 重置——
        //    元数据变更不影响文本状态机，5.3 既有语义）
        patchChunkTaskStatus(suggestion.getEvidenceChunkId(), newStatus);

        // ② registry.current_status 同步 + completed 时 closed_at
        applyRegistryStatus(suggestion.getTodoId(), newStatus, now);

        // ③ evidence 关联（用户背书才落；UNIQUE(todo_id, chunk_id) 已有 origin 时跳过）
        if (evidenceChunk != null
                && linkMapper.selectOneByTodoAndChunk(suggestion.getTodoId(), evidenceChunk.getId()) == null) {
            linkMapper.insert(TodoRegistryLink.builder()
                    .todoId(suggestion.getTodoId())
                    .chunkId(evidenceChunk.getId())
                    .relation("evidence")
                    .createdAt(now)
                    .build());
        }

        // ④ 建议行 confirmed
        suggestionMapper.update(null, new LambdaUpdateWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getId, suggestion.getId())
                .set(TodoSuggestion::getStatus, "confirmed")
                .set(TodoSuggestion::getResolvedAt, now));
        log.info("待办建议已确认：todo={}, chunk={}, 状态={}, 用户={}",
                suggestion.getTodoId(), suggestion.getEvidenceChunkId(), newStatus, userId);
    }

    /**
     * 忽略：建议行 dismissed（永久静默），todo 不动，关联不落
     */
    private void doDismiss(TodoSuggestion suggestion) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        suggestionMapper.update(null, new LambdaUpdateWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getId, suggestion.getId())
                .set(TodoSuggestion::getStatus, "dismissed")
                .set(TodoSuggestion::getResolvedAt, now));
        log.info("待办建议已忽略（永久静默）：todo={}, chunk={}, 用户={}",
                suggestion.getTodoId(), suggestion.getUserId(), suggestion.getEvidenceChunkId());
    }

    @Override
    @Transactional
    public void setStatusDirectly(Long todoId, UUID userId, String newStatus) {
        String status = normalizeStatus(newStatus);
        if (status == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "status 取值非法（not_started/in_progress/completed）");
        }
        TodoRegistry todo = registryMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "待办不存在");
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        // 双写：真源（source chunk）+ 物化索引；source chunk 已删（orphan）时只改 registry
        patchChunkTaskStatus(todo.getSourceChunkId(), status);
        applyRegistryStatus(todoId, status, now);
        // 该待办的 pending 建议全部作废（用户手动改了，机器建议作废；resolved_at 落）
        suggestionMapper.update(null, new LambdaUpdateWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getTodoId, todoId)
                .eq(TodoSuggestion::getStatus, "pending")
                .set(TodoSuggestion::getStatus, "dismissed")
                .set(TodoSuggestion::getResolvedAt, now));
        log.info("待办状态直调：todo={}, {} → {}，pending 建议作废，用户={}", todoId, todo.getCurrentStatus(), status, userId);
    }

    // ==================== 查询（侧栏/列表） ====================

    @Override
    @Transactional(readOnly = true)
    public List<TodoSuggestionVO.SuggestionCard> pendingSuggestions(UUID userId) {
        List<TodoSuggestion> suggestions = suggestionMapper.selectPendingByUser(userId);
        List<TodoSuggestionVO.SuggestionCard> cards = new ArrayList<>(suggestions.size());
        for (TodoSuggestion s : suggestions) {
            TodoRegistry todo = registryMapper.selectById(s.getTodoId());
            Chunk evidence = chunkMapper.selectById(s.getEvidenceChunkId());
            cards.add(TodoSuggestionVO.SuggestionCard.builder()
                    .id(s.getId())
                    .todoId(s.getTodoId())
                    .title(todo == null ? "（已删除的待办）" : todo.getTitle())
                    .currentStatus(todo == null ? null : todo.getCurrentStatus())
                    .suggestedStatus(s.getSuggestedStatus())
                    .evidenceExcerpt(evidence == null ? null
                            : trunc(firstNonBlank(evidence.getSegment(), evidence.getContent()),
                            CARD_EXCERPT_LIMIT))
                    .evidenceRecordId(evidence == null ? null : evidence.getRecordId())
                    .createdAt(s.getCreatedAt() == null ? null : s.getCreatedAt().format(TS))
                    .build());
        }
        return cards;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoItemVO> listAll(UUID userId) {
        List<Map<String, Object>> rows = registryMapper.selectAllByUserRaw(userId);
        List<TodoItemVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(TodoItemVO.builder()
                    .id(longOf(row.get("todoid")))
                    .title(str(row.get("title")))
                    .currentStatus(str(row.get("currentstatus")))
                    .sourceChunkId(longOf(row.get("sourcechunkid")))
                    .sourceExcerpt(trunc(firstNonBlank(str(row.get("sourceexcerpt")),
                            str(row.get("sourcesummary"))), EXCERPT_LIMIT))
                    .orphan(row.get("sourcechunkid") == null)
                    .linkCount(longOf(row.get("linkcount")))
                    .pendingSuggestionCount(longOf(row.get("pendingcount")))
                    .createdAt(str(row.get("createdat")))
                    .closedAt(str(row.get("closedat")))
                    .build());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoChainVO> listOpenChains(UUID userId) {
        // ① open registry 基础行（selectOpenTodos 同口径：!= completed + INNER JOIN chunks 排 orphan，
        //    createdAt DESC；currentStatus 附带 chunk 实时 taskStatus——真源 #33）
        List<Map<String, Object>> bases = registryMapper.selectOpenChainBase(userId, CHAIN_LIMIT);
        if (bases.isEmpty()) {
            return List.of();
        }
        List<Long> todoIds = new ArrayList<>(bases.size());
        for (Map<String, Object> row : bases) {
            Long id = longOf(row.get("todoid"));
            if (id != null) {
                todoIds.add(id);
            }
        }
        if (todoIds.isEmpty()) {
            return List.of();
        }

        // ② 一次 links IN JOIN chunks：origin/evidence 全带回（SQL 已按 chunk date ASC 排），
        //    按 todoId 分组、按 relation 拆分
        Map<Long, TodoChainVO.ChainRef> origins = new HashMap<>();
        Map<Long, List<TodoChainVO.ChainEvidence>> evidences = new HashMap<>();
        for (Map<String, Object> l : linkMapper.selectChainLinks(todoIds)) {
            Long todoId = longOf(l.get("todoid"));
            if (todoId == null) {
                continue;
            }
            TodoChainVO.ChainRef ref = TodoChainVO.ChainRef.builder()
                    .chunkId(longOf(l.get("chunkid")))
                    .recordId(longOf(l.get("recordid")))
                    .excerpt(trunc(str(l.get("excerpt")), CHAIN_EXCERPT_LIMIT))
                    .date(str(l.get("date")))
                    .build();
            if ("origin".equals(str(l.get("relation")))) {
                origins.putIfAbsent(todoId, ref); // UNIQUE(todo_id, chunk_id) 下 origin 至多一条，防御取首条
            } else if ("evidence".equals(str(l.get("relation")))) {
                evidences.computeIfAbsent(todoId, k -> new ArrayList<>())
                        .add(TodoChainVO.ChainEvidence.builder()
                                .chunkId(ref.getChunkId())
                                .recordId(ref.getRecordId())
                                .excerpt(ref.getExcerpt())
                                .date(ref.getDate())
                                .confirmedAt(str(l.get("confirmedat")))
                                .build());
            }
        }

        // ③ 一次 suggestions pending 计数 GROUP BY todo_id
        Map<Long, Long> pendingCounts = new HashMap<>();
        for (Map<String, Object> c : suggestionMapper.selectPendingCounts(todoIds)) {
            Long todoId = longOf(c.get("todoid"));
            if (todoId != null) {
                pendingCounts.put(todoId, longOf(c.get("cnt")));
            }
        }

        // 组装（保持基础行 createdAt DESC 顺序）
        List<TodoChainVO> chains = new ArrayList<>(bases.size());
        for (Map<String, Object> row : bases) {
            Long todoId = longOf(row.get("todoid"));
            if (todoId == null) {
                continue;
            }
            // currentStatus 真源口径：chunk.metadata.taskStatus 实时值（COALESCE 已缺省 not_started）；
            // chunk 值是脏数据时回退 registry 物化值（双写正常时两者一致）
            String status = normalizeStatus(str(row.get("chunkstatus")));
            if (status == null) {
                status = str(row.get("currentstatus"));
            }
            chains.add(TodoChainVO.builder()
                    .todoId(todoId)
                    .title(str(row.get("title")))
                    .currentStatus(status)
                    .createdAt(str(row.get("createdat")))
                    .origin(origins.get(todoId)) // 理论必有；links 行丢失时判空不报错
                    .evidence(evidences.getOrDefault(todoId, List.of()))
                    .pendingSuggestionCount(pendingCounts.getOrDefault(todoId, 0L))
                    .build());
        }
        return chains;
    }

    // ==================== 内部工具 ====================

    /**
     * chunk.metadata.taskStatus 定向改（真源写）
     *
     * <p>与 ChunkServiceImpl.update 同款语义但只动 taskStatus 一个键。LambdaUpdateWrapper
     * 的 .set(metadata) 传 Map 会走 hstore 默认 Handler（库无 hstore 扩展，实测报
     * "No hstore extension installed"——updateById + autoResultMap 才走 JsonbMapTypeHandler），
     * 故用 updateById 全实体回写：先查快照只改 taskStatus 键再落。与 DigestService 竞态
     * 场景不同，这里改的是用户日记 chunk（无异步线程并发写 metadata），全列回写安全。</p>
     *
     * <p>不碰 classified_segment / user_edited——元数据变更不影响文本状态机（5.3 既有语义）。</p>
     */
    private void patchChunkTaskStatus(Long chunkId, String taskStatus) {
        if (chunkId == null) {
            return; // source chunk 已删（orphan）：真源不存在，registry 是唯一落点
        }
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            return;
        }
        Map<String, Object> metadata = chunk.getMetadata() == null
                ? new java.util.HashMap<>() : chunk.getMetadata();
        if ("not_started".equals(taskStatus)) {
            metadata.remove("taskStatus"); // 缺省语义（COALESCE 口径），与 ChunkDTO 清空语义一致
        } else {
            metadata.put("taskStatus", taskStatus);
        }
        chunk.setMetadata(metadata);
        chunkMapper.updateById(chunk);
    }

    /**
     * registry.current_status 物化写 + completed 时 closed_at（Asia/Shanghai）
     */
    private void applyRegistryStatus(Long todoId, String newStatus, OffsetDateTime now) {
        LambdaUpdateWrapper<TodoRegistry> wrapper = new LambdaUpdateWrapper<TodoRegistry>()
                .eq(TodoRegistry::getId, todoId)
                .set(TodoRegistry::getCurrentStatus, newStatus)
                .set(TodoRegistry::getUpdatedAt, now);
        if ("completed".equals(newStatus)) {
            wrapper.set(TodoRegistry::getClosedAt, now);
        } else {
            wrapper.set(TodoRegistry::getClosedAt, null); // 回退未完成：closed_at 清空
        }
        registryMapper.update(null, wrapper);
    }

    /**
     * 三态归一：null/非法 → null；兼容 TaskStatus 枚举名大写（TodoRef suggested_status）与前端小写
     */
    static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return VALID_STATUSES.contains(s) ? s : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return (b == null || b.isBlank()) ? null : b.trim();
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        String v = firstNonBlank(a, b);
        return v != null ? v : fallback;
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
