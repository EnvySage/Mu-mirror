package org.xianshen.mumirrorb.service;

import java.util.UUID;

public interface RecordMemoryLifecycleService {
    void onInitialIndexComplete(UUID userId, Long recordId, int processingVersion);
    void onInitialIndexExhausted(UUID userId, Long recordId, int processingVersion, String reason);
    void disable(UUID userId, Long recordId, int expectedProcessingVersion);
}
