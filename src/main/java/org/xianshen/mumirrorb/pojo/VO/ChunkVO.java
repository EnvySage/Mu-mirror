package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Chunk 视图对象
 *
 * <p>返回给前端的 Chunk 数据结构，包含主题片段和 AI 元数据。</p>
 *
 * <p><strong>前端使用：</strong></p>
 * <ul>
 *   <li>列表页：用 metadata.title 展示摘要</li>
 *   <li>审核页：展示 segment 和 metadata，用户可编辑</li>
 *   <li>确认后：hasEmbedding 变为 true</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chunk视图对象 - 主题片段及AI元数据")
public class ChunkVO {

    /**
     * Chunk ID
     */
    @Schema(description = "Chunk ID", example = "1")
    private Long id;

    /**
     * 关联记录ID
     */
    @Schema(description = "关联记录ID", example = "1")
    private Long recordId;

    /**
     * 主题片段（可编辑）
     */
    @Schema(description = "主题片段（用户可编辑）", example = "今天上午学了Spring Boot")
    private String segment;

    /**
     * AI 元数据（可编辑）
     *
     * <p>包含：title, summary, contentType, mood, keywords 等</p>
     */
    @Schema(description = "AI元数据（用户可编辑）",
            example = "{\"title\":\"学Spring Boot\",\"contentType\":\"learning\",\"mood\":[\"happy\"]}")
    private Map<String, Object> metadata;

    /**
     * 是否已生成 embedding
     */
    @Schema(description = "是否已生成embedding", example = "false")
    private Boolean hasEmbedding;
}
