package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 写作灵感请求 DTO（设计文档 6.8）
 *
 * <p>前端在输入停顿 &gt;30s 时触发；后端临时检索历史，不落库。</p>
 */
@Data
@Schema(description = "写作灵感请求")
public class InspirationRequestDTO {

    @NotBlank(message = "草稿内容不能为空")
    @Schema(description = "用户当前输入的草稿内容", example = "今天想把跑步的习惯坚持下来")
    private String draft;
}
