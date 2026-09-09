package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 待办建议卡 VO（todo-registry-design.md §3.3 裁决期，F 侧侧栏建议卡数据源）
 *
 * <p>GET /todos/pending-suggestions 返回结构：{suggestions: [...]}；
 * 侧栏角标数字 = suggestions.length。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "待办状态建议卡")
public class TodoSuggestionVO {

    @Schema(description = "建议ID", example = "1")
    private Long id;

    @Schema(description = "待办登记ID", example = "1")
    private Long todoId;

    @Schema(description = "待办标题", example = "补作业")
    private String title;

    @Schema(description = "待办当前状态", example = "not_started")
    private String currentStatus;

    @Schema(description = "建议状态（机器猜的）", example = "completed")
    private String suggestedStatus;

    @Schema(description = "触发建议的chunk ID（evidence 候选，确认时才落关联）", example = "77")
    private Long evidenceChunkId;

    @Schema(description = "触发建议的记录 ID（F 跳记录详情）", example = "17")
    private Long evidenceRecordId;

    @Schema(description = "触发建议的片段文本（截 160 字符，建议卡展示）")
    private String evidenceExcerpt;

    @Schema(description = "建议产生时间（yyyy-MM-dd HH:mm:ss 上海时区）")
    private String createdAt;

    /**
     * 待办建议卡条目（pending-suggestions 列表项）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "pending 建议卡")
    public static class SuggestionCard {
        @Schema(description = "建议ID", example = "1")
        private Long id;

        @Schema(description = "待办登记ID", example = "1")
        private Long todoId;

        @Schema(description = "待办标题", example = "补作业")
        private String title;

        @Schema(description = "待办当前状态", example = "not_started")
        private String currentStatus;

        @Schema(description = "建议状态", example = "completed")
        private String suggestedStatus;

        @Schema(description = "触发片段文本（截 160 字符）")
        private String evidenceExcerpt;

        @Schema(description = "触发片段的记录 ID", example = "17")
        private Long evidenceRecordId;

        @Schema(description = "建议产生时间")
        private String createdAt;
    }

    /**
     * pending-suggestions 响应包装
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "pending 建议列表（侧栏角标=列表长度）")
    public static class SuggestionListVO {
        @Schema(description = "pending 建议卡列表")
        private List<SuggestionCard> suggestions;
    }
}
