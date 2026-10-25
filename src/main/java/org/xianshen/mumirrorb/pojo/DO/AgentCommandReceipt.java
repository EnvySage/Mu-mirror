package org.xianshen.mumirrorb.pojo.DO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Success-only idempotency receipt for a named Java command. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommandReceipt {
    private UUID userId;
    private UUID callId;
    private UUID runId;
    private String command;
    private String requestHash;
    private String resultJson;
    private OffsetDateTime createdAt;
}
