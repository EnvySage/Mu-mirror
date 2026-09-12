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
     */
    default List<TodoSuggestion> selectPendingByUser(UUID userId) {
        return selectList(new LambdaQueryWrapper<TodoSuggestion>()
                .eq(TodoSuggestion::getUserId, userId)
                .eq(TodoSuggestion::getStatus, "pending")
                .orderByDesc(TodoSuggestion::getCreatedAt)
                .orderByDesc(TodoSuggestion::getId));
    }

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
