package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Java-owned lifecycle record for one accepted chat request. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_runs")
public class AgentRun {

    @TableId
    private UUID runId;
    private UUID userId;
    private UUID sessionId;
    private UUID clientRequestId;
    private String requestHash;
    private String engine;
    private String mode;
    private UUID parentRunId;
    private String questionHash;
    private String status;
    private String outcome;
    private Long userMessageId;
    private Long assistantMessageId;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime leaseExpiresAt;
    private String stopReason;
    private String errorCode;
    private Integer planRounds;
    private Integer toolCallCount;
    private Long lastEventSeq;
    private Boolean glossaryHitsApplied;
    private String metricsJson;
}
