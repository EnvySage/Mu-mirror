package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.TodoChainVO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;

import java.util.List;
import java.util.UUID;

/**
 * 待办登记服务（todo-registry-design.md §3/§4-B）
 *
 * <p>三段生命周期：</p>
 * <ol>
 *   <li>登记期（零门禁）：confirmReview/auto 管道完成路径调 {@link #registerFromRecord}，
 *       每 todo/plan chunk 幂等登记</li>
 *   <li>判别期（proto 扩展）：组装 ClassifyRequest 查 {@link #openTodosForHint} 塞 open_todos；
 *       ClassifyReply 回传 refers_to_todo → {@link #suggestFromChunk} 落 pending 建议</li>
 *   <li>裁决期（用户主权）：{@link #resolve} 事务三写 / {@link #applyRecordResolutions} 审核窗口决议</li>
 * </ol>
 */
public interface TodoRegistryService {

    /**
     * 记录确认入库时登记待办（登记期，零门禁）
     *
     * <p>confirmReview 完成路径与 auto EventListener 完成路径都会走到这里
     * （auto 路径 confirmReview 内部统一调用，一次覆盖两条路径）。
     * 幂等：source_chunk_id 已登记跳过。登记失败不阻断确认主流程（调用方 try-catch）。</p>
     *
     * @param recordId 已确认入库的记录 ID（须属于 userId）
     * @param userId   用户 ID
     * @return 本次新登记条数（0 = 全部已登记或无 todo/plan 片段）
     */
    int registerFromRecord(Long recordId, UUID userId);

    /**
     * Classify 判别期：回传 refers_to_todo → 落 pending 建议
     *
     * <p>幂等/去重：同一 (todo, evidence_chunk) 已有 pending 或已 confirmed 建议不重复落；
     * dismissed 永久静默（同一证据不再提示，新证据=新 chunk 可再提示，裁决 #5）。</p>
     *
     * @param userId       用户 ID
     * @param evidenceChunk 触发判别的片段（新日记的 todo/plan chunk）
     * @param todoRef      LLM 判别结果（todo_id + suggested_status）
     */
    void suggestFromChunk(UUID userId, Chunk evidenceChunk, RecordProcessorProto.TodoRef todoRef);

    /**
     * 用户裁决建议（§3.3）
     *
     * @param suggestionId 建议行 ID
     * @param userId       用户 ID（ownership）
     * @param action       confirmed / dismissed
     * @param status       用户选的三态（可改 LLM 建议；action=confirmed 时可空=按 suggested_status）
     */
    void resolve(Long suggestionId, UUID userId, String action, String status);

    /**
     * 判别期注入清单（open_todos；未完成 + 非 orphan，最近优先，≤20 条）
     */
    List<org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO.TodoItem> openTodosForHint(UUID userId);

    /**
     * pending 建议卡列表（侧栏角标 + 建议卡数据）
     */
    List<TodoSuggestionVO.SuggestionCard> pendingSuggestions(UUID userId);

    /**
     * 全量登记列表（GET /todos，带关联计数）
     */
    List<TodoItemVO> listAll(UUID userId);

    /**
     * 未完成待办证据链（GET /todos/open-chain）
     *
     * <p>口径：selectOpenTodos 同款（!= completed 且非 orphan），按 createdAt DESC；
     * 每链 = origin（可空，代码判空）+ evidence[]（date ASC）+ pendingSuggestionCount。
     * currentStatus 以 chunk.metadata.taskStatus 实时值为准（真源 #33）。
     * 批量三段查询防 N+1：open 基础行 → links IN JOIN chunks → suggestions count GROUP BY。</p>
     */
    List<TodoChainVO> listOpenChains(UUID userId);

    /**
     * 删除待办（特例：侧栏直删 + 弹框；软删方案，todo-status-removal-design.md §3/§4）
     *
     * <p>事务内：① registry.deleted_at=now（已删幂等返回成功）② 源头片段 metadata 加
     * {@code todoRemoved: true}（source_chunk_id 为空或 chunk 不存在则跳过）③ 该 todo 全部
     * pending 建议置 dismissed + resolved_at。原始记录保留，终态可追溯。</p>
     *
     * <p>删除后：所有视图过滤不可见，不再产生新建议，不可改状态。</p>
     *
     * @param todoId 登记 ID
     * @param userId 用户 ID（ownership；非本人一律 404 不暴露存在性）
     */
    void deleteTodo(Long todoId, UUID userId);

    /**
     * 记录确认入库时应用待办决议（审核页唯一状态变更入口，todo-status-removal-design.md §5/§10）
     *
     * <p>处理 {@code body.todoResolutions}，条目分两类（suggestionId 与 todoId <b>恰好其一</b>，
     * 都无/都有 → 400）：</p>
     * <ul>
     *   <li><b>suggestionId 分支（裁决 AI 建议）</b>：
     *       confirmed → 更新该 todo registry 状态（closed_at 语义对齐 applyRegistryStatus）
     *       → 回写 source chunk taskStatus（新需求）+ evidence chunk taskStatus（保留现状语义）
     *       → 落 evidence link（若不存在）→ 建议置 confirmed + resolved_at；
     *       dismissed → 建议置 dismissed + resolved_at</li>
     *   <li><b>todoId 分支（用户主动挂载，无建议）</b>：action 仅允许 confirmed（dismissed → 400），
     *       status 必填三态。todo 不存在/非本人/已删除 → 静默忽略该条 + warn（不阻断 confirm）；
     *       status 相同也幂等走；回写 source chunk taskStatus + registry 物化 + 本记录 evidence link +
     *       该 todo 全部 pending 建议一并 confirmed（防孤儿）</li>
     *   <li>本记录下未出现在 body 中的 pending 建议 → 一律 dismissed（含 body 缺省——旧客户端行为变化）</li>
     * </ul>
     *
     * <p>调用方（confirmReview）在事务内调用；与补分类/embedding/待办登记衔接。建议先于
     * registerFromRecord 调用：evidence chunk 若本身是 todo 片段，回写的 taskStatus 会
     * 被登记读取，避免物化值落后。</p>
     *
     * @param recordId    正在确认的记录 ID（REVIEWING）
     * @param userId      用户 ID
     * @param resolutions body 决议列表（null/空 = 全部未处理作废）
     */
    void applyRecordResolutions(Long recordId, UUID userId, List<TodoResolutionDTO> resolutions);

    /**
     * 审核页数据接口（GET /records/{id}/suggestions）
     *
     * <p>返回该记录 evidence 的 pending 建议（合同字段见
     * {@link TodoSuggestionVO.RecordSuggestion}）。ownership 在 SQL 层过滤。</p>
     */
    List<TodoSuggestionVO.RecordSuggestion> listRecordSuggestions(Long recordId, UUID userId);
}
