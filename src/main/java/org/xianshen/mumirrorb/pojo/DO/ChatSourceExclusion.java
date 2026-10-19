package org.xianshen.mumirrorb.pojo.DO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Source tombstone: identifiers and optional fingerprints only, never forgotten content. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatSourceExclusion {
    private UUID userId;
    private String sourceKind;
    private Long sourceId;
    private Long sourceVersion;
    private String sourceFingerprint;
    private String reason;
    private OffsetDateTime createdAt;
}
