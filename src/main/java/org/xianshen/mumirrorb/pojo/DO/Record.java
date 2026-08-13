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
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 记录实体类（对应 records 表）
 *
 * <p>存储用户的日记记录，包括：</p>
 * <ul>
 *   <li>用户原始输入内容（content）</li>
 *   <li>AI 生成的信息（title, summary, contentType, mood）</li>
 *   <li>处理状态（status）</li>
 *   <li>软删除标记（deletedAt）</li>
 * </ul>
 *
 * <p><strong>状态流转：</strong></p>
 * <pre>
 *   创建 → PROCESSING（AI处理中）
 *              ↓
 *          REVIEWING（人工审查）← 用户可修改标签
 *              ↓
 *            DONE（已完成）
 *
 *   任何阶段都可能 → FAILED（处理失败）
 * </pre>
 *
 * <p><strong>软删除：</strong></p>
 * <ul>
 *   <li>deletedAt 为 NULL 表示未删除</li>
 *   <li>deletedAt 有值表示已删除（软删除）</li>
 *   <li>查询时自动过滤已删除的记录</li>
 * </ul>
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
     * 关联用户ID（数据库和 Java 都使用 UUID 类型）
     */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    /**
     * 用户原始输入内容
     *
     * <p>创建时提供的内容，后续不可修改</p>
     */
    @Schema(description = "用户原始输入内容（不可修改）", example = "今天学习了Spring Security的核心概念...")
    private String content;

    /**
     * AI 生成的标题（10字以内）
     *
     * <p>由 AI 根据内容自动生成，在 REVIEWING 状态下可修改</p>
     */
    @Schema(description = "AI生成的标题（10字以内）", example = "学习Spring Security")
    private String title;

    /**
     * AI 生成的摘要（30字以内）
     *
     * <p>由 AI 根据内容自动生成，在 REVIEWING 状态下可修改</p>
     */
    @Schema(description = "AI生成的摘要（30字以内）", example = "学习了Spring Security的核心概念和配置方法")
    private String summary;

    /**
     * 内容类型（枚举：todo/thought/learning/plan/note/work/social/health）
     *
     * <p>由 AI 自动识别，在 REVIEWING 状态下可修改</p>
     */
    @Schema(description = "内容类型（AI自动识别）", example = "learning")
    private ContentType contentType;

    /**
     * 情绪标签（JSONB 数组，多选，如 ["happy", "calm"]）
     *
     * <p>由 AI 分析内容中的情绪生成，在 REVIEWING 状态下可修改</p>
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    @Schema(description = "情绪标签（JSONB数组，支持多选）", example = "[\"happy\", \"calm\"]")
    private List<String> mood;

    /**
     * 处理状态（枚举：processing/reviewing/done/failed）
     *
     * <p>由系统自动管理，用户不可直接修改</p>
     */
    @Schema(description = "处理状态", example = "reviewing")
    private RecordStatus status;

    /**
     * 用户是否已审核 AI 生成的标签
     *
     * <p>true: 用户已审核（已调用更新或确认接口）</p>
     * <p>false: 用户未审核（等待用户审查）</p>
     */
    @Schema(description = "用户是否已审核AI生成的标签", example = "false")
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
     *
     * <p>设置此字段表示记录已被软删除，查询时会自动过滤</p>
     */
    @Schema(description = "软删除时间（NULL表示未删除）", example = "null")
    private OffsetDateTime deletedAt;

    /**
     * 原始记录ID（拆分场景）
     *
     * <p>当记录是由 AI 拆分生成时，此字段指向原始记录的 ID。</p>
     * <p>非拆分场景下为 NULL。</p>
     *
     * <p><strong>示例：</strong></p>
     * <pre>
     * 用户输入: "今天上午学了Spring Boot，下午去健身"
     * 拆分后：
     *   - 记录42: originalRecordId = NULL （原记录）
     *   - 记录43: originalRecordId = 42  （拆分生成的新记录）
     * </pre>
     */
    @Schema(description = "原始记录ID（拆分场景，指向原记录，非拆分为NULL）", example = "null")
    private Long originalRecordId;

    /**
     * 关键词标签（非数据库字段，不持久化）
     *
     * <p>由管道 ClassifyProcessor 生成，Service 层取出后存入 tags 表</p>
     */
    @TableField(exist = false)
    @Schema(description = "关键词标签（临时字段，不持久化）", example = "[\"Spring\", \"安全\", \"Java\"]")
    private List<String> keywords;
}
