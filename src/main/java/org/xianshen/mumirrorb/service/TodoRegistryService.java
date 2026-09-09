package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
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
 *   <li>裁决期（用户主权）：{@link #resolve} 事务三写 / {@link #setStatusDirectly} 直调双写</li>
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
     * 侧栏直调状态变更（§3.3 auto 主路径）
     *
     * <p>事务内双写（chunk.metadata.taskStatus + registry.current_status/closed_at）
     * + 该待办的 pending 建议全部作废（status=dismissed，resolved_at）。</p>
     *
     * @param todoId    登记 ID
     * @param userId    用户 ID（ownership）
     * @param newStatus 三态
     */
    void setStatusDirectly(Long todoId, UUID userId, String newStatus);

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
}
