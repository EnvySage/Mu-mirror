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
              AND c.metadata ? 'contentType'
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
              AND c.metadata ? 'mood'
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
     * 昨日未完成待办（contentType in todo/plan 且 taskStatus != completed）
     */
    @Select("""
            SELECT c.metadata->>'title' AS title,
                   c.metadata->>'summary' AS summary
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND r.created_at >= #{dayStart}
              AND r.created_at < #{dayEnd}
              AND c.metadata->>'contentType' IN ('todo', 'plan')
              AND COALESCE(c.metadata->>'taskStatus', 'not_started') != 'completed'
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
