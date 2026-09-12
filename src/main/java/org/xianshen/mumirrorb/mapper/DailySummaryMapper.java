package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.Chunk;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日总结统计 Mapper（设计文档 6.7）
 *
 * <p>昨日记录数 / 类型分布 / 情绪分布 / 活跃时段，供日报 prompt 使用。
 * 全部参数化占位符；口径与画像统计一致（source='user'、未删除、DONE）。</p>
 */
@Mapper
public interface DailySummaryMapper {

    /**
     * 昨日有效记录数
     */
    @Select("""
            SELECT COUNT(*) FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
            """)
    long countRecords(@Param("userId") UUID userId,
                      @Param("dayStart") OffsetDateTime dayStart,
                      @Param("dayEnd") OffsetDateTime dayEnd);

    /**
     * 昨日内容类型分布（chunks.metadata.contentType 计数）
     */
    @Select("""
            SELECT c.metadata->>'contentType' AS type,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
              AND jsonb_exists(c.metadata, 'contentType')
            GROUP BY c.metadata->>'contentType'
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectTypeStats(@Param("userId") UUID userId,
                                              @Param("dayStart") OffsetDateTime dayStart,
                                              @Param("dayEnd") OffsetDateTime dayEnd);

    /**
     * 昨日情绪分布（metadata.mood 数组展开计数）
     */
    @Select("""
            SELECT m.value AS mood,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            CROSS JOIN LATERAL jsonb_array_elements_text(c.metadata->'mood') AS m(value)
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
              AND jsonb_exists(c.metadata, 'mood')
            GROUP BY m.value
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectMoodStats(@Param("userId") UUID userId,
                                              @Param("dayStart") OffsetDateTime dayStart,
                                              @Param("dayEnd") OffsetDateTime dayEnd);

    /**
     * 昨日活跃时段（小时分布，Asia/Shanghai）
     */
    @Select("""
            SELECT EXTRACT(HOUR FROM r.created_at AT TIME ZONE 'Asia/Shanghai')::int AS bucket,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
            GROUP BY 1
            ORDER BY 1
            """)
    List<Map<String, Object>> selectHourDistribution(@Param("userId") UUID userId,
                                                     @Param("dayStart") OffsetDateTime dayStart,
                                                     @Param("dayEnd") OffsetDateTime dayEnd);

    /**
     * 昨日登记的未完成待办（<b>registry 口径</b>：todo-status-removal-design.md §11）
     *
     * <p>窗口语义不变（源头记录 {@code r.created_at ∈ [dayStart, dayEnd)}），
     * "未完成"判据由 chunk 快照 {@code taskStatus} 改为 registry 实时 {@code t.current_status}
     * ——日报"待办遗留"要的是"昨天记的、现在还没做完的"，chunk 快照过期即失真。</p>
     *
     * <p>过滤/排序与 {@code ProfileStatsMapper.selectOpenTodos} 同款（registry 主表 +
     * INNER JOIN chunks 排 orphan + records 侧 source='user'/status='done'/未删除）。</p>
     */
    @Select("""
            SELECT COALESCE(c.metadata->>'title', t.title) AS title,
                   c.metadata->>'summary' AS summary
            FROM todo_registry t
            JOIN chunks c ON c.id = t.source_chunk_id
            JOIN records r ON r.id = c.record_id
            WHERE t.user_id = #{userId}::uuid
              AND t.deleted_at IS NULL
              AND t.current_status != 'completed'
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
            ORDER BY r.created_at DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectOpenTodos(@Param("userId") UUID userId,
                                              @Param("dayStart") OffsetDateTime dayStart,
                                              @Param("dayEnd") OffsetDateTime dayEnd);

    /**
     * 昨日 done 记录原文（供日报引用，最多 30 条）
     */
    @Select("""
            SELECT r.content,
                   TO_CHAR(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
            ORDER BY r.created_at ASC
            LIMIT 30
            """)
    List<Chunk> selectDayRecords(@Param("userId") UUID userId,
                                 @Param("dayStart") OffsetDateTime dayStart,
                                 @Param("dayEnd") OffsetDateTime dayEnd);
}
