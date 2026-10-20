package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentCommandResponse(
        @JsonProperty("call_id") UUID callId,
        String status,
        boolean replayed,
        AgentCreatedRecord record
) {
    public record AgentCreatedRecord(
            long id,
            String status,
            @JsonProperty("created_at") OffsetDateTime createdAt
    ) { }
}
