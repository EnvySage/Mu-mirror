package org.xianshen.mumirrorb.usage;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Trusted Java binding. Contains no prompt, content, API key or URL credentials. */
public record UsageEnvelope(UUID id,UUID taskId,UUID userId,String scene,boolean standalone,
                            Snapshot snapshot,OffsetDateTime startedAt) {
    public record Snapshot(String provider,String model,String channelKey,String channelName,UUID profileId) { }
}
