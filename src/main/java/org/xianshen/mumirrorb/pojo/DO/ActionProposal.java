package org.xianshen.mumirrorb.pojo.DO;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class ActionProposal {
    private UUID id;
    private UUID userId;
    private String kind;
    private String prepareKey;
    private String state;
    private int version;
    private String preparationStatus;
    private JsonNode payload;
    private JsonNode preview;
    private JsonNode targetVersions;
    private UUID sourceSessionId;
    private Long sourceMessageId;
    private String requestId;
    private Integer executionVersion;
    private JsonNode result;
    private String errorCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
