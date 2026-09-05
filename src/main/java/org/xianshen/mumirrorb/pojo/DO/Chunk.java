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
import org.xianshen.mumirrorb.common.typehandler.JsonbMapTypeHandler;
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
    @Schema(description = "切片内容（原始全文）", example = "今天学习了Spring Security的核心概念...")
    private String content;

    /**
     * 主题片段（用于 embedding）
     *
     * <p>AI 拆分后的主题摘要，embedding 基于此字段生成。</p>
     * <p>非拆分场景下与 content 相同。</p>
     */
    @Schema(description = "主题片段（用于embedding）", example = "今天学习了Spring Security的核心概念...")
    private String segment;

    /**
     * 元数据（JSONB）
     *
     * <p>包含：contentType, mood, title, summary, createdAt 等</p>
     * <p>用于向量检索后的元数据过滤</p>
     */
    @TableField(typeHandler = JsonbMapTypeHandler.class)
    @Schema(description = "元数据（类型、情绪、时间等）", example = "{\"contentType\":\"learning\",\"mood\":[\"happy\"]}")
    private Map<String, Object> metadata;

    /**
     * 生成当前 metadata 时所用的 segment 文本
     *
     * <p>状态机（设计文档 5.3，confirm 补分类判据 5.4）：</p>
     * <ul>
     *   <li>AI 分类回填 metadata 时 → 写入当时文本</li>
     *   <li>用户改动 segment 文本时 → 置 NULL（元数据编辑不影响它）</li>
     *   <li>手动新增 Chunk → 初始为 NULL</li>
     * </ul>
     * <p>NULL = 这段文本从未被分类过或已被用户改过，confirm 时需补分类。</p>
     */
    @Schema(description = "生成当前metadata时所用的segment文本；NULL=未分类或文本已改", example = "今天上午学了Spring Boot")
    private String classifiedSegment;

    /**
     * 用户是否编辑过（文本或元数据）
     *
     * <p>不参与业务逻辑，用于统计"AI 拆分被人工修正的比例"（论文数据点，13.5）。</p>
     */
    @Schema(description = "用户是否编辑过（文本或元数据）", example = "false")
    private Boolean userEdited;

    /**
     * vault 资产挂链（toolcalling-vault-design.md 3.3 全消化）
     *
     * <p>vault 文件全消化时其文本走 chunk 管道，此列 = 所属 vault_item_id；
     * vault 硬删除时 FK ON DELETE CASCADE 级联清 chunk（向量库无孤儿）。</p>
     */
    @Schema(description = "所属vault资产ID（全消化挂链；普通记录 chunk 为 null）", example = "1")
    private Long vaultItemId;

    /**
     * 向量嵌入（pgvector）
     *
     * <p>维度硬约束 1024（设计文档 3.4，裁决 #18），与 HNSW 索引列一致。</p>
     */
    @TableField(typeHandler = VectorTypeHandler.class)
    @Schema(description = "向量嵌入（1024 维）")
    private List<Float> embedding;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-08-12T14:30:00+08:00")
    private OffsetDateTime createdAt;
}
