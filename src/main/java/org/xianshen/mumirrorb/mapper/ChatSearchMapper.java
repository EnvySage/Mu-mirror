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
 *
 * <p>fix-batch B1（Y1）：删除 {@code r.source='user'} 过滤——系统记录（每日总结 source='system'）
 * 与 vault 资产进通用检索。vault 侧由白名单谓词收口（Q3 key-embed + 确认门禁）：
 * 只有 <b>confirmed 资产的 keyChunk</b>（元数据 keyChunk='true'）进通用对话检索——
 * 全文消化 chunk 不进对话上下文（§3.3c「内容问答走 recall_item」，防全文灌入 prompt），
 * 未确认资产检索不到（§3.3b 确认门禁）。SEMANTIC/HYBRID 另有 embedding IS NOT NULL
 * （确认前不 embed，天然挡住）；STRUCTURED 无向量过滤，谓词是唯一防线。</p>
 *
 * <p>fix（REVIEWING 被消费修复）：STRUCTURED 补 {@code r.status = 'done'}——未确认记录
 * （reviewing）不得进对话 RAG 检索。SEMANTIC/HYBRID 依赖 embedding IS NOT NULL 天然安全
 * （reviewing 数据无向量），保持不动。</p>
 */
@Mapper
public interface ChatSearchMapper {

