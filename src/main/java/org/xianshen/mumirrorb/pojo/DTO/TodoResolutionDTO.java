package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待办决议条目（审核页 confirm 提交时随记录一起生效）
 *
 * <p>todo-status-removal-design.md §4 契约：</p>
 * <pre>
 * {"suggestionId":123,"action":"confirmed","status":"completed"}
 * {"suggestionId":124,"action":"dismissed"}
 * </pre>
 * <ul>
 *   <li>action=confirmed：status 必填（not_started/in_progress/completed），入库时一起生效</li>
 *   <li>action=dismissed：忽略该建议（永久静默），status 忽略</li>
 * </ul>
 */
@Data
@Schema(description = "待办决议条目")
public class TodoResolutionDTO {

    @Schema(description = "建议ID", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long suggestionId;

    @Schema(description = "动作：confirmed / dismissed", example = "confirmed",
            allowableValues = {"confirmed", "dismissed"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String action;

    @Schema(description = "目标三态（action=confirmed 时必填）", example = "completed",
            allowableValues = {"not_started", "in_progress", "completed"})
    private String status;
}
