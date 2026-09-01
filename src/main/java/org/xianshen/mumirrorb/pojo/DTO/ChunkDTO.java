package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Chunk 更新请求 DTO
 *
 * <p>审核阶段用户修改 Chunk 的 segment 和元数据时使用。</p>
 * <p>所有字段均为可选，只传需要修改的字段。</p>
 */
@Data
@Schema(description = "Chunk更新DTO - 审核时修改Chunk使用")
public class ChunkDTO {

    /**
     * 主题片段（用户可修改拆分结果）
     */
    @Schema(description = "主题片段（修改拆分结果）", example = "今天上午学了Spring Boot")
    private String segment;

    /**
     * 标题
     */
    @Schema(description = "标题", example = "学Spring Boot")
    private String title;

    /**
     * 摘要
     */
    @Schema(description = "摘要", example = "学习了Spring Boot核心概念")
    private String summary;

    /**
     * 内容类型
     */
    @Schema(description = "内容类型", example = "learning",
            allowableValues = {"todo", "thought", "learning", "plan", "note", "work", "social", "health"})
    private String contentType;

    /**
     * 情绪标签
     */
    @Schema(description = "情绪标签", example = "[\"happy\", \"productive\"]")
    private List<String> mood;

    /**
     * 关键词
     */
    @Schema(description = "关键词", example = "[\"Spring\", \"Java\"]")
    private List<String> keywords;
}
