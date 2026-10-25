package org.xianshen.mumirrorb.agent;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Java-issued authority for exactly one immutable create_record call. */
public record CreateGrant(
        String token,
        String authorizedContent,
        UUID callId,
        String requestHash,
        OffsetDateTime expiresAt
) {
}
