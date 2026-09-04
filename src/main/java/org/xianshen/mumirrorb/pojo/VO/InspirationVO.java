package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 写作灵感视图对象（POST /api/inspiration 返回，设计文档 6.8）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "写作灵感")
public class InspirationVO {

    /**
     * 2-3 条写作方向提示
     */
    @Schema(description = "写作方向提示（2-3 条）", example = "[\"结合上周的跑步记录写坚持的感受\", \"...\"]")
    private List<String> suggestions;

    /**
     * 依据的历史记录摘要（可点击溯源，不含向量）
     */
    @Schema(description = "依据的历史记录（title + createdAt）")
    private List<SourceItem> sources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "灵感来源记录")
    public static class SourceItem {

        @Schema(description = "记录ID", example = "1")
        private Long recordId;

        @Schema(description = "标题或片段摘要")
        private String title;

        @Schema(description = "记录时间", example = "2026-09-03 14:30")
        private String createdAt;
    }
}
