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
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记录实体类（对应 records 表）
 *
 * <p>用户的输入日志，仅存储原始内容和拆分片段。</p>
 * <p>AI 生成的元数据（title, contentType, mood 等）存储在 Chunk 的 metadata 中。</p>
 *
 * <p><strong>状态流转：</strong></p>
 * <pre>
 *   创建 → PROCESSING（AI处理中）
 *              ↓
 *          REVIEWING（人工审查）← 用户可修改 Chunk 元数据
 *              ↓
 *            DONE（已完成）
 *
 *   任何阶段都可能 → FAILED（处理失败）
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "records", autoResultMap = true)
@Schema(description = "记录实体 - 对应 records 表")
public class Record {

    /**
     * 记录ID（自增主键）
     */
    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID（自增主键）", example = "1")
    private Long id;

    /**
     * 关联用户ID
     */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    /**
     * 用户原始输入内容
     */
    @Schema(description = "用户原始输入内容（不可修改）", example = "今天学习了Spring Security的核心概念...")
    private String content;

    /**
     * AI 拆分后的主题片段数组（JSONB）
     *
     * <p>一条记录可能包含多个主题，AI 拆分后每个片段存为数组元素。</p>
     * <p>非拆分场景下数组只有一个元素。</p>
     *
     * <p><strong>示例：</strong></p>
     * <pre>
     * 输入: "今天上午学了Spring Boot，下午去健身"
     * segment: ["今天上午学了Spring Boot", "下午去健身"]
     * </pre>
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    @Schema(description = "AI拆分后的主题片段数组", example = "[\"上午学了Spring Boot\", \"下午去健身\"]")
    private List<String> segment;

    /**
     * 处理状态
     */
    @Schema(description = "处理状态", example = "reviewing")
    private RecordStatus status;

    /**
     * 用户是否已审核
     */
    @Schema(description = "用户是否已审核", example = "false")
    private Boolean userReviewed;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-08-07T14:30:00+08:00")
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-08-07T15:45:00+08:00")
    private OffsetDateTime updatedAt;

    /**
     * 软删除时间（NULL 表示未删除）
     */
    @Schema(description = "软删除时间（NULL表示未删除）", example = "null")
    private OffsetDateTime deletedAt;

    /**
     * Chunk 元数据列表（非数据库字段，不持久化）
     *
     * <p>由 ClassifyProcessor 生成，EventListener 读取后创建 Chunk 记录。</p>
     * <p>每个 Map 包含：title, summary, contentType, mood, keywords</p>
     */
    @TableField(exist = false)
    @Schema(description = "Chunk元数据列表（临时字段，不持久化）")
    private List<Map<String, Object>> chunkMetadataList;
}
