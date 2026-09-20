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

    /**
     * 用过的工具（JSONB 数组，仅 assistant 消息）：[{tool, summary}]
     * 历史回放时前端据此渲染工具轨迹芯片（chat.js normalizeToolsUsed 兼容对象/字符串两种形态）
     */
    @TableField(typeHandler = JsonbTypeHandlerListMap.class)
    @Schema(description = "用过的工具（assistant 消息）", example = "[{\"tool\":\"find_item\",\"summary\":\"4个文件\"}]")
    private List<Map<String, Object>> toolsUsed;

    /**
     * 对话文件卡（JSONB 数组，仅 assistant 消息）：[{n, vault_item_id, display_name, digest_status, quote}]
     * 历史回放时前端据此渲染文件卡与正文 [Fn] 行内芯片
     */
    @TableField(typeHandler = JsonbTypeHandlerListMap.class)
    @Schema(description = "对话文件卡（assistant 消息）")
    private List<Map<String, Object>> vaultRefs;

    /**
     * 兜底消息标记（chat-loop-design.md §6.2）：true = 这条 assistant 消息是系统兜底文案，
     * <b>不进后续轮次的历史上下文</b>（{@code buildChatRequest} 按此列过滤）。
     *
     * <p>为什么不用文案匹配：用户自己打出"没有找到相关记录"会被误跳，脆得离谱；
     * 这一列是精准判据。已有库不会随 {@code CREATE TABLE IF NOT EXISTS} 自动加列，
     * 需手动执行设计稿 §11 的 ALTER TABLE。</p>
     */
    @TableField("is_fallback")
    @Schema(description = "是否系统兜底文案（true 不进历史上下文）", example = "false")
    private Boolean isFallback;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;
}
