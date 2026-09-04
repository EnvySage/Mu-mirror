package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 对话请求 DTO（设计文档 6.6）
 *
 * <p>sessionId 可空：为空则创建新会话（标题取本次提问截断）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对话请求")
public class ChatRequestDTO {

    @NotBlank(message = "提问内容不能为空")
    @Schema(description = "用户提问", example = "我最近一周学了什么")
    private String question;

    @Schema(description = "会话ID（可空：为空创建新会话）",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID sessionId;
}
