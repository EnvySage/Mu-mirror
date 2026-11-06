package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Named LLM connection. Keys use the same AES-GCM encryption as user_settings. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_model_profiles")
public class UserModelProfile {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidTypeHandler.class)
    private UUID userId;
    private String name;
    private String aiProvider;
    private String aiProtocol;
    private String aiBaseUrl;
    private String aiModel;
    @ToString.Exclude
    private String aiApiKey;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
