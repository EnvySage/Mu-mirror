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
 *
 * <p><b>例外：待办状态类查询以 todo_registry 为主表</b>（todo-status-removal-design.md §11）——
 * 镜像统计的两个待办查询（{@link #selectOpenTodos} / {@link #selectTodoStatusCounts}）读
 * {@code todo_registry.current_status}，chunks 只补展示字段。</p>
 */
@Mapper
public interface ProfileStatsMapper {

    /**
     * 未完成待办（<b>registry 口径</b>：todo-status-removal-design.md §11）
     *
     * <p>主表 = todo_registry（状态真源），chunks/records 只补展示字段（title/summary/recordId/createdAt）：</p>
     * <ul>
     *   <li>状态取 {@code t.current_status != 'completed'}——chunk.metadata.taskStatus 只是"登记时初值"，
     *       状态变更只更新 registry，chunk 快照会过期（统计不准的根因，2026-09-12 用户实测）</li>
     *   <li>{@code t.deleted_at IS NULL}：软删待办不可见；删除时同时打的 chunk 标记
     *       {@code todoRemoved} 无需重复过滤（registry 软删即是权威判据）</li>
     *   <li>INNER JOIN chunks（source_chunk_id）：天然排除 orphan，与
     *       {@code TodoRegistryMapper.selectOpenTodos} 同口径；registry 行只由 todo/plan
     *       片段登记产生（TodoRegistryServiceImpl.registerFromRecord），无需再判 contentType</li>
     *   <li>records 侧过滤保留（source='user'、status='done'、deleted_at IS NULL）：消费口径收口</li>
     * </ul>
     */
    @Select("""
            SELECT r.id AS recordId,
                   COALESCE(c.metadata->>'title', t.title) AS title,
                   c.metadata->>'summary' AS summary,
                   t.current_status AS taskStatus,
                   TO_CHAR(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS createdAt
            FROM todo_registry t
            JOIN chunks c ON c.id = t.source_chunk_id
            JOIN records r ON r.id = c.record_id
            WHERE t.user_id = #{userId}::uuid
              AND t.deleted_at IS NULL
              AND t.current_status != 'completed'
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
            ORDER BY r.created_at DESC
            LIMIT 50
            """)
    List<ProfileStatsDTO.TodoItemDTO> selectOpenTodos(@Param("userId") java.util.UUID userId);

    /**
     * 待办状态计数（<b>registry 口径</b>：todo-status-removal-design.md §11）
     *
     * <p>状态取 {@code t.current_status}（NOT NULL DEFAULT 'not_started'，无需 COALESCE），
     * total = 各状态之和（含 completed）；JOIN/filter 口径与 {@link #selectOpenTodos} 完全一致
     * （仅不排除 completed——计数卡要展示完成数）。</p>
     */
    @Select("""
            SELECT t.current_status AS status,
                   COUNT(*) AS count
            FROM todo_registry t
            JOIN chunks c ON c.id = t.source_chunk_id
            JOIN records r ON r.id = c.record_id
            WHERE t.user_id = #{userId}::uuid
              AND t.deleted_at IS NULL
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
            GROUP BY 1
            """)
    List<java.util.Map<String, Object>> selectTodoStatusCounts(@Param("userId") java.util.UUID userId);

    /**
     * 最近学习条目（contentType = learning）
     *
     * <p>驼峰别名必须保留双引号（"recordId"/"keywordsJson"/"createdAt"）：PG 对未加引号的标识符
     * 折叠为小写（recordId→recordid），而 MyBatis 对 Map 返回值按列名原样做 key，
     * 去掉引号会导致消费侧 row.get("recordId") 静默取不到值。</p>
     */
    @Select("""
            SELECT r.id AS "recordId",
                   c.metadata->>'title' AS title,
                   c.metadata->>'summary' AS summary,
                   c.metadata->'keywords' AS "keywordsJson",
                   TO_CHAR(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "createdAt"
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              AND c.metadata->>'contentType' = 'learning'
            ORDER BY r.created_at DESC
            LIMIT 30
            """)
    List<java.util.Map<String, Object>> selectRecentLearningsRaw(@Param("userId") java.util.UUID userId);

    /**
     * 情绪分布（metadata.mood 数组展开计数）
     *
     * <p>窗口 [since, until)：until 为开区间上界（按月统计截断用，如统计 8 月则 until=9/1 0 点）；
     * 传 null 表示不限。manually 生成只传 since（until=null 等价于无上界）。</p>
     *
     * @param since 起始时间（含）；可空表示不限
     * @param until 截止时间（不含）；可空表示不限
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
              AND r.status = 'done'
              AND jsonb_exists(c.metadata, 'mood')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY m.value
            ORDER BY count DESC
            </script>
            """)
    List<java.util.Map<String, Object>> selectMoodStats(@Param("userId") java.util.UUID userId,
                                                        @Param("since") OffsetDateTime since,
                                                        @Param("until") OffsetDateTime until);

    /**
     * 按日情绪聚合（镜子统计页堆叠色带数据源）
     *
     * <p>日期按 Asia/Shanghai 本地时区切分（全站口径）；只返回有数据的天，
     * 缺失日期由 Service 层补零。窗口 [since, until)（until 可空=不限）。</p>
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
              AND r.status = 'done'
              AND jsonb_exists(c.metadata, 'mood')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY 1, 2
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectMoodDaily(@Param("userId") java.util.UUID userId,
                                                        @Param("since") OffsetDateTime since,
                                                        @Param("until") OffsetDateTime until);

    /**
     * 按日记录数聚合（镜子统计页频率柱状图数据源，含无 chunk 的记录）
     *
     * <p>日期按 Asia/Shanghai 本地时区切分；只返回有记录的天，
     * 缺失日期由 Service 层补零。只统计已确认（status='done'）记录——消费口径收口，
     * 未确认（REVIEWING）数据不进画像统计。窗口 [since, until)（until 可空=不限）。</p>
     */
    @Select("""
            <script>
            SELECT TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS date,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectRecordDaily(@Param("userId") java.util.UUID userId,
                                                          @Param("since") OffsetDateTime since,
                                                          @Param("until") OffsetDateTime until);

    /**
     * 关键词频次（metadata.keywords 数组展开计数，取 Top N）。窗口 [since, until)（until 可空=不限）。
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
              AND r.status = 'done'
              AND jsonb_exists(c.metadata, 'keywords')
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY k.value
            ORDER BY count DESC
            LIMIT #{limit}
            </script>
            """)
    List<java.util.Map<String, Object>> selectKeywordStats(@Param("userId") java.util.UUID userId,
                                                           @Param("since") OffsetDateTime since,
                                                           @Param("until") OffsetDateTime until,
                                                           @Param("limit") int limit);

    /**
     * 活跃时段：小时分布（按 Asia/Shanghai 本地时间），供 rhythm 维度。窗口 [since, until)（until 可空=不限）。
     */
    @Select("""
            <script>
            SELECT EXTRACT(HOUR FROM r.created_at AT TIME ZONE 'Asia/Shanghai')::int AS bucket,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectHourDistribution(@Param("userId") java.util.UUID userId,
                                                               @Param("since") OffsetDateTime since,
                                                               @Param("until") OffsetDateTime until);

    /**
     * 活跃时段：星期分布（0=周日 … 6=周六，Asia/Shanghai）。窗口 [since, until)（until 可空=不限）。
     */
    @Select("""
            <script>
            SELECT EXTRACT(DOW FROM r.created_at AT TIME ZONE 'Asia/Shanghai')::int AS bucket,
                   COUNT(*) AS count
            FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            GROUP BY 1
            ORDER BY 1
            </script>
            """)
    List<java.util.Map<String, Object>> selectWeekdayDistribution(@Param("userId") java.util.UUID userId,
                                                                  @Param("since") OffsetDateTime since,
                                                                  @Param("until") OffsetDateTime until);

    /**
     * 统计范围内用户记录总数（含无 chunk 的记录）。窗口 [since, until)（until 可空=不限）。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM records r
            WHERE r.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status = 'done'
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            </script>
            """)
    long countUserRecords(@Param("userId") java.util.UUID userId,
                          @Param("since") OffsetDateTime since,
                          @Param("until") OffsetDateTime until);

    /**
     * 最近会话的对话（画像生成用 recent_chats：优先近 7 天，不足则前补，设计文档 6.5）
     *
     * <p>"createdAt" 别名必须保留双引号（原因同 selectRecentLearningsRaw：防 PG 折叠小写导致
     * Map key 与消费侧读取失配）。</p>
     *
     * @return Map keys: role / content / createdAt(ISO 字符串)
     */
    @Select("""
            SELECT h.role,
                   h.content,
                   TO_CHAR(h.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "createdAt"
            FROM conversation_history h
            WHERE h.user_id = #{userId}::uuid
            ORDER BY h.created_at DESC
            LIMIT #{limit}
            """)
    List<java.util.Map<String, Object>> selectRecentChats(@Param("userId") java.util.UUID userId,
                                                          @Param("limit") int limit);
}
