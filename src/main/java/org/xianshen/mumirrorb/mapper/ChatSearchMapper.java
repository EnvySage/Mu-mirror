package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话四路检索 Mapper（设计文档 6.6）
 *
 * <p>ExtractIntent 返回 query_type 后按路由调用：</p>
 * <ul>
 *   <li>STRUCTURED → {@link #searchStructured}：纯 SQL 元数据过滤，不走向量</li>
 *   <li>SEMANTIC  → {@link #searchSemantic}：pgvector 余弦距离（可叠加时间衰减）</li>
 *   <li>HYBRID    → {@link #searchHybrid}：元数据预过滤 + 向量 + 时间衰减</li>
 *   <li>PROFILE   → 不走本 Mapper，查 ProfileSnapshotMapper</li>
 * </ul>
 *
 * <p>全部参数化占位符（安全约定）；过滤条件用 {@code (#{x} IS NULL OR ...)} 可选拼接。</p>
 */
@Mapper
public interface ChatSearchMapper {

    /**
     * STRUCTURED 路由：SQL 元数据过滤（不走向量），按时间倒序
     *
     * @param contentType 8 类英文小写（可空）
     * @param moods       13 情绪英文小写（可空，命中任一即可）
     * @param timeStart   time_range 解析出的起始时间（可空）
     * @param timeEnd     time_range 解析出的结束时间（可空）
     */
    @Select("""
            <script>
            SELECT c.record_id,
                   COALESCE(c.segment, c.content) AS content,
                   c.metadata->>'title'           AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS created_at,
                   COALESCE(c.metadata->>'contentType', '') AS content_type,
                   1.0 AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.classified_segment IS NOT NULL
              <if test="contentType != null">AND c.metadata->>'contentType' = #{contentType}</if>
              <if test="moods != null and moods.size() > 0">AND jsonb_exists_any(c.metadata->'mood', #{moodArray}::text[])</if>
              <if test="timeStart != null">AND r.created_at &gt;= #{timeStart}</if>
              <if test="timeEnd != null">AND r.created_at &lt; #{timeEnd}</if>
            ORDER BY r.created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunkDTO> searchStructured(@Param("userId") UUID userId,
                                             @Param("contentType") String contentType,
                                             @Param("moods") List<String> moods,
                                             @Param("moodArray") String moodArray,
                                             @Param("timeStart") OffsetDateTime timeStart,
                                             @Param("timeEnd") OffsetDateTime timeEnd,
                                             @Param("limit") int limit);

    /**
     * SEMANTIC 路由：pgvector 余弦距离检索
     *
     * @param queryVector  改写后 query 的向量（pgvector 文本字面量 "[0.1,0.2,...]"）
     * @param applyDecay   是否叠加时间衰减（ExtractIntent 返回 time_range 时为 false）
     * @param halfLifeDays 衰减半衰期（天，user_settings.rag_half_life，默认 30）
     */
    @Select("""
            <script>
            SELECT c.record_id,
                   COALESCE(c.segment, c.content) AS content,
                   c.metadata->>'title'           AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS created_at,
                   COALESCE(c.metadata->>'contentType', '') AS content_type,
                   <choose>
                     <when test="applyDecay">
                       (c.embedding &lt;=&gt; #{queryVector}::vector)
                       * 1.0 / (1 + EXTRACT(EPOCH FROM (NOW() - r.created_at)) / 86400.0 / #{halfLifeDays})
                     </when>
                     <otherwise>
                       (c.embedding &lt;=&gt; #{queryVector}::vector)
                     </otherwise>
                   </choose> AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.embedding IS NOT NULL
              AND c.classified_segment IS NOT NULL
            ORDER BY score ASC
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunkDTO> searchSemantic(@Param("userId") UUID userId,
                                           @Param("queryVector") String queryVector,
                                           @Param("applyDecay") boolean applyDecay,
                                           @Param("halfLifeDays") double halfLifeDays,
                                           @Param("limit") int limit);

    /**
     * HYBRID 路由：元数据预过滤 + pgvector + 时间衰减
     *
     * <p>final_score = (embedding &lt;=&gt; query) × 1/(1 + 天数差/half_life)；
     * ExtractIntent 明确返回 time_range 时关闭衰减（applyDecay=false），
     * 且 timeStart/timeEnd 作为硬过滤条件收窄候选集。</p>
     */
    @Select("""
            <script>
            SELECT c.record_id,
                   COALESCE(c.segment, c.content) AS content,
                   c.metadata->>'title'           AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS created_at,
                   COALESCE(c.metadata->>'contentType', '') AS content_type,
                   <choose>
                     <when test="applyDecay">
                       (c.embedding &lt;=&gt; #{queryVector}::vector)
                       * 1.0 / (1 + EXTRACT(EPOCH FROM (NOW() - r.created_at)) / 86400.0 / #{halfLifeDays})
                     </when>
                     <otherwise>
                       (c.embedding &lt;=&gt; #{queryVector}::vector)
                     </otherwise>
                   </choose> AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND c.embedding IS NOT NULL
              AND c.classified_segment IS NOT NULL
              <if test="contentType != null">AND c.metadata->>'contentType' = #{contentType}</if>
              <if test="moods != null and moods.size() > 0">AND jsonb_exists_any(c.metadata->'mood', #{moodArray}::text[])</if>
              <if test="timeStart != null">AND r.created_at &gt;= #{timeStart}</if>
              <if test="timeEnd != null">AND r.created_at &lt; #{timeEnd}</if>
            ORDER BY score ASC
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunkDTO> searchHybrid(@Param("userId") UUID userId,
                                         @Param("queryVector") String queryVector,
                                         @Param("contentType") String contentType,
                                         @Param("moods") List<String> moods,
                                         @Param("moodArray") String moodArray,
                                         @Param("timeStart") OffsetDateTime timeStart,
                                         @Param("timeEnd") OffsetDateTime timeEnd,
                                         @Param("applyDecay") boolean applyDecay,
                                         @Param("halfLifeDays") double halfLifeDays,
                                         @Param("limit") int limit);
}
