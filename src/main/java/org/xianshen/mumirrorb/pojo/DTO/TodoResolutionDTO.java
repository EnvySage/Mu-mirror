package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待办决议条目（审核页 confirm 提交时随记录一起生效）
 *
 * <p>todo-status-removal-design.md §4 契约：条目分两类，<b>suggestionId 与 todoId 恰好提供其一</b>：</p>
 * <pre>
 * // ① 裁决 AI 建议（现有）
 * {"suggestionId":123,"action":"confirmed","status":"completed"}
 * {"suggestionId":124,"action":"dismissed"}
 * // ② 用户主动挂载已注册 todo（新增，无建议）
 * {"todoId":6,"action":"confirmed","status":"in_progress"}
 * </pre>
 * <ul>
 *   <li>suggestionId 分支：action=confirmed（status 必填三态）→ 状态随入库生效；
 *       action=dismissed → 忽略该建议（永久静默），status 忽略</li>
 *   <li>todoId 分支（用户主动挂载）：action 必须为 confirmed（无"忽略"语义——不想挂载就不提交该行；
 *       传 dismissed → 400），status 必填三态；用户不想改状态时预填该 todo 当前状态即可（契约不新增特例）</li>
 *   <li>两者都无 / 都有 → 400（防歧义）</li>
 * </ul>
 */
@Data
@Schema(description = "待办决议条目（suggestionId 与 todoId 恰好其一）")
public class TodoResolutionDTO {

    @Schema(description = "建议ID（裁决 AI 建议路径；与 todoId 恰好其一）", example = "123")
    private Long suggestionId;

    @Schema(description = "待办登记ID（用户主动挂载路径，无建议；与 suggestionId 恰好其一）", example = "6")
    private Long todoId;

    @Schema(description = "动作：confirmed / dismissed（todoId 分支仅允许 confirmed）", example = "confirmed",
            allowableValues = {"confirmed", "dismissed"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String action;

    @Schema(description = "目标三态（action=confirmed 时必填）", example = "completed",
            allowableValues = {"not_started", "in_progress", "completed"})
    private String status;
}
