package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 待办登记列表 VO（todo-registry-design.md §4-B，侧栏"全部待办"入口数据源）
 *
 * <p>GET /todos 返回 {todos: [...]}；orphan 行（原 chunk 被删）带 isOrphan=true，
 * 不进判别注入清单但列表可见（数据不丢失）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "待办登记条目（列表用）")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoItemVO {

    @Schema(description = "登记ID", example = "1")
    private Long id;

    @Schema(description = "待办标题", example = "补作业")
    private String title;

    @Schema(description = "当前状态", example = "not_started")
    private String currentStatus;

    @Schema(description = "原始待办chunk ID", example = "42")
    private Long sourceChunkId;

    @Schema(description = "原始片段摘录（≤100 字符）")
    private String sourceExcerpt;

    @Schema(description = "是否 orphan（原 chunk 已删；不进判别注入）", example = "false")
    private Boolean orphan;

    @Schema(description = "关联日记数（origin + evidence）", example = "2")
    private Long linkCount;

    @Schema(description = "pending 建议数", example = "1")
    private Long pendingSuggestionCount;

    @Schema(description = "登记时间（yyyy-MM-dd HH:mm:ss 上海时区）")
    private String createdAt;

    @Schema(description = "完成时刻（completed 时有值）")
    private String closedAt;

    /**
     * GET /todos 响应包装
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "待办列表响应")
    public static class TodoListVO {
        @Schema(description = "登记行列表")
        private List<TodoItemVO> todos;
    }
}
