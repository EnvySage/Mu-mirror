package org.xianshen.mumirrorb.pojo.DTO;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionEditDTO {
    private Integer expectedVersion;
    private JsonNode payload;
}
