package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索结果条目（对话四路检索统一返回结构）
 *
 * <p>对应 mirror_chat.proto 的 RetrievedChunk（组装 ChatRequest.chunks 用）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "检索结果条目")
public class RetrievedChunkDTO {

    @Schema(description = "记录ID", example = "1")
    private Long recordId;

    @Schema(description = "片段文本")
    private String content;

    @Schema(description = "AI 标题", example = "学Spring Boot")
    private String title;

    @Schema(description = "记录时间（Asia/Shanghai，YYYY-MM-DD HH:mm）", example = "2026-09-03 14:30")
    private String createdAt;

    @Schema(description = "内容类型（英文小写，可空）", example = "learning")
    private String contentType;

    /**
     * 排序分值：SEMANTIC/HYBRID = 余弦距离（可含时间衰减），STRUCTURED 恒为 1.0；
     * 越小越相关，组装 RetrievedChunk.score 时转为 1/(1+distance) 相似度
     */
    @Schema(description = "排序分值（距离，越小越相关）")
    private Double score;
}
