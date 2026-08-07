package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.util.List;

/**
 * 记录请求 DTO（创建 + 更新共用）
 *
 * <p><strong>创建时：</strong></p>
 * <ul>
 *   <li>content 必填（用户原始输入内容）</li>
 *   <li>其他字段由 AI 生成，不需要传</li>
 * </ul>
 *
 * <p><strong>更新时（仅 REVIEWING 状态允许）：</strong></p>
 * <ul>
 *   <li>content 不能修改（保持原始输入）</li>
 *   <li>只传需要修改的字段：title, summary, contentType, mood, keywords</li>
 *   <li>更新后 userReviewed 会自动设为 true</li>
 * </ul>
 */
@Data
@Schema(description = "记录请求DTO - 创建和更新记录时使用")
public class RecordDTO {

    /**
     * 用户输入的内容
     *
     * <p>创建时必填，更新时不要传此字段（不能修改原始内容）</p>
     * <p>最大长度 2000 字</p>
     */
    @Size(max = 2000, message = "内容长度不能超过 2000 字")
    @Schema(
            description = "用户输入的原始内容（创建时必填，更新时不传）",
            example = "今天学习了Spring Security的核心概念...",
            maxLength = 2000,
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String content;

    /**
     * 标题（AI 生成，审核时可修改）
     *
     * <p>AI 会根据内容自动生成 10 字以内的标题</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Size(max = 200, message = "标题长度不能超过 200 字")
    @Schema(
            description = "标题（AI生成，人工审查时可修改）",
            example = "学习Spring Security",
            maxLength = 200
    )
    private String title;

    /**
     * 摘要（AI 生成，审核时可修改）
     *
     * <p>AI 会根据内容自动生成 30 字以内的摘要</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Size(max = 500, message = "摘要长度不能超过 500 字")
    @Schema(
            description = "摘要（AI生成，人工审查时可修改）",
            example = "学习了Spring Security的核心概念和配置方法",
            maxLength = 500
    )
    private String summary;

    /**
     * 内容类型
     *
     * <p>AI 会自动识别内容类型，如：todo, thought, learning, plan 等</p>
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
     * <p>AI 会分析内容中的情绪，生成多个标签</p>
     * <p>在人工审查状态下可以修改</p>
     */
    @Schema(
            description = "情绪标签（AI自动识别，人工审查时可修改，支持多选）",
            example = "[\"happy\", \"productive\", \"calm\"]"
    )
    private List<String> mood;

    /**
     * 处理状态
     *
     * <p>此字段为只读，由系统自动管理，不需要手动设置</p>
     */
    @Schema(
            description = "处理状态（只读，由系统自动管理）",
            example = "reviewing",
            accessMode = Schema.AccessMode.READ_ONLY,
            allowableValues = {"processing", "reviewing", "done", "failed"}
    )
    private RecordStatus status;

    /**
     * 关键词列表（替换原有标签）
     *
     * <p>AI 会根据内容提取关键词作为标签</p>
     * <p>在人工审查状态下可以修改，修改后会同步更新 tags 表</p>
     */
    @Schema(
            description = "关键词标签列表（AI自动提取，人工审查时可修改）",
            example = "[\"Spring\", \"安全\", \"Java\", \"学习\"]"
    )
    private List<String> keywords;
}
