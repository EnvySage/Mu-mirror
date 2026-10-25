package org.xianshen.mumirrorb.tools;

import java.util.UUID;

/** Java-created provenance for one normalized model call in one persisted user turn. */
public record ToolCallContext(UUID sessionId, Long sourceMessageId, String prepareKey) {}
