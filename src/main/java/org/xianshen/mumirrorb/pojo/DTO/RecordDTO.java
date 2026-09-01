package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 记录请求 DTO（创建用）
 *
 * <p>用户创建记录时只需提供 content 字段。</p>
 * <p>AI 生成的元数据（title, contentType, mood 等）在 Chunk 上，审核时通过 Chunk 接口修改。</p>
 */
@Data
@Schema(description = "记录请求DTO - 创建记录时使用")
public class RecordDTO {

    /**
     * 用户输入的内容
     *
     * <p>创建时必填，最大长度 2000 字</p>
     */
    @Size(max = 2000, message = "内容长度不能超过 2000 字")
    @Schema(
            description = "用户输入的原始内容（创建时必填）",
            example = "今天学习了Spring Security的核心概念...",
            maxLength = 2000,
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String content;
}
