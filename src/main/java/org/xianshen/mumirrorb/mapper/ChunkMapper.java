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
     *
     * @param userId      用户ID（隔离不同用户的数据）
     * @param queryVector 查询向量（由 Python Embedding 服务生成）
     * @param limit       返回数量限制
     * @return 相似的 chunks 列表（按相似度排序）
     */
    @Select("""
            SELECT id, user_id, record_id, content, metadata, created_at,
                   1 - (embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks
            WHERE user_id = #{userId}::uuid
            ORDER BY embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchBySimilarity(@Param("userId") UUID userId,
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
            SELECT id, user_id, record_id, content, metadata, created_at,
                   1 - (embedding <=> #{queryVector}::vector) AS similarity
            FROM chunks
            WHERE user_id = #{userId}::uuid
              AND (#{contentType} IS NULL OR metadata->>'contentType' = #{contentType})
            ORDER BY embedding <=> #{queryVector}::vector
            LIMIT #{limit}
            """)
    List<Chunk> searchBySimilarityWithFilter(@Param("userId") UUID userId,
                                              @Param("queryVector") String queryVector,
                                              @Param("contentType") String contentType,
                                              @Param("limit") int limit);
}
