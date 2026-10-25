package org.xianshen.mumirrorb.agent;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Claims verified by the Java-only HMAC key. */
public record VerifiedGrant(
        UUID userId,
        UUID sessionId,
        UUID runId,
        String command,
        String slot,
        String contentHash,
        boolean requestAiProcessing,
        OffsetDateTime expiresAt
) {
}
