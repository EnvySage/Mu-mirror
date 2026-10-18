package org.xianshen.mumirrorb.pojo.DTO;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionPrepareDTO {
    private String kind;
    private JsonNode payload;
    private String prepareKey;
    private UUID sourceSessionId;
    private Long sourceMessageId;
}
