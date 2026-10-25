package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class ActionProposalVO {
    private UUID id;
    private String kind;
    private String state;
    private int version;
    private String preparationStatus;
    private JsonNode payload;
    private JsonNode preview;
    private JsonNode targetVersions;
    private UUID sourceSessionId;
    private Long sourceMessageId;
    private Integer executionVersion;
    private JsonNode result;
    private String errorCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static ActionProposalVO from(ActionProposal p) {
        return builder().id(p.getId()).kind(p.getKind()).state(p.getState()).version(p.getVersion())
                .preparationStatus(p.getPreparationStatus()).payload(copy(p.getPayload())).preview(copy(p.getPreview()))
                .targetVersions(copy(p.getTargetVersions())).sourceSessionId(p.getSourceSessionId())
                .sourceMessageId(p.getSourceMessageId()).executionVersion(p.getExecutionVersion())
                .result(copy(p.getResult())).errorCode(p.getErrorCode())
                .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt()).build();
    }

    private static JsonNode copy(JsonNode node) { return node == null ? null : node.deepCopy(); }
}
