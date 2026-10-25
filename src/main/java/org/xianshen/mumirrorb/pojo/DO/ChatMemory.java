package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data @TableName("chat_memories")
public class ChatMemory {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID userId;
    private String content;
    private String kind;
    private UUID sourceSessionId;
    @com.baomidou.mybatisplus.annotation.TableField("source_message_ids")
    private String sourceMessageIds;
    private LocalDate sourceDate;
    private LocalDate eventDate;
    private OffsetDateTime expiresAt;
    private String state;
    private long version;
    private OffsetDateTime createdAt;
}
