package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待办登记 Mapper（todo-registry-design.md §2/§3.2）
 *
 * <p>核心查询 selectOpenTodos 是判别期注入清单的唯一口径：</p>
 * <ul>
 *   <li>current_status != 'completed'（完成的不注入）</li>
 *   <li>JOIN chunks 判 orphan：原 chunk 被删（registry.source_chunk_id 置 NULL
 *       或 chunk 物理不存在）→ <b>排除</b>（orphan 关闭逻辑不物理改行，JOIN 实现）</li>
 *   <li>登记时间最近优先，LIMIT 20（判别注入 prompt 膨胀防线）</li>
 * </ul>
 */
@Mapper
public interface TodoRegistryMapper extends BaseMapper<TodoRegistry> {

    /**
     * 未完成待办清单（Classify 判别期注入 + GET /todos 列表共用口径）
     *
     * <p>orphan 判据：INNER JOIN chunks（source_chunk_id IS NOT NULL 且 chunk 存在即关联；
     * chunks 物理删除后 INNER JOIN 天然排除，registry 行保留不动）。</p>
     *
     * @param limit 注入上限（判别期 20；列表侧传大值如 200 全量）
     */
    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   c.segment AS sourceExcerpt,
                   c.metadata->>'summary' AS sourceSummary,
                   TO_CHAR(t.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt,
                   t.closed_at AS closedAt
            FROM todo_registry t
            JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.user_id = #{userId}::uuid
              AND t.current_status != 'completed'
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit}
            """)
    List<TodoRegistryDTO.TodoItem> selectOpenTodos(@Param("userId") UUID userId,
                                                   @Param("limit") int limit);

    /**
     * 未完成待办基础行（GET /todos/open-chain 证据链聚合第一段查询）
     *
     * <p>口径与 selectOpenTodos 完全一致（current_status != 'completed' + INNER JOIN chunks
     * 天然排除 orphan + created_at DESC, id DESC），仅两点差异：</p>
     * <ul>
     *   <li>currentStatus 取 <b>chunk.metadata.taskStatus 实时值</b>（真源 #33；COALESCE 缺省
     *       not_started，与 ProfileStatsMapper 同款）——registry 只是物化索引，读路径以 chunk 为准；
     *       行过滤仍按 registry.current_status（双写保证一致，chunk 脏值时 Service 回退 registry 值）</li>
     *   <li>created_at 输出 yyyy-MM-dd HH:mm:ss（Asia/Shanghai，AT TIME ZONE 口径照 ProfileStatsMapper）</li>
     * </ul>
     */
    @Select("""
            SELECT t.id AS todoId,
                   t.title AS title,
                   t.current_status AS currentStatus,
                   COALESCE(c.metadata->>'taskStatus', 'not_started') AS chunkStatus,
                   t.source_chunk_id AS sourceChunkId,
                   TO_CHAR(t.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') AS createdAt
            FROM todo_registry t
            JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.user_id = #{userId}::uuid
              AND t.current_status != 'completed'
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectOpenChainBase(@Param("userId") UUID userId,
                                                  @Param("limit") int limit);

    /**
     * 单条登记行（带 source chunk 摘要；ownership 过滤在 SQL 层——非本人查不到，防存在性探测）
     */
    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   c.segment AS sourceExcerpt,
                   c.metadata->>'summary' AS sourceSummary,
                   TO_CHAR(t.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt,
                   t.closed_at AS closedAt
            FROM todo_registry t
            LEFT JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.id = #{todoId}
              AND t.user_id = #{userId}::uuid
            """)
    TodoRegistryDTO.TodoItem selectOneByUser(@Param("todoId") Long todoId,
                                             @Param("userId") UUID userId);

    /**
     * 全部登记行（含 completed/orphan；GET /todos 全量列表）
     *
     * <p>JOIN LATERAL 计 evidence 关联数；isOrphan = source chunk 已被物理删除
     * （LEFT JOIN 不上即 true，行保留但不进注入清单）。</p>
     */
    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   c.segment AS sourceExcerpt,
                   c.metadata->>'summary' AS sourceSummary,
                   TO_CHAR(t.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt,
                   t.closed_at AS closedAt,
                   (SELECT COUNT(*) FROM todo_registry_links l
                     WHERE l.todo_id = t.id) AS linkCount,
                   (SELECT COUNT(*) FROM todo_suggestions s
                     WHERE s.todo_id = t.id AND s.status = 'pending') AS pendingCount
            FROM todo_registry t
            LEFT JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.user_id = #{userId}::uuid
            ORDER BY t.created_at DESC, t.id DESC
            """)
    List<Map<String, Object>> selectAllByUserRaw(@Param("userId") UUID userId);
}
