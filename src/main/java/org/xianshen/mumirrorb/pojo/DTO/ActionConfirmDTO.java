package org.xianshen.mumirrorb.pojo.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionConfirmDTO {
    private Integer expectedVersion;
    private String requestId;
}
