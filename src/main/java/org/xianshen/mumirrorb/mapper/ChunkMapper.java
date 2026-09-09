package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.Chunk;

import java.util.List;
import java.util.UUID;

/**
 * 向量块 Mapper
 *
 * <p>用于 RAG 检索的向量存储和查询。</p>
 *
 * <p><strong>向量检索方法：</strong></p>
 * <ul>
 *   <li>{@link #searchBySimilarity} - 余弦相似度检索（pgvector <=> 操作符）</li>
 *   <li>{@link #searchBySimilarityWithFilter} - 带元数据过滤的相似度检索</li>
 * </ul>
 */
@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {

    /**
     * 向量相似度检索（余弦距离）
     *
     * <p>使用 pgvector 的 <=> 操作符计算余弦距离，返回最相似的 chunks。</p>
     * <p>JOIN records 排除软删除记录（技术债"检索排除软删除"）；
     *    软删除记录的 chunks 物理保留，由 SQL 关联过滤。</p>
     *
     * @param userId      用户ID（隔离不同用户的数据）
     * @param queryVector 查询向量（由 Python Embedding 服务生成）
     * @param limit       返回数量限制
     * @return 相似的 chunks 列表（按相似度排序）
     */
    @Select("""
            SELECT c.id, c.user_id, c.record_id, c.content, c.segment, c.metadata, c.created_at,
                   1 - (c.embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchBySimilarity(@Param("userId") UUID userId,
                                    @Param("queryVector") String queryVector,
                                    @Param("limit") int limit);

    /**
     * vault 三层漏斗 ③ 全文层：消化 chunks 向量检索（只查挂了 vault_item_id 的 chunk）
     *
     * <p>与 {@link #searchBySimilarity} 同算法；范围限定 vault 全消化产物（find_item 第③层）。</p>
     */
    @Select("""
            SELECT c.id, c.user_id, c.record_id, c.content, c.segment, c.metadata, c.vault_item_id, c.created_at,
                   1 - (c.embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks c
            WHERE c.user_id = #{userId}::uuid
              AND c.embedding IS NOT NULL
              AND c.vault_item_id IS NOT NULL
            ORDER BY c.embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchVaultBySimilarity(@Param("userId") UUID userId,
                                        @Param("queryVector") String queryVector,
                                        @Param("limit") int limit);

    /**
     * recall_item 内容问答层：某文件的全文 chunks 按与 query 相似度取 top-N
     *
     * <p>范围限定单个 vault 文件的消化产物；排除 keyChunk（key chunk 是元数据拼合文本
     * 非正文，不该作为内容问答的段落返回）。与 {@link #searchBySimilarity} 同算法（pgvector &lt;=&gt;）。</p>
     *
     * @param userId      用户ID（隔离不同用户的数据）
     * @param itemId      vault 资产ID（只取该文件的消化 chunks）
     * @param queryVector 查询向量（由 Python Embedding 服务生成）
     * @param limit       返回数量限制
     * @return 相似的 chunks 列表（按相似度排序，similarity 字段携带余弦相似度）
     */
    @Select("""
            SELECT c.id, c.user_id, c.record_id, c.content, c.segment, c.metadata, c.vault_item_id, c.created_at,
                   1 - (c.embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks c
            WHERE c.user_id = #{userId}::uuid
              AND c.vault_item_id = #{itemId}
              AND c.embedding IS NOT NULL
              AND (c.metadata->>'keyChunk' IS NULL OR c.metadata->>'keyChunk' <> 'true')
            ORDER BY c.embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchByItemAndSimilarity(@Param("userId") UUID userId,
                                          @Param("itemId") Long itemId,
                                          @Param("queryVector") String queryVector,
                                          @Param("limit") int limit);

    /**
     * 带内容类型过滤的向量相似度检索
     *
     * @param userId      用户ID
     * @param queryVector 查询向量
     * @param contentType 内容类型过滤（可为 null）
     * @param limit       返回数量限制
     * @return 相似的 chunks 列表
     */
    @Select("""
            SELECT c.id, c.user_id, c.record_id, c.content, c.segment, c.metadata, c.created_at,
                   1 - (c.embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND c.embedding IS NOT NULL
              AND (#{contentType} IS NULL OR c.metadata->>'contentType' = #{contentType})
            ORDER BY c.embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchBySimilarityWithFilter(@Param("userId") UUID userId,
                                              @Param("queryVector") String queryVector,
                                              @Param("contentType") String contentType,
                                              @Param("limit") int limit);

    /**
     * 回看窗口原文（rolling-mirror-design.md §2：按 lookback 档位带②本月原始记录）
     *
     * <p>时间窗 [since, until)：until 开区间；旧→新升序（截断时保留最近 N 条由 Service 层实现，
     * SQL LIMIT 是条数闸硬上限兜底）。只带 source='user'、未删除、非 failed 记录的 chunk；
     * segment 优先渲染（用户可编辑的唯一真源，裁决 #2），空则回退 content 由 Service 判。</p>
     */
    @Select("""
            <script>
            SELECT c.id, c.user_id, c.record_id, c.content, c.segment, c.metadata, c.created_at
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status != 'failed'
              AND c.vault_item_id IS NULL
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            ORDER BY r.created_at ASC, c.id ASC
            <if test="limit &gt; 0">LIMIT #{limit}</if>
            </script>
            """)
    List<Chunk> selectLookbackChunks(@Param("userId") UUID userId,
                                     @Param("since") java.time.OffsetDateTime since,
                                     @Param("until") java.time.OffsetDateTime until,
                                     @Param("limit") int limit);

    /**
     * 校正索引源数据（rolling-mirror-design.md §1③：上期镜子涉及的记录 title+日期清单）
     *
     * <p>与 lookback 窗口同口径（user 记录、未删除、非 failed），取窗口内全部 chunk 的
     * title + 日期；lookback=0 时随请求携带（唯一防误差手段）。</p>
     */
    @Select("""
            <script>
            SELECT c.metadata->>'title' AS title,
                   TO_CHAR(r.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS record_date
            FROM chunks c
            JOIN records r ON r.id = c.record_id
            WHERE c.user_id = #{userId}::uuid
              AND r.deleted_at IS NULL
              AND r.source = 'user'
              AND r.status != 'failed'
              AND c.vault_item_id IS NULL
              AND c.metadata->>'title' IS NOT NULL
              AND c.metadata->>'title' != ''
              <if test="since != null">AND r.created_at &gt;= #{since}</if>
              <if test="until != null">AND r.created_at &lt; #{until}</if>
            ORDER BY r.created_at ASC, c.id ASC
            </script>
            """)
    List<java.util.Map<String, Object>> selectCorrectionIndex(@Param("userId") UUID userId,
                                                              @Param("since") java.time.OffsetDateTime since,
                                                              @Param("until") java.time.OffsetDateTime until);
}
