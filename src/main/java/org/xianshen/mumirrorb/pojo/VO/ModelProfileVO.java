package org.xianshen.mumirrorb.pojo.VO;

import lombok.Builder;
import lombok.Value;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Deliberately contains no plaintext, ciphertext, or masked API key. */
@Value
@Builder
public class ModelProfileVO {
    UUID id;
    String name;
    String aiProvider;
    String aiProtocol;
    String aiBaseUrl;
    String aiModel;
    boolean hasApiKey;
    boolean apiKeyNeedsReentry;
    boolean active;
    boolean unappliedChanges;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
