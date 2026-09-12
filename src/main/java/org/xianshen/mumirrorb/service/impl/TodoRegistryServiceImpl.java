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
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.TodoChainVO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;
import org.xianshen.mumirrorb.service.TodoRegistryService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        if (todo.getDeletedAt() != null) {
            return; // 已删除的待办不再产生新建议（todo-status-removal-design.md §8）
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
     * 确认：事务内五写——① （todo 存在时）终态回写源头片段 taskStatus（新需求，补齐现状缺口）
     * ② chunk.metadata.taskStatus（证据片段真源，定向改）③ registry.current_status
     * 同步 + completed 时 closed_at ④ evidence 关联落库（用户背书才落）⑤ 建议行 confirmed
     */
    private void doConfirm(TodoSuggestion suggestion, UUID userId, String status) {
        String newStatus = status != null && !status.isBlank()
                ? normalizeStatus(status) : suggestion.getSuggestedStatus();
        if (newStatus == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "status 取值非法（not_started/in_progress/completed）");
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Chunk evidenceChunk = chunkMapper.selectById(suggestion.getEvidenceChunkId());

        // 已删除的 todo 不可改状态（防御；正常路径删除时 pending 建议已作废，
        // todo==null 为脏数据，降级跳过源头回写）
        TodoRegistry todo = registryMapper.selectById(suggestion.getTodoId());
        if (todo != null) {
            if (todo.getDeletedAt() != null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该待办已删除，无法变更状态");
            }
            // ① 终态回写源头片段（todo-status-removal-design.md §5：补齐现状缺口——原建议裁决
            //    路径只写 evidence 片段；source chunk 已删/orphan 时 patchChunkTaskStatus 内部跳过）
            patchChunkTaskStatus(todo.getSourceChunkId(), newStatus);
        }

        // ② chunk.metadata.taskStatus（证据片段真源；定向 SET 不触发 classified_segment 重置——
        //    元数据变更不影响文本状态机，5.3 既有语义；保留现状语义）
        patchChunkTaskStatus(suggestion.getEvidenceChunkId(), newStatus);

        // ③ registry.current_status 同步 + completed 时 closed_at
        applyRegistryStatus(suggestion.getTodoId(), newStatus, now);

        // ④ evidence 关联（用户背书才落；UNIQUE(todo_id, chunk_id) 已有 origin 时跳过）
        if (evidenceChunk != null
                && linkMapper.selectOneByTodoAndChunk(suggestion.getTodoId(), evidenceChunk.getId()) == null) {
            linkMapper.insert(TodoRegistryLink.builder()
                    .todoId(suggestion.getTodoId())
                    .chunkId(evidenceChunk.getId())
                    .relation("evidence")
                    .createdAt(now)
                    .build());
        }

        // ⑤ 建议行 confirmed
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
        //    createdAt DESC；currentStatus 取 registry 值——待办状态类读取统一 registry 口径，
        //    todo-status-removal-design.md §11）
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
            // currentStatus 统一 registry 口径（§11）：registry 是持续维护的状态机真源，
            // chunk.metadata.taskStatus 只是登记时初值，状态变更后即过期。
            // registry 值脏（写入侧已归一，理论不可能）→ 兜底 not_started，保证前端三态渲染不炸。
            String status = normalizeStatus(str(row.get("currentstatus")));
            if (status == null) {
                status = "not_started";
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

    // ==================== 删除（特例：侧栏直删） ====================

    @Override
    @Transactional
    public void deleteTodo(Long todoId, UUID userId) {
        TodoRegistry todo = registryMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "待办不存在");
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        if (todo.getDeletedAt() != null) {
            log.info("待办已删除（幂等返回成功）：todo={}, 用户={}", todoId, userId);
            return;
        }
        // ① registry 软删（行保留 + deleted_at；所有视图过滤）
        registryMapper.update(null, new LambdaUpdateWrapper<TodoRegistry>()
                .eq(TodoRegistry::getId, todoId)
                .set(TodoRegistry::getDeletedAt, now)
                .set(TodoRegistry::getUpdatedAt, now));
        // ② 源头片段 metadata 加 todoRemoved=true（source_chunk_id 空 / chunk 不存在则跳过）
        patchChunkTodoRemoved(todo.getSourceChunkId());
        // ③ 该 todo 全部 pending 建议作废（永久静默；resolved_at 落）
        suggestionMapper.update(null, new LambdaUpdateWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getTodoId, todoId)
                .eq(TodoSuggestion::getStatus, "pending")
                .set(TodoSuggestion::getStatus, "dismissed")
                .set(TodoSuggestion::getResolvedAt, now));
        log.info("待办已删除（软删 + 源头标记 + pending 建议作废）：todo={}, title={}, 用户={}",
                todoId, todo.getTitle(), userId);
    }

    // ==================== 审核窗口：记录确认应用待办决议 ====================

    @Override
    @Transactional
    public void applyRecordResolutions(Long recordId, UUID userId, List<TodoResolutionDTO> resolutions) {
        Record record = recordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return; // 防御：调用方 confirmReview 已校验归属
        }
        // 契约校验先行（suggestionId / todoId 恰好其一）：即便记录无 chunk 也须对非法 body 报 400
        validateResolutions(resolutions);
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getRecordId, recordId));
        List<Long> chunkIds = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c.getId() != null) {
                chunkIds.add(c.getId());
            }
        }
        if (chunkIds.isEmpty()) {
            return;
        }
        // 本记录 evidence 的 pending 建议 = 本次窗口待处理清单（不按 record.status 过滤：
        // confirmReview 在调用本方法前已把记录置 DONE，窗口语义由 evidence 归属表达）
        List<TodoSuggestion> pendings = suggestionMapper.selectPendingByEvidenceChunks(chunkIds);
        if (pendings.isEmpty() && (resolutions == null || resolutions.isEmpty())) {
            return;
        }
        Map<Long, TodoSuggestion> byId = new HashMap<>();
        for (TodoSuggestion s : pendings) {
            byId.put(s.getId(), s);
        }

        // ① 处理 body 指定决议
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Set<Long> handled = new HashSet<>();
        if (resolutions != null) {
            for (TodoResolutionDTO r : resolutions) {
                if (r == null) {
                    continue; // 脏数据防御（validateResolutions 已保证非 null 条目恰好其一）
                }
                String act = r.getAction() == null ? "" : r.getAction().trim().toLowerCase(Locale.ROOT);
                if (r.getSuggestionId() != null) {
                    // ---- 现有分支：裁决 AI 建议（行为不变） ----
                    TodoSuggestion s = byId.get(r.getSuggestionId());
                    if (s == null) {
                        // 不在本记录窗口 / 已处理 / 非本人：不泄存在性，跳过（前端脏数据容错）
                        log.warn("待办决议跳过：建议不在本记录审核窗口或已处理，record={}, suggestion={}",
                                recordId, r.getSuggestionId());
                        continue;
                    }
                    switch (act) {
                        case "confirmed" -> {
                            if (r.getStatus() == null || r.getStatus().isBlank()) {
                                throw new BusinessException(ResultCode.PARAM_ERROR, "action=confirmed 时 status 必填");
                            }
                            if (normalizeStatus(r.getStatus()) == null) {
                                throw new BusinessException(ResultCode.PARAM_ERROR,
                                        "status 取值非法（not_started/in_progress/completed）");
                            }
                            doConfirm(s, userId, r.getStatus());
                        }
                        case "dismissed" -> doDismiss(s);
                        default -> throw new BusinessException(ResultCode.PARAM_ERROR,
                                "action 取值非法（confirmed/dismissed）");
                    }
                    handled.add(s.getId());
                } else {
                    // ---- 新增分支：用户主动挂载已注册 todo（无建议） ----
                    // action 仅允许 confirmed：不挂载就不提交该行，无"忽略"语义（dismissed → 400）
                    if (!"confirmed".equals(act)) {
                        throw new BusinessException(ResultCode.PARAM_ERROR,
                                "todoId 分支 action 必须为 confirmed（不挂载则不提交该行）");
                    }
                    if (r.getStatus() == null || r.getStatus().isBlank()) {
                        throw new BusinessException(ResultCode.PARAM_ERROR, "action=confirmed 时 status 必填");
                    }
                    if (normalizeStatus(r.getStatus()) == null) {
                        throw new BusinessException(ResultCode.PARAM_ERROR,
                                "status 取值非法（not_started/in_progress/completed）");
                    }
                    // 合并确认的建议 id 一并纳入 handled，避免随后被"未处理一律作废"误改
                    handled.addAll(mountTodo(r.getTodoId(), userId, r.getStatus(), chunkIds, now));
                }
            }
        }

        // ② 未出现在 body 中的 pending 建议 → 一律作废（含 body 缺省，行为变化）
        int dismissed = 0;
        for (TodoSuggestion s : pendings) {
            if (!handled.contains(s.getId())) {
                doDismiss(s);
                dismissed++;
            }
        }
        log.info("记录 {} 待办决议完成：confirmed/dismissed 指定 {} 条，未处理作废 {} 条，用户={}",
                recordId, handled.size(), dismissed, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TodoSuggestionVO.RecordSuggestion> listRecordSuggestions(Long recordId, UUID userId) {
        List<Map<String, Object>> rows = suggestionMapper.selectRecordSuggestions(recordId, userId);
        List<TodoSuggestionVO.RecordSuggestion> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(TodoSuggestionVO.RecordSuggestion.builder()
                    .suggestionId(longOf(row.get("suggestionId")))
                    .todoId(longOf(row.get("todoId")))
                    .todoTitle(str(row.get("todoTitle")))
                    .todoStatus(str(row.get("todoStatus")))
                    .suggestedStatus(str(row.get("suggestedStatus")))
                    .evidenceChunkId(longOf(row.get("evidenceChunkId")))
                    .build());
        }
        return result;
    }

    // ==================== 内部工具 ====================

    /**
     * 契约校验：每条决议的 suggestionId / todoId 恰好提供其一（都无 / 都有 → 400 防歧义）
     *
     * <p>先于一切副作用执行（含"记录无 chunk 短路"之前），保证非法 body 无论记录形态如何都报 400。</p>
     */
    private void validateResolutions(List<TodoResolutionDTO> resolutions) {
        if (resolutions == null) {
            return;
        }
        for (TodoResolutionDTO r : resolutions) {
            if (r == null) {
                continue; // 脏数据：单项 null 静默跳过
            }
            boolean hasSuggestion = r.getSuggestionId() != null;
            boolean hasTodo = r.getTodoId() != null;
            if (hasSuggestion == hasTodo) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "suggestionId 与 todoId 必须恰好提供其一");
            }
        }
    }

    /**
     * 用户主动挂载：把已注册 todo 关联到本记录并同步其状态（todoResolutions 的 todoId 分支）
     *
     * <p>事务内（由 applyRecordResolutions 外层事务包裹）：</p>
     * <ol>
     *   <li>todo 不存在 / 非本人 / 已删除（deleted_at 非空）→ 静默忽略该条 + warn（防御，
     *       不阻断 confirm；与"越窗忽略"防御哲学一致）</li>
     *   <li>回写源头片段 taskStatus（真源；source chunk orphan 则跳过，惯例）</li>
     *   <li>registry.current_status 物化 + closed_at（状态相同也统一走，幂等）</li>
     *   <li>本记录<b>全部</b> chunk 落 evidence link（UNIQUE(todo_id, chunk_id) 已存在则跳过）</li>
     *   <li>该 todo <b>全部</b> pending 建议一并置 confirmed + resolved_at（防孤儿建议；
     *       与 deleteTodo 的"todo 级 pending 作废"对称——todo 状态已由用户直接拍板，
     *       任何证据来源的待处理建议均不再有意义）</li>
     * </ol>
     *
     * @return 本次被合并确认的建议 id 集合（供"未处理一律作废"排除，避免误将 confirmed 改回 dismissed）
     */
    private Set<Long> mountTodo(Long todoId, UUID userId, String status, List<Long> recordChunkIds,
                                OffsetDateTime now) {
        TodoRegistry todo = registryMapper.selectById(todoId);
        if (todo == null || !todo.getUserId().equals(userId)) {
            log.warn("用户主动挂载跳过：todo 不存在或非本人，todo={}, 用户={}", todoId, userId);
            return Set.of();
        }
        if (todo.getDeletedAt() != null) {
            log.warn("用户主动挂载跳过：todo 已删除，todo={}, 用户={}", todoId, userId);
            return Set.of();
        }
        // 调用方已保证 status 为合法三态
        String newStatus = normalizeStatus(status);
        // ① 真源回写：源头片段 taskStatus（source chunk orphan 则内部跳过）
        patchChunkTaskStatus(todo.getSourceChunkId(), newStatus);
        // ② registry 物化 + closed_at（completed 落值 / 非 completed 清空；状态相同也幂等走）
        applyRegistryStatus(todoId, newStatus, now);
        // ③ 本记录全部 chunk 落 evidence link（UNIQUE 已存在——含 origin——则跳过）
        int linked = 0;
        for (Long chunkId : recordChunkIds) {
            if (linkMapper.selectOneByTodoAndChunk(todoId, chunkId) == null) {
                linkMapper.insert(TodoRegistryLink.builder()
                        .todoId(todoId)
                        .chunkId(chunkId)
                        .relation("evidence")
                        .createdAt(now)
                        .build());
                linked++;
            }
        }
        // ④ 该 todo 全部 pending 建议合并确认（防孤儿建议）
        Set<Long> confirmed = new HashSet<>();
        List<TodoSuggestion> pendingOfTodo = suggestionMapper.selectList(
                new LambdaQueryWrapper<TodoSuggestion>()
                        .eq(TodoSuggestion::getTodoId, todoId)
                        .eq(TodoSuggestion::getStatus, "pending"));
        for (TodoSuggestion s : pendingOfTodo) {
            suggestionMapper.update(null, new LambdaUpdateWrapper<TodoSuggestion>()
                    .eq(TodoSuggestion::getId, s.getId())
                    .set(TodoSuggestion::getStatus, "confirmed")
                    .set(TodoSuggestion::getResolvedAt, now));
            confirmed.add(s.getId());
        }
        log.info("用户主动挂载成功：todo={}, 状态={}, 新增 evidence link={}, 合并确认建议={}, 用户={}",
                todoId, newStatus, linked, confirmed.size(), userId);
        return confirmed;
    }

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
     * 源头片段 metadata 加 {@code todoRemoved: true} 标记（删除特例）
     *
     * <p>与 patchChunkTaskStatus 同款 updateById 全实体回写（JsonbMapTypeHandler 生效），
     * 只动 todoRemoved 一个键。source_chunk_id 为空（orphan）或 chunk 物理不存在则跳过——
     * registry 的 deleted_at 仍保证所有视图过滤，标记仅用于 chunk 口径统计排除。</p>
     */
    private void patchChunkTodoRemoved(Long chunkId) {
        if (chunkId == null) {
            return;
        }
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            return;
        }
        Map<String, Object> metadata = chunk.getMetadata() == null
                ? new java.util.HashMap<>() : chunk.getMetadata();
        metadata.put("todoRemoved", true);
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
