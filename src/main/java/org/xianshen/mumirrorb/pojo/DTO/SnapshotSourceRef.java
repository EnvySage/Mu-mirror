package org.xianshen.mumirrorb.pojo.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A source ID and the role in which a snapshot included it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotSourceRef {
    private String inputKind;
    private Long inputId;
    private String inputRole;
}
