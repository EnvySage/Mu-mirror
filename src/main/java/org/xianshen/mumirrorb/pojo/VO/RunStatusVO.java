package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Safe owner-facing run status; intentionally excludes request hashes, metrics and grants. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对话运行状态")
public class RunStatusVO {
    private UUID runId;
    private UUID sessionId;
    private String status;
    private String outcome;
    private String stopReason;
    private String errorCode;
    private ChatSessionVO.MessageVO assistant;
}
