package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.xianshen.mumirrorb.pojo.DTO.TodoCommandDTO;
import org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Active independent todos and authorized record todos share hints/chains; record orphans stay visible only in the full list. */
@Mapper
public interface TodoRegistryMapper extends BaseMapper<TodoRegistry> {

    @Update("""
            UPDATE todo_registry SET title = #{command.title}, notes = #{command.notes}, due_at = #{command.dueAt},
                current_status = #{command.currentStatus},
                closed_at = CASE WHEN #{command.currentStatus} = 'completed' THEN COALESCE(closed_at, now()) ELSE NULL END,
                updated_at = now(), version = version + 1
            WHERE id = #{todoId} AND user_id = #{userId}::uuid AND version = #{expectedVersion} AND deleted_at IS NULL
            """)
    int updateVersioned(@Param("userId") UUID userId, @Param("todoId") Long todoId,
                        @Param("expectedVersion") long expectedVersion, @Param("command") TodoCommandDTO command);

    @Update("""
            UPDATE todo_registry SET deleted_at = now(), updated_at = now(), version = version + 1
            WHERE id = #{todoId} AND user_id = #{userId}::uuid AND version = #{expectedVersion} AND deleted_at IS NULL
            """)
    int deleteVersioned(@Param("userId") UUID userId, @Param("todoId") Long todoId,
                        @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.origin_kind AS originKind, t.version AS version, t.notes AS notes, t.due_at AS dueAt,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   (t.origin_kind = 'record' AND c.id IS NULL) AS orphan,
                   c.segment AS sourceExcerpt,
                   c.metadata->>'summary' AS sourceSummary,
                   TO_CHAR(t.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt,
                   t.closed_at AS closedAt
            FROM todo_registry t
            LEFT JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.user_id = #{userId}::uuid
              AND t.current_status != 'completed'
              AND t.deleted_at IS NULL
              AND (t.origin_kind = 'direct' OR (t.origin_kind = 'record' AND c.todo_approved = TRUE AND c.user_id = t.user_id))
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit}
            """)
    List<TodoRegistryDTO.TodoItem> selectOpenTodos(@Param("userId") UUID userId,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT t.id AS todoId,
                   t.title AS title,
                   t.origin_kind AS originKind, t.version AS version, t.notes AS notes, t.due_at AS dueAt,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   (t.origin_kind = 'record' AND c.id IS NULL) AS orphan,
                   TO_CHAR(t.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') AS createdAt
            FROM todo_registry t
            LEFT JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.user_id = #{userId}::uuid
              AND t.current_status != 'completed'
              AND t.deleted_at IS NULL
              AND (t.origin_kind = 'direct' OR (t.origin_kind = 'record' AND c.todo_approved = TRUE AND c.user_id = t.user_id))
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectOpenChainBase(@Param("userId") UUID userId,
                                                  @Param("limit") int limit);

    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.origin_kind AS originKind, t.version AS version, t.notes AS notes, t.due_at AS dueAt,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   (t.origin_kind = 'record' AND c.id IS NULL) AS orphan,
                   c.segment AS sourceExcerpt,
                   c.metadata->>'summary' AS sourceSummary,
                   TO_CHAR(t.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt,
                   t.closed_at AS closedAt
            FROM todo_registry t
            LEFT JOIN chunks c ON c.id = t.source_chunk_id
            WHERE t.id = #{todoId}
              AND t.user_id = #{userId}::uuid
              AND t.deleted_at IS NULL
            """)
    TodoRegistryDTO.TodoItem selectOneByUser(@Param("todoId") Long todoId,
                                             @Param("userId") UUID userId);

    @Select("""
            SELECT t.id AS todoId,
                   t.user_id AS userId,
                   t.title AS title,
                   t.origin_kind AS originKind, t.version AS version, t.notes AS notes, t.due_at AS dueAt,
                   t.current_status AS currentStatus,
                   t.source_chunk_id AS sourceChunkId,
                   (t.origin_kind = 'record' AND c.id IS NULL) AS orphan,
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
              AND t.deleted_at IS NULL
            ORDER BY (t.current_status = 'completed') ASC, t.created_at DESC, t.id DESC
            """)
    List<Map<String, Object>> selectAllByUserRaw(@Param("userId") UUID userId);
}
