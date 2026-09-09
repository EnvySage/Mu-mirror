package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.TodoSuggestion;

import java.util.List;
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
}
