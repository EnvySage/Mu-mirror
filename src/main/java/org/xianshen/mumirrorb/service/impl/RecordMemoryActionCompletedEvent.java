package org.xianshen.mumirrorb.service.impl;

import java.util.UUID;

public record RecordMemoryActionCompletedEvent(UUID actionId, int executionVersion, Long recordId,
                                               String memoryState, String errorCode, String memoryFailureReason) {}