    /**
     * STRUCTURED 路由：SQL 元数据过滤（不走向量），按时间倒序
     *
     * <p>score 固定 {@code -1}：结构化命中没有相似度可言，用负数充当"无相关性信息"哨兵，
     * 上层据此不给该条打相关度标记（约定：score &lt; 0 = 无信息；score ≥ 0 = 余弦距离）。</p>
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
                   c.vault_item_id                AS vault_item_id,
                   -1.0 AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.status = 'done'
              AND (r.source &lt;&gt; 'vault' OR c.metadata->>'keyChunk' = 'true')
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
     * <p><b>时间衰减方向</b>：余弦距离 {@code <=>} 是"越小越相似"（0=同向），
     * 而排序是 {@code ORDER BY score ASC}，所以时间权重必须做成<b>除数</b>
     * （等价于 {@code 距离 × (1 + 天数差/半衰期)}）：越旧分越大 → 排越后。
     * 早期写成 {@code 距离 × 1/(1+天数差/半衰期)} 会把旧记录"加分"排到最前
     * （半衰期 30 天、距今 60 天的记录因子 0.33，分数被压小反而优先命中）。</p>
     *
     * <p><b>相关性下限</b>：maxDistance 非空时按<b>纯余弦距离</b>过滤（不混衰减因子，
     * 否则阈值含义随时间漂移）；全部被滤掉则返回空 → 上层走"没有找到相关记录"兜底。</p>
     *
     * <p><b>vault 放宽一档</b>：文件资产进检索的只有一条 keyChunk，内容是
     * "文件名：描述，类型"（{@code DigestService.buildKeyText}），比整段日记短得多、
     * 语义匹配天然偏弱——用同一把尺子会把"问我的设计文档"误杀成"没找到"。
     * 故 vault 记录的阈值放宽 0.15（谓词见各 SQL 的 CASE）。</p>
     *
     * <p><b>返回的 score</b> = <b>纯余弦距离</b>（不掺衰减），供上层换算成相关度呈现给 LLM
     * 判断证据强弱；时间衰减只作用于 ORDER BY，不再污染 score 语义。</p>
     *
     * @param queryVector  改写后 query 的向量（pgvector 文本字面量 "[0.1,0.2,...]"）
     * @param applyDecay   是否叠加时间衰减（ExtractIntent 返回 time_range 时为 false）
     * @param halfLifeDays 衰减半衰期（天，user_settings.rag_half_life，默认 30）
     * @param maxDistance  相关性下限（余弦距离，越小越严）；null = 不启用
     */
    @Select("""
            <script>
            SELECT c.record_id,
                   COALESCE(c.segment, c.content) AS content,
                   c.metadata->>'title'           AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS created_at,
                   COALESCE(c.metadata->>'contentType', '') AS content_type,
                   c.vault_item_id                AS vault_item_id,
                   (c.embedding &lt;=&gt; #{queryVector}::vector) AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND (r.source &lt;&gt; 'vault' OR c.metadata->>'keyChunk' = 'true')
              AND c.embedding IS NOT NULL
              AND c.classified_segment IS NOT NULL
              <if test="maxDistance != null">AND (c.embedding &lt;=&gt; #{queryVector}::vector)
                  &lt; (CASE WHEN r.source = 'vault' THEN #{maxDistance} + 0.15 ELSE #{maxDistance} END)</if>
            ORDER BY
              <choose>
                <when test="applyDecay">
                  (c.embedding &lt;=&gt; #{queryVector}::vector)
                  * (1 + EXTRACT(EPOCH FROM (NOW() - r.created_at)) / 86400.0 / #{halfLifeDays})
                </when>
                <otherwise>
                  (c.embedding &lt;=&gt; #{queryVector}::vector)
                </otherwise>
              </choose> ASC
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunkDTO> searchSemantic(@Param("userId") UUID userId,
                                           @Param("queryVector") String queryVector,
                                           @Param("applyDecay") boolean applyDecay,
                                           @Param("halfLifeDays") double halfLifeDays,
                                           @Param("maxDistance") Double maxDistance,
                                           @Param("limit") int limit);

    /**
     * HYBRID 路由：元数据预过滤 + pgvector + 时间衰减
     *
     * <p>排序键 = (embedding &lt;=&gt; query) × (1 + 天数差/half_life)：
     * 距离越小越相似、排序 ASC，所以权重做除数让旧记录分变大排后
     * （详见 {@link #searchSemantic} 的衰减方向说明）。
     * ExtractIntent 明确返回 time_range 时关闭衰减（applyDecay=false），
     * 且 timeStart/timeEnd 作为硬过滤条件收窄候选集。
     * 返回的 score 同 SEMANTIC：纯余弦距离，衰减不掺进 score。</p>
     */
    @Select("""
            <script>
            SELECT c.record_id,
                   COALESCE(c.segment, c.content) AS content,
                   c.metadata->>'title'           AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS created_at,
                   COALESCE(c.metadata->>'contentType', '') AS content_type,
                   c.vault_item_id                AS vault_item_id,
                   (c.embedding &lt;=&gt; #{queryVector}::vector) AS score
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND (r.source &lt;&gt; 'vault' OR c.metadata->>'keyChunk' = 'true')
              AND c.embedding IS NOT NULL
              AND c.classified_segment IS NOT NULL
              <if test="contentType != null">AND c.metadata->>'contentType' = #{contentType}</if>
              <if test="moods != null and moods.size() > 0">AND jsonb_exists_any(c.metadata->'mood', #{moodArray}::text[])</if>
              <if test="timeStart != null">AND r.created_at &gt;= #{timeStart}</if>
              <if test="timeEnd != null">AND r.created_at &lt; #{timeEnd}</if>
              <if test="maxDistance != null">AND (c.embedding &lt;=&gt; #{queryVector}::vector)
                  &lt; (CASE WHEN r.source = 'vault' THEN #{maxDistance} + 0.15 ELSE #{maxDistance} END)</if>
            ORDER BY
              <choose>
                <when test="applyDecay">
                  (c.embedding &lt;=&gt; #{queryVector}::vector)
                  * (1 + EXTRACT(EPOCH FROM (NOW() - r.created_at)) / 86400.0 / #{halfLifeDays})
                </when>
                <otherwise>
                  (c.embedding &lt;=&gt; #{queryVector}::vector)
                </otherwise>
              </choose> ASC
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
                                         @Param("maxDistance") Double maxDistance,
                                         @Param("limit") int limit);
}
