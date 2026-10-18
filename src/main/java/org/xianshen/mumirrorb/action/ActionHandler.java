package org.xianshen.mumirrorb.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

/** Domain adapters are server beans, never model tools or HTTP submission endpoints. */
public interface ActionHandler {
    Set<String> kinds();

    /**
     * Validate ownership and payload; return before/after/effects plus a mandatory object
     * field "targetVersions" (empty for creation). Versions must come from the database,
     * never from untrusted payload. This method must not make business writes.
     * On confirmation it runs inside the proposal transaction: lock target rows in a
     * consistent order, or guard execute's SQL by these versions and reject a mismatch.
     */
    JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload);

    /** Preparation/edit-only preconditions; confirmation rechecks the authoritative snapshot. */
    default void validatePreparation(String kind, JsonNode payload, JsonNode preview) {}

    /**
     * All domain SQL and durable async job enqueueing MUST join the confirmation transaction.
     * No REQUIRES_NEW, unmanaged external effect, or dispatch before commit. Async result
     * must include a nonempty "jobIds" array so the existing worker can recover after a restart.
     * The action ID is the domain effect's idempotency key.
     */
    ActionExecution execute(AuthorizedAction action);
}
