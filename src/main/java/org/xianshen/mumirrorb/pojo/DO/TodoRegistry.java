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

/**
 * 待办登记实体（对应 todo_registry 表，todo-registry-design.md §2）
 *
 * <p>跨日记待办状态跟踪的状态实体。核心哲学（设计稿 §1 裁决）：</p>
 * <ul>
 *   <li>真源唯一（裁决 #33）：chunk.metadata.taskStatus 保持唯一真源，
 *       registry.current_status 是索引（物化），状态变更时事务内双写</li>
 *   <li>独立 todo 字典，不混 user_terms（裁决 #1）：有生命周期的状态实体，
 *       非解释性词条（top30 注入对话），混用挤占注入上限</li>
 *   <li>orphan 语义：source_chunk_id 被删（ON DELETE SET NULL）→ 注册表行保留，
 *       注入清单 JOIN 排除（不物理改行）</li>
 * </ul>
 */
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

    /** 当前状态（物化索引；not_started/in_progress/completed） */
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
}
