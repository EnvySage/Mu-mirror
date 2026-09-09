package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 待办登记数据载体（todo-registry-design.md §3.2/§4-B）
 *
 * <p>TodoItem 供判别期注入（TodoHint proto 组装）、GET /todos 列表与
 * selectOneByUser 详情复用；字段是 todo_registry 行 + source chunk 摘要的展平。</p>
 */
public final class TodoRegistryDTO {

    private TodoRegistryDTO() {
    }

    /** open_todos 注入/列表条目（todoId=0 不允许出现，登记行必有主键） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "待办条目")
    public static class TodoItem {
        @Schema(description = "登记ID", example = "1")
        private Long todoId;

        @Schema(description = "用户ID")
        private UUID userId;

        @Schema(description = "待办标题（chunk metadata.title）", example = "补作业")
        private String title;

        @Schema(description = "当前状态", example = "not_started")
        private String currentStatus;

        @Schema(description = "原始待办chunk ID（orphan 时 null）", example = "42")
        private Long sourceChunkId;

        @Schema(description = "原始片段文本（注入摘要截 100 字符依据）")
        private String sourceExcerpt;

        @Schema(description = "原始片段 AI 摘要（excerpt 空时兜底）")
        private String sourceSummary;

        @Schema(description = "登记时间（ISO yyyy-MM-ddTHH:mm:ss）")
        private String createdAt;

        @Schema(description = "完成时刻（completed 时有值）")
        private OffsetDateTime closedAt;

        @Schema(description = "关联数（仅列表口径填充，其余为 null）")
        private Long linkCount;

        @Schema(description = "pending 建议数（仅列表口径填充，其余为 null）")
        private Long pendingCount;
    }
}
