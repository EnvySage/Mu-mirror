package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistryLink;

import java.util.List;
import java.util.Map;

/**
 * 待办关联 Mapper（todo-registry-design.md §2）
 *
 * <p>单条路径全部走 default 方法（LambdaQueryWrapper），无 JSONB 列无自定义 SQL 竞争坑
 * （见 UserTermMapper/ProfileSnapshotMapper 教训注释）；批量链查询走 @Select script
 * （IN + JOIN 非 wrapper 能表达）。</p>
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

    /**
     * 证据链批量查询（GET /todos/open-chain 第二段：一次 links IN(todoIds) JOIN chunks）
     *
     * <p>excerpt 取 COALESCE(segment, content)（SQL 粗裁 200 防大文本传输，Service 层
     * trunc 精裁 60 字符带省略号，与既有卡片摘录口径一致）；date 取 chunk.created_at
     * （Asia/Shanghai，yyyy-MM-dd HH:mm，AT TIME ZONE 口径照 ProfileStatsMapper）；
     * confirmedAt 取 link.created_at（背书时刻，yyyy-MM-dd HH:mm）。按 date ASC, id ASC 排。
     * origin/evidence 都在这一次查询带回，Service 层按 relation 拆分。chunk 理论上必在
     * （links 对 chunks 有 FK CASCADE），JOIN 用 INNER。</p>
     *
     * <p>别名不加引号（pg 折叠小写），Service 层按小写 key 读——照 selectAllByUserRaw 既有口径。</p>
     *
     * @param todoIds 待办 ID 列表（调用方保证非空）
     */
    @Select("""
            <script>
            SELECT l.todo_id AS todoId,
                   l.relation AS relation,
                   c.id AS chunkId,
                   c.record_id AS recordId,
                   LEFT(COALESCE(NULLIF(c.segment, ''), c.content), 200) AS excerpt,
                   TO_CHAR(c.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS date,
                   TO_CHAR(l.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS confirmedAt
            FROM todo_registry_links l
            JOIN chunks c ON c.id = l.chunk_id
            WHERE l.todo_id IN
              <foreach collection="todoIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            ORDER BY c.created_at ASC, l.id ASC
            </script>
            """)
    List<Map<String, Object>> selectChainLinks(@Param("todoIds") List<Long> todoIds);
}
