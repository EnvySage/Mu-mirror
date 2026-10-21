package org.xianshen.mumirrorb.agent;

import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.List;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;

/** Trusted chat identity after the request and its user message were accepted once. */
public record ChatInvocation(
        UUID userId,
        UUID sessionId,
        UUID runId,
        UUID clientRequestId,
        long userMessageId,
        String question,
        String engine,
        CommonProto.AssistantContext assistantContext,
        List<Long> contextSourceMessageIds
) {
    public ChatInvocation {
        assistantContext = assistantContext == null
                ? CommonProto.AssistantContext.getDefaultInstance() : assistantContext;
        contextSourceMessageIds = contextSourceMessageIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(contextSourceMessageIds));
    }

    /** Keeps non-context replay and lifecycle events source-compatible. */
    public ChatInvocation(UUID userId, UUID sessionId, UUID runId, UUID clientRequestId,
                          long userMessageId, String question, String engine) {
        this(userId, sessionId, runId, clientRequestId, userMessageId, question, engine,
                CommonProto.AssistantContext.getDefaultInstance(), List.of());
    }
}
