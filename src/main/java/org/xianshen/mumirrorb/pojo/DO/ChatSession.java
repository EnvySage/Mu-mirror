package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 会话实体（对应 chat_sessions 表，设计文档 3.3 / 6.6）
 *
 * <p>会话列表按 updated_at 倒序（裁决 #9：统一用 updated_at，无 last_message_at）。
 * 每次新消息落库时触碰 updated_at。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chat_sessions", autoResultMap = true)
@Schema(description = "会话实体 - 对应 chat_sessions 表")
public class ChatSession {

    /**
     * 会话ID（UUID，数据库 gen_random_uuid() 自动生成）
     */
    @TableId(type = IdType.INPUT)
    @Schema(description = "会话ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    /**
     * 关联用户ID
     */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    /**
     * 会话标题（取首条用户提问截断，前端列表展示）
     */
    @Schema(description = "会话标题", example = "我最近在忙什么")
    private String title;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    /**
     * 更新时间（每次新消息触碰；会话列表按它倒序）
     */
    @Schema(description = "更新时间（会话列表排序键）")
    private OffsetDateTime updatedAt;
}
