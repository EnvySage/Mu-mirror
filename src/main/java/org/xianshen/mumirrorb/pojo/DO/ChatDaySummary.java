package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data @TableName("chat_day_summaries")
public class ChatDaySummary {
    private UUID userId;
    private LocalDate sourceDate;
    private UUID sourceSessionId;
    private String summary;
    private String sourceHash;
    private String state;
    private long version;
    private OffsetDateTime leaseUntil;
    private String errorCode;
    private OffsetDateTime updatedAt;
}
