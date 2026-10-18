package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Current todo state, with explicit record/direct origin and an optimistic version. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "todo_registry", autoResultMap = true)
@Schema(description = "待办登记 - 对应 todo_registry 表")
public class TodoRegistry {

    @TableId(type = IdType.AUTO)
    @Schema(description = "登记ID（自增主键）", example = "1")
    private Long id;

    /** 关联用户ID */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    /** 待办标题（来自 chunk metadata.title） */
    @Schema(description = "待办标题", example = "补作业")
    private String title;

    @Builder.Default
    private String originKind = "record";

    @Builder.Default
    private Long version = 1L;

    private String notes;

    private OffsetDateTime dueAt;

    /** 当前状态（真源；not_started/in_progress/completed） */
    @Schema(description = "当前状态", example = "not_started",
            allowableValues = {"not_started", "in_progress", "completed"})
    private String currentStatus;

    /** 原始待办片段（登记时唯一判据；chunk 被删 → SET NULL → orphan 不进注入清单） */
    @Schema(description = "原始待办chunk ID", example = "42")
    private Long sourceChunkId;

    @Schema(description = "登记时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    /** completed 时刻（追溯；Asia/Shanghai） */
    @Schema(description = "完成时刻（completed 时有值）")
    private OffsetDateTime closedAt;

    /**
     * 软删时间戳（todo-status-removal-design.md）
     *
     * <p>删除 = 特例（侧栏直删+弹框）：registry 行保留 + deleted_at 落值（不动 auto 已定稿代码）。
     * 非 null = 已删除：所有视图（清单/注入/证据链/统计）过滤，不再产生新建议、不可改状态。
     * 来源片段保留，只更新本表和关联建议。</p>
     */
    @Schema(description = "软删时间戳（非 null = 已删除）")
    private OffsetDateTime deletedAt;
}
