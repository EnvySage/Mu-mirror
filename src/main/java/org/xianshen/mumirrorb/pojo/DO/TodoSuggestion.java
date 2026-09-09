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
 * 待办状态变更建议实体（对应 todo_suggestions 表，todo-registry-design.md §2）
 *
 * <p>机器猜的（LLM 判别 refers_to_todo 回传），pending 等用户裁决：</p>
 * <ul>
 *   <li>确认：事务内三写（chunk.metadata.taskStatus + registry.current_status/closed_at
 *       + evidence link）+ 建议行 confirmed</li>
 *   <li>忽略：建议行 dismissed（永久静默——同一证据不再提示），todo 不动，关联不落</li>
 *   <li>侧栏直调（PUT /todos/{id}/status）：该待办的 pending 建议全部作废
 *       （status=dismissed，用户手动改了，机器建议作废）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "todo_suggestions", autoResultMap = true)
@Schema(description = "待办状态建议 - 对应 todo_suggestions 表")
public class TodoSuggestion {

    @TableId(type = IdType.AUTO)
    @Schema(description = "建议ID（自增主键）", example = "1")
    private Long id;

    /** 关联用户ID */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    @Schema(description = "待办登记ID", example = "1")
    private Long todoId;

    /** 触发建议的新日记片段（evidence 候选；确认时才落 evidence link） */
    @Schema(description = "触发建议的chunk ID", example = "77")
    private Long evidenceChunkId;

    @Schema(description = "建议状态", example = "completed",
            allowableValues = {"not_started", "in_progress", "completed"})
    private String suggestedStatus;

    /** pending / confirmed / dismissed */
    @Schema(description = "建议处理状态", example = "pending",
            allowableValues = {"pending", "confirmed", "dismissed"})
    private String status;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "裁决时间（confirmed/dismissed 时有值）")
    private OffsetDateTime resolvedAt;
}
