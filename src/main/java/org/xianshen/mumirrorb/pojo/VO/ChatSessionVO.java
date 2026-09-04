package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话视图对象（前端会话列表/历史渲染，设计文档 6.6 / 8.5）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话视图对象")
public class ChatSessionVO {

    @Schema(description = "会话ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "会话标题", example = "我最近在忙什么")
    private String title;

    @Schema(description = "历史消息（仅 GET /mirror/sessions/{id} 返回；列表接口为 null）")
    private List<MessageVO> messages;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间（会话列表排序键）")
    private OffsetDateTime updatedAt;

    /**
     * 单条消息（含来源追溯）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "对话消息")
    public static class MessageVO {

        @Schema(description = "消息ID", example = "1")
        private Long id;

        @Schema(description = "角色", example = "assistant", allowableValues = {"user", "assistant"})
        private String role;

        @Schema(description = "消息内容")
        private String content;

        /**
         * 来源追溯（仅 assistant 消息）：[{record_id, quote, date}]
         */
        @Schema(description = "来源追溯（assistant 消息）",
                example = "[{\"record_id\":1,\"quote\":\"...\",\"date\":\"2026-09-03\"}]")
        private List<Map<String, Object>> sources;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @Schema(description = "创建时间")
        private OffsetDateTime createdAt;
    }
}
