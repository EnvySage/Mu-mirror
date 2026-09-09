package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistryLink;

import java.util.List;

/**
 * 待办关联 Mapper（todo-registry-design.md §2）
 *
 * <p>全部走 default 方法（LambdaQueryWrapper），无 JSONB 列无自定义 SQL 竞争坑
 * （见 UserTermMapper/ProfileSnapshotMapper 教训注释）。</p>
 */
@Mapper
public interface TodoRegistryLinkMapper extends BaseMapper<TodoRegistryLink> {

    /**
     * 某待办的全部关联（evidence 计数 / 关联日记列表用；按创建时间升序——origin 在前）
     */
    default List<TodoRegistryLink> selectByTodo(Long todoId) {
        return selectList(new LambdaQueryWrapper<TodoRegistryLink>()
                .eq(TodoRegistryLink::getTodoId, todoId)
                .orderByAsc(TodoRegistryLink::getCreatedAt)
                .orderByAsc(TodoRegistryLink::getId));
    }

    /**
     * 关联计数（GET /todos 列表 evidence 关联计数用）
     */
    default long countByTodo(Long todoId) {
        Long cnt = selectCount(new LambdaQueryWrapper<TodoRegistryLink>()
                .eq(TodoRegistryLink::getTodoId, todoId));
        return cnt == null ? 0 : cnt;
    }

    /**
     * 指定待办+chunk 的关联是否已存在（UNIQUE(todo_id, chunk_id) 先查后插防撞）
     */
    default TodoRegistryLink selectOneByTodoAndChunk(Long todoId, Long chunkId) {
        return selectOne(new LambdaQueryWrapper<TodoRegistryLink>()
                .eq(TodoRegistryLink::getTodoId, todoId)
                .eq(TodoRegistryLink::getChunkId, chunkId)
                .last("LIMIT 1"));
    }
}
