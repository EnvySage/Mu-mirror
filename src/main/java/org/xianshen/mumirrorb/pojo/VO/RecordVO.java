package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记录视图对象（列表 + 详情通用）
 *
 * <p>返回给前端的记录数据结构。Record 是输入日志，AI 元数据在 Chunk 中。</p>
 *
 * <p><strong>前端渲染：</strong></p>
 * <ul>
 *   <li>列表页：展示 content（原文）+ chunks 中的 title 摘要</li>
 *   <li>详情/审核页：展示每个 chunk 的 segment、metadata，用户可编辑</li>
 *   <li>确认后：状态变为 DONE，chunks 包含 embedding</li>
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
    @Schema(description = "记录ID（自增主键）", example = "1")
    private Long id;

    /**
     * 原始内容
     */
    @Schema(description = "用户输入的原始内容（不可修改）", example = "今天学习了Spring Security的核心概念...")
    private String content;

    /**
     * AI 拆分后的主题片段数组
     */
    @Schema(description = "AI拆分后的主题片段数组", example = "[\"上午学了Spring Boot\", \"下午去健身\"]")
    private List<String> segment;

    /**
     * 处理状态
     */
    @Schema(description = "处理状态", example = "reviewing",
            allowableValues = {"processing", "reviewing", "done", "failed"})
    private RecordStatus status;

    /**
     * 用户是否已审核
     */
    @Schema(description = "用户是否已审核", example = "false")
    private Boolean userReviewed;

    /**
     * 关联的 Chunk 列表
     *
     * <p>每个 chunk 包含一个主题片段及其 AI 元数据。</p>
     * <p>列表页可用 chunks[*].metadata.title 展示摘要。</p>
     * <p>审核页可编辑每个 chunk 的 segment 和 metadata。</p>
     */
    @Schema(description = "关联的Chunk列表（包含主题片段和AI元数据）")
    private List<ChunkVO> chunks;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间", example = "2026-08-07 14:30:00")
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间", example = "2026-08-07 15:45:00")
    private OffsetDateTime updatedAt;
}
