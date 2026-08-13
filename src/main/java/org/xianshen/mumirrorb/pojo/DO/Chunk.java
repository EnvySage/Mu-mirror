package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.handler.VectorTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量块实体类（对应 chunks 表）
 *
 * <p>用于 RAG 检索的向量存储，每条记录审核通过后，其内容会被向量化并存储到此表。</p>
 *
 * <p><strong>设计要点：</strong></p>
 * <ul>
 *   <li>一条记录 = 一个 chunk（整条记录作为一个向量）</li>
 *   <li>向量维度取决于 embedding 模型（BGE-m3 默认 1024 维）</li>
 *   <li>metadata 存储元数据（类型、情绪、时间等），用于过滤</li>
 *   <li>Embedding 失败不影响记录确认，后续可补录</li>
 * </ul>
 *
 * <p><strong>向量检索流程：</strong></p>
 * <pre>
 *   用户提问 → Embedding → pgvector 相似度检索 → 返回相关 chunks → LLM 生成回答
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chunks", autoResultMap = true)
@Schema(description = "向量块实体 - 对应 chunks 表")
public class Chunk {

    /**
     * 向量块ID（自增主键）
     */
    @TableId(type = IdType.AUTO)
    @Schema(description = "向量块ID（自增主键）", example = "1")
    private Long id;

    /**
     * 关联用户ID
     */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    /**
     * 关联记录ID
     */
    @Schema(description = "关联记录ID", example = "1")
    private Long recordId;

    /**
     * 切片内容（整条记录的原始内容）
     */
    @Schema(description = "切片内容", example = "今天学习了Spring Security的核心概念...")
    private String content;

    /**
     * 元数据（JSONB）
     *
     * <p>包含：contentType, mood, title, summary, createdAt 等</p>
     * <p>用于向量检索后的元数据过滤</p>
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    @Schema(description = "元数据（类型、情绪、时间等）", example = "{\"contentType\":\"learning\",\"mood\":[\"happy\"]}")
    private Map<String, Object> metadata;

    /**
     * 向量嵌入（pgvector）
     *
     * <p>维度取决于 embedding 模型：</p>
     * <ul>
     *   <li>BGE-m3 本地：1024 维</li>
     *   <li>OpenAI text-embedding-3-small：1536 维</li>
     * </ul>
     */
    @TableField(typeHandler = VectorTypeHandler.class)
    @Schema(description = "向量嵌入（维度取决于模型）")
    private List<Float> embedding;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-08-12T14:30:00+08:00")
    private OffsetDateTime createdAt;
}
