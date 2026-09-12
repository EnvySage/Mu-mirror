package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 记录确认审查请求体（PUT /records/{id}/confirm，可选）
 *
 * <p>todo-status-removal-design.md §4 契约：审核页选择待办状态后随记录入库一起提交：</p>
 * <pre>
 * {"todoResolutions":[{"suggestionId":123,"action":"confirmed","status":"completed"},
 *                     {"suggestionId":124,"action":"dismissed"}]}
 * </pre>
 *
 * <p>body 缺省（旧客户端）时行为：本记录 evidence 的全部 pending 建议一律作废（行为变化）。</p>
 */
@Data
@Schema(description = "记录确认审查请求体（可选）")
public class RecordConfirmDTO {

    @Schema(description = "待办决议列表（缺省 = 本记录未处理建议一律作废）")
    private List<TodoResolutionDTO> todoResolutions;
}
