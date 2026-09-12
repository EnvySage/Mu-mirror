package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 待办证据链 VO（todo-registry-design.md §2 links: origin/evidence，GET /todos/open-chain）
 *
 * <p>一条未完成待办 = 一条链：origin（登记时原始片段，理论必有、代码判空）+
 * evidence[]（用户确认建议时背书的后续证据，按 date ASC）+ pendingSuggestionCount。
 * currentStatus 以 chunk.metadata.taskStatus 实时值为准（真源 #33，registry 是物化索引）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "待办证据链条目")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoChainVO {

    @Schema(description = "登记ID", example = "5")
    private Long todoId;

    @Schema(description = "待办标题", example = "计划补文献综述")
    private String title;

    @Schema(description = "当前状态（chunk.metadata.taskStatus 实时值）", example = "in_progress")
    private String currentStatus;

    @Schema(description = "登记时间（yyyy-MM-dd HH:mm:ss 上海时区）", example = "2026-09-12 09:15:17")
    private String createdAt;

    @Schema(description = "原始片段（登记时 origin link；理论必有，可空）")
    private ChainRef origin;

    @Schema(description = "证据列表（evidence link，按 date ASC）")
    private List<ChainEvidence> evidence;

    @Schema(description = "pending 建议数（该待办待裁决建议条数）", example = "1")
    private Long pendingSuggestionCount;

    /**
     * 链上片段引用（origin 用；excerpt 取 COALESCE(segment, content) 截 60 字符）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "链上片段引用")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChainRef {
        @Schema(description = "片段ID", example = "29")
        private Long chunkId;

        @Schema(description = "所属记录ID", example = "10301")
        private Long recordId;

        @Schema(description = "片段摘录（≤60 字符）")
        private String excerpt;

        @Schema(description = "片段时刻（yyyy-MM-dd HH:mm 上海时区）", example = "2026-09-12 09:15")
        private String date;
    }

    /**
     * 证据条目（evidence 用：ChainRef + confirmedAt 用户背书时刻，取 link.created_at）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "证据条目")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChainEvidence {
        @Schema(description = "片段ID", example = "17")
        private Long chunkId;

        @Schema(description = "所属记录ID", example = "10012")
        private Long recordId;

        @Schema(description = "片段摘录（≤60 字符）")
        private String excerpt;

        @Schema(description = "片段时刻（yyyy-MM-dd HH:mm 上海时区）", example = "2026-09-09 23:45")
        private String date;

        @Schema(description = "背书确认时刻（evidence link created_at，yyyy-MM-dd HH:mm 上海时区）",
                example = "2026-09-09 23:50")
        private String confirmedAt;
    }

    /**
     * GET /todos/open-chain 响应包装
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "证据链列表响应")
    public static class ChainListVO {
        @Schema(description = "证据链列表（未完成待办，按登记时间新→旧）")
        private List<TodoChainVO> chains;
    }
}
