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
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandlerListMap;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话历史实体（对应 conversation_history 表，设计文档 3.3 / 6.6）
 *
 * <p>sources 落库（裁决 #8）：assistant 消息携带 [{record_id, quote, date}]，
 * 来源追溯是对话模块核心卖点。会话删除时级联删除（FK ON DELETE CASCADE）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "conversation_history", autoResultMap = true)
@Schema(description = "对话历史实体 - 对应 conversation_history 表")
public class ConversationHistory {

    @TableId(type = IdType.AUTO)
    @Schema(description = "消息ID（自增主键）", example = "1")
    private Long id;

    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "所属会话ID")
    private UUID sessionId;

    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    /**
     * 角色：user / assistant
     */
    @Schema(description = "角色", example = "assistant", allowableValues = {"user", "assistant"})
    private String role;

    @Schema(description = "消息内容")
    private String content;

    /**
     * 来源追溯（JSONB 数组，仅 assistant 消息）：[{record_id, quote, date}]
     */
    @TableField(typeHandler = JsonbTypeHandlerListMap.class)
    @Schema(description = "来源追溯（assistant 消息）", example = "[{\"record_id\":1,\"quote\":\"...\",\"date\":\"2026-09-03\"}]")
    private List<Map<String, Object>> sources;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;
}
