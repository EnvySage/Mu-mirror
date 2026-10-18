package org.xianshen.mumirrorb.action;

import com.fasterxml.jackson.databind.JsonNode;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;
import java.util.Objects;
import java.util.UUID;

/** Immutable submission capability. Cannot be bound from JSON or minted by a tool. */
public final class AuthorizedAction {
    private final UUID actionId;
    private final UUID userId;
    private final String kind;
    private final JsonNode payload;
    private final JsonNode targetVersions;
    private final int executionVersion;

    private AuthorizedAction(ActionProposal p) {
        actionId = p.getId();
        userId = p.getUserId();
        kind = p.getKind();
        payload = p.getPayload().deepCopy();
        targetVersions = p.getTargetVersions().deepCopy();
        executionVersion = p.getExecutionVersion();
    }

    /** The permit has a private constructor owned by the confirmation service. */
    public static AuthorizedAction issue(ActionProposalServiceImpl.ConfirmationPermit permit, ActionProposal proposal) {
        Objects.requireNonNull(permit, "confirmation permit");
        return new AuthorizedAction(proposal);
    }

    public UUID getActionId() { return actionId; }
    public UUID getUserId() { return userId; }
    public String getKind() { return kind; }
    public JsonNode getPayload() { return payload.deepCopy(); }
    public JsonNode getTargetVersions() { return targetVersions.deepCopy(); }
    public int getExecutionVersion() { return executionVersion; }
}
