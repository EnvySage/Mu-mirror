package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.TodoSuggestion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待办状态建议 Mapper（todo-registry-design.md §2）
 */
@Mapper
public interface TodoSuggestionMapper extends BaseMapper<TodoSuggestion> {

    /**
     * 侧栏角标+建议卡数据源：pending 建议列表（新→旧）
     *
     * <p>展示兜底（todo-status-removal-design.md §7）：只返回"证据记录仍为 REVIEWING"的建议
     * （JOIN records 过滤，兼顾历史遗留数据与审核窗口绑定语义）；已删除 todo（deleted_at 非空）
     * 的建议一律不展示。全局类型处理器 {@code UuidTypeHandler}（@MappedTypes(UUID.class)）
     * 负责 user_id 映射。</p>
     */
    @Select("""
            SELECT s.id, s.user_id, s.todo_id, s.evidence_chunk_id,
                   s.suggested_status, s.status, s.created_at, s.resolved_at
            FROM todo_suggestions s
            JOIN chunks c ON c.id = s.evidence_chunk_id
            JOIN records r ON r.id = c.record_id
            JOIN todo_registry t ON t.id = s.todo_id
            WHERE s.user_id = #{userId}::uuid
              AND s.status = 'pending'
              AND r.status = 'reviewing'
              AND r.deleted_at IS NULL
              AND t.deleted_at IS NULL
            ORDER BY s.created_at DESC, s.id DESC
            """)
    List<TodoSuggestion> selectPendingByUser(@Param("userId") UUID userId);

    /**
     * 某记录 evidence 片段的全部 pending 建议（confirm 入库时"未处理一律作废"的数据源）
     *
     * <p>窗口绑定语义：审核窗口 = REVIEWING 记录。confirm 提交时本记录 evidence 的 pending
     * 建议若未出现在 body.todoResolutions，一律 dismissed（todo-status-removal-design.md §5）。</p>
     *
     * @param chunkIds 本记录全部 chunk ID（调用方保证非空）
     */
    @Select("""
            <script>
            SELECT s.id, s.user_id, s.todo_id, s.evidence_chunk_id,
                   s.suggested_status, s.status, s.created_at, s.resolved_at
            FROM todo_suggestions s
            JOIN todo_registry t ON t.id = s.todo_id
            WHERE s.status = 'pending'
              AND t.deleted_at IS NULL
              AND s.evidence_chunk_id IN
              <foreach collection="chunkIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            </script>
            """)
    List<TodoSuggestion> selectPendingByEvidenceChunks(@Param("chunkIds") List<Long> chunkIds);

    /**
     * 审核页数据接口（GET /records/{id}/suggestions）：该记录 evidence 的 pending 建议
     *
     * <p>合同字段（todo-status-removal-design.md §4）：suggestionId / todoId / todoTitle /
     * todoStatus / suggestedStatus / evidenceChunkId。引号别名保留驼峰（PG 会折叠未加引号的
     * 标识符，Map 消费侧按原样 key 读取——照 ProfileStatsMapper 既有口径）。ownership 在
     * SQL 层过滤（r.user_id），非本人返回空。</p>
     */
    @Select("""
            SELECT s.id AS "suggestionId",
                   s.todo_id AS "todoId",
                   t.title AS "todoTitle",
                   t.current_status AS "todoStatus",
                   s.suggested_status AS "suggestedStatus",
                   s.evidence_chunk_id AS "evidenceChunkId"
            FROM todo_suggestions s
            JOIN chunks c ON c.id = s.evidence_chunk_id
            JOIN records r ON r.id = c.record_id
            JOIN todo_registry t ON t.id = s.todo_id
            WHERE s.user_id = #{userId}::uuid
              AND r.id = #{recordId}
              AND r.user_id = #{userId}::uuid
              AND s.status = 'pending'
              AND t.deleted_at IS NULL
            ORDER BY s.created_at DESC, s.id DESC
            """)
    List<Map<String, Object>> selectRecordSuggestions(@Param("recordId") Long recordId,
                                                      @Param("userId") UUID userId);

    /**
     * 某待办的 pending 建议数（列表角标）
     */
    default long countPendingByTodo(Long todoId) {
        Long cnt = selectCount(new LambdaQueryWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getTodoId, todoId)
                .eq(TodoSuggestion::getStatus, "pending"));
        return cnt == null ? 0 : cnt;
    }

    /**
     * 证据链批量计数（GET /todos/open-chain 第三段）：一次 IN(todoIds) + status='pending'
     * GROUP BY todo_id，防逐条 countPendingByTodo 的 N+1。无 pending 的 todo 不出现在结果里
     * （Service 层缺省 0）。
     *
     * @param todoIds 待办 ID 列表（调用方保证非空）
     */
    @Select("""
            <script>
            SELECT s.todo_id AS todoId, COUNT(*) AS cnt
            FROM todo_suggestions s
            WHERE s.status = 'pending'
              AND s.todo_id IN
              <foreach collection="todoIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            GROUP BY s.todo_id
            </script>
            """)
    List<Map<String, Object>> selectPendingCounts(@Param("todoIds") List<Long> todoIds);
}
