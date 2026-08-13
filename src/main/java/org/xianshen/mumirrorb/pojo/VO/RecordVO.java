package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记录视图对象（列表 + 详情通用）
 *
 * <p>用于返回给前端的记录数据结构，包含：</p>
 * <ul>
 *   <li>记录基本信息（id, content, createdAt）</li>
 *   <li>AI 生成的信息（title, summary, contentType, mood, keywords）</li>
 *   <li>状态信息（status, userReviewed）</li>
 * </ul>
 *
 * <p><strong>状态说明：</strong></p>
 * <ul>
 *   <li>processing - AI 正在处理（前端显示转圈动画）</li>
 *   <li>reviewing - 人工审查中（AI 处理完成，等待用户审核标签）</li>
 *   <li>done - 已完成（用户已确认标签）</li>
 *   <li>failed - 处理失败（显示错误 + 重新尝试按钮）</li>
 * </ul>
 *
 * <p><strong>使用场景：</strong></p>
 * <ul>
 *   <li>列表查询：GET /api/records</li>
 *   <li>详情查询：GET /api/records/{id}</li>
 *   <li>创建响应：POST /api/records</li>
 *   <li>更新响应：PUT /api/records/{id}</li>
 *   <li>确认响应：PUT /api/records/{id}/confirm</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "记录视图对象 - 返回给前端的记录数据结构")
public class RecordVO {

    /**
     * 记录ID
     */
    @Schema(
            description = "记录ID（自增主键）",
            example = "1"
    )
    private Long id;

    /**
     * 原始内容
     *
     * <p>用户创建时提交的原始文本，不可修改</p>
     */
    @Schema(
            description = "用户输入的原始内容（不可修改）",
            example = "今天学习了Spring Security的核心概念..."
    )
    private String content;

    /**
     * AI 生成的标题
     *
     * <p>AI 根据内容自动生成的标题，10字以内</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "AI生成的标题（10字以内，人工审查时可修改）",
            example = "学习Spring Security"
    )
    private String title;

    /**
     * AI 生成的摘要
     *
     * <p>AI 根据内容自动生成的摘要，30字以内</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "AI生成的摘要（30字以内，人工审查时可修改）",
            example = "学习了Spring Security的核心概念和配置方法"
    )
    private String summary;

    /**
     * 内容类型
     *
     * <p>AI 自动识别的内容类型分类</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "内容类型（AI自动识别，人工审查时可修改）",
            example = "learning",
            allowableValues = {"todo", "thought", "learning", "plan", "note", "work", "social", "health"}
    )
    private ContentType contentType;

    /**
     * 情绪标签（多选）
     *
     * <p>AI 分析内容中的情绪，生成多个标签</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "情绪标签（AI自动识别，支持多选，人工审查时可修改）",
            example = "[\"happy\", \"productive\"]"
    )
    private List<String> mood;

    /**
     * 处理状态
     *
     * <p>记录的当前处理状态，由系统自动管理</p>
     */
    @Schema(
            description = "处理状态（由系统自动管理）",
            example = "reviewing",
            allowableValues = {"processing", "reviewing", "done", "failed"}
    )
    private RecordStatus status;

    /**
     * 用户是否已审核
     *
     * <p>true: 用户已审核（已调用更新或确认接口）</p>
     * <p>false: 用户未审核（等待用户审查）</p>
     */
    @Schema(
            description = "用户是否已审核AI生成的标签",
            example = "false"
    )
    private Boolean userReviewed;

    /**
     * 关键词标签列表
     *
     * <p>AI 根据内容自动提取的关键词</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "关键词标签列表（AI自动提取，人工审查时可修改）",
            example = "[\"Spring\", \"安全\", \"Java\", \"学习\"]"
    )
    private List<String> keywords;

    /**
     * 创建时间
     *
     * <p>记录创建的时间戳，格式：yyyy-MM-dd HH:mm:ss</p>
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(
            description = "创建时间",
            example = "2026-08-07 14:30:00",
            format = "date-time",
            type = "string"
    )
    private OffsetDateTime createdAt;

    /**
     * 原始记录ID（拆分场景）
     *
     * <p>当记录是由 AI 拆分生成时，此字段指向原始记录的 ID。</p>
     * <p>前端可通过此字段判断是否为拆分记录，并将同一 originalRecordId 的记录分组展示。</p>
     *
     * <p><strong>前端使用示例：</strong></p>
     * <pre>
     * // 判断是否为拆分记录
     * const isSplit = record.originalRecordId !== null;
     *
     * // 获取同一拆分组的所有记录
     * const splitGroup = records.filter(r =>
     *     r.originalRecordId === record.originalRecordId ||
     *     r.id === record.originalRecordId
     * );
     * </pre>
     */
    @Schema(
            description = "原始记录ID（拆分场景，指向原记录，非拆分为null）",
            example = "null"
    )
    private Long originalRecordId;

    /**
     * 更新时间
     *
     * <p>记录最后一次更新的时间戳，格式：yyyy-MM-dd HH:mm:ss</p>
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(
            description = "更新时间",
            example = "2026-08-07 15:45:00",
            format = "date-time",
            type = "string"
    )
    private OffsetDateTime updatedAt;
}
