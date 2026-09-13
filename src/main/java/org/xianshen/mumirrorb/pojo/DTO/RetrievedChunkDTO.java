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
     * 余弦距离（越小越相关）。约定：&ge; 0 = 真实距离（SEMANTIC/HYBRID）；
     * &lt; 0 = "无相似度信息"哨兵（STRUCTURED 命中、画像快照）——上层据此不给该条打相关度标记。
     * 时间衰减只作用于 SQL 的 ORDER BY，不掺进本字段。
     */
    @Schema(description = "余弦距离（越小越相关）；<0 表示无相似度信息")
    private Double score;

    @Schema(description = "vault 资产 ID（非空 = 这条是文件资产的 keyChunk，不是日记片段）")
    private Long vaultItemId;
}
