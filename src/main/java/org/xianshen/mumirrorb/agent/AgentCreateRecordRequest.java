package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Strict input for the create-record command; title and date are never accepted. */
public record AgentCreateRecordRequest(
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("call_id") UUID callId,
        String content
) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("unsupported create command field");
    }
}
