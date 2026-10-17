package org.xianshen.mumirrorb.pojo.DO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** A source or parent-snapshot ID actually included in a generated mirror. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotInput {

    private Long snapshotId;
    private String inputKind;
    private Long inputId;
    private String inputRole;
    private OffsetDateTime createdAt;
}
