package org.xianshen.mumirrorb.pojo.DTO;

import lombok.Data;

/** Point-in-time axis name/description captured by the growth audit ledger. */
@Data
public class GrowthAxisSnapshotDTO {
    private String name;
    private String description;
}
