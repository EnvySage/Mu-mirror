package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class ChatMemoryVO {
    private UUID id;
    private String content;
    private String kind;
    @JsonProperty("source_date") private LocalDate sourceDate;
    private long version;
    @JsonProperty("source_message_ids") private List<Long> sourceMessageIds;
    @JsonProperty("source_session_id") private UUID sourceSessionId;
    @JsonProperty("event_date") private LocalDate eventDate;
    @JsonProperty("expires_at") private OffsetDateTime expiresAt;
}
