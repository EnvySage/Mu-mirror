package org.xianshen.mumirrorb.agent;

import java.util.UUID;

/** Trusted chat identity after the request and its user message were accepted once. */
public record ChatInvocation(
        UUID userId,
        UUID sessionId,
        UUID runId,
        UUID clientRequestId,
        long userMessageId,
        String question,
        String engine
) {
}
