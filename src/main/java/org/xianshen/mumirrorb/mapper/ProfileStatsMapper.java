package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 画像五维统计 Mapper（设计文档 6.5：未完成待办 / 最近学习 / 情绪分布 / 关键词 / 活跃时段）
 *
 * <p>数据源：chunks.metadata（JSONB）JOIN records（口径：source='user'、未删除、DONE）。
 * 全部参数化占位符（安全约定）。</p>
 */
@Mapper
public interface ProfileStatsMapper {

    /**
     * 未完成待办：contentType in (todo, plan) 且 taskStatus != 'completed'
     * （taskStatus 由裁决 #16 落 metadata；缺 taskStatus 的旧数据视作未完成，一并纳入）
     */
    @Select("""
            SELECT r.id AS recordId,
                   c.metadata->>'title' AS title,
                   c.metadata->>'summary' AS summary,
                   COALESCE(c.metadata->>'taskStatus', 'not_started') AS taskStatus,
                   TO_CHAR(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.metadata->>'contentType' IN ('todo', 'plan')
              AND COALESCE(c.metadata->>'taskStatus', 'not_started') != 'completed'
            ORDER BY r.created_at DESC
            LIMIT 50
            """)
    List<ProfileStatsDTO.TodoItemDTO> selectOpenTodos(@Param("userId") java.util.UUID userId);

    /**
     * 待办/计划按任务状态计数（total = 各状态之和，chunk 粒度，与 selectOpenTodos 口径一致）
     *
     * <p>缺 taskStatus 的旧数据 COALESCE 归入 not_started（裁决 #16）。</p>
     */
    @Select("""
            SELECT COALESCE(c.metadata->>'taskStatus', 'not_started') AS status,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.metadata->>'contentType' IN ('todo', 'plan')
            GROUP BY 1
            """)
    List<java.util.Map<String, Object>> selectTodoStatusCounts(@Param("userId") java.util.UUID userId);

    /**
     * 最近学习条目（contentType = learning）
     */
    @Select("""
            SELECT r.id AS recordId,
                   c.metadata->>'title' AS title,
                   c.metadata->>'summary' AS summary,
                   c.metadata->'keywords' AS keywordsJson,
                   TO_CHAR(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.metadata->>'contentType' = 'learning'
            ORDER BY r.created_at DESC
            LIMIT 30
            """)
    List<java.util.Map<String, Object>> selectRecentLearningsRaw(@Param("userId") java.util.UUID userId);

    /**
     * 情绪分布（metadata.mood 数组展开计数）
     *
     * @param since 起始时间（如最近 30 天）；可空表示不限
     */
    @Select("""
            <script>
            SELECT m.value AS mood,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            CROSS JOIN LATERAL jsonb_array_elements_text(c.metadata->'mood') AS m(value)
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND jsonb_exists(c.metadata, 'mood')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY m.value
            ORDER BY count DESC
            </script>
            """)
    List<java.util.Map<String, Object>> selectMoodStats(@Param("userId") java.util.UUID userId,
                                                        @Param("since") OffsetDateTime since);

    /**
     * 按日情绪聚合（镜子统计页堆叠色带数据源）
     *
     * <p>日期按 Asia/Shanghai 本地时区切分（全站口径）；只返回有数据的天，
     * 缺失日期由 Service 层补零。</p>
     */
    @Select("""
            <script>
            SELECT TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS date,
                   m.value AS mood,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            CROSS JOIN LATERAL jsonb_array_elements_text(c.metadata->'mood') AS m(value)
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND jsonb_exists(c.metadata, 'mood')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY 1, 2
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectMoodDaily(@Param("userId") java.util.UUID userId,
                                                        @Param("since") OffsetDateTime since);

    /**
     * 按日记录数聚合（镜子统计页频率柱状图数据源，含无 chunk 的记录）
     *
     * <p>日期按 Asia/Shanghai 本地时区切分；只返回有记录的天，
     * 缺失日期由 Service 层补零。排除 failed 记录（与日历 countByDay 口径一致）。</p>
     */
    @Select("""
            <script>
            SELECT TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS date,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status != 'failed'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectRecordDaily(@Param("userId") java.util.UUID userId,
                                                          @Param("since") OffsetDateTime since);

    /**
     * 关键词频次（metadata.keywords 数组展开计数，取 Top N）
     */
    @Select("""
            <script>
            SELECT k.value AS keyword,
                   COUNT(*) AS count
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            CROSS JOIN LATERAL jsonb_array_elements_text(c.metadata->'keywords') AS k(value)
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND jsonb_exists(c.metadata, 'keywords')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY k.value
            ORDER BY count DESC
            LIMIT #{limit}
            </script>
            """)
    List<java.util.Map<String, Object>> selectKeywordStats(@Param("userId") java.util.UUID userId,
                                                           @Param("since") OffsetDateTime since,
                                                           @Param("limit") int limit);

    /**
     * 活跃时段：小时分布（按 Asia/Shanghai 本地时间），供 rhythm 维度
     */
    @Select("""
            <script>
            SELECT EXTRACT(HOUR FROM r.created_at AT TIME ZONE 'Asia/Shanghai')::int AS bucket,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectHourDistribution(@Param("userId") java.util.UUID userId,
                                                               @Param("since") OffsetDateTime since);

    /**
     * 活跃时段：星期分布（0=周日 … 6=周六，Asia/Shanghai）
     */
    @Select("""
            <script>
            SELECT EXTRACT(DOW FROM r.created_at AT TIME ZONE 'Asia/Shanghai')::int AS bucket,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectWeekdayDistribution(@Param("userId") java.util.UUID userId,
                                                                  @Param("since") OffsetDateTime since);

    /**
     * 统计范围内用户记录总数（含无 chunk 的记录）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
            </script>
            """)
    long countUserRecords(@Param("userId") java.util.UUID userId,
                          @Param("since") OffsetDateTime since);

    /**
     * 最近会话的对话（画像生成用 recent_chats：优先近 7 天，不足则前补，设计文档 6.5）
     *
     * @return Map keys: role / content / created_at(ISO 字符串)
     */
    @Select("""
            SELECT h.role,
                   h.content,
                   TO_CHAR(h.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt
            FROM conversation_history h
            WHERE h.user_id = #{userId}::uuid
            ORDER BY h.created_at DESC
            LIMIT #{limit}
            """)
    List<java.util.Map<String, Object>> selectRecentChats(@Param("userId") java.util.UUID userId,
                                                          @Param("limit") int limit);
}
