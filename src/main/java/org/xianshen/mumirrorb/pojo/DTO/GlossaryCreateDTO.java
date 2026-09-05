package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 个人词典手动新增 DTO（lexicon-design.md 5c："教镜子一个词"）
 *
 * <p>POST /api/glossary：手动新增即 confirmed（用户亲口教的直接生效）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "个人词典新增DTO - 手动新增（教镜子一个词）")
public class GlossaryCreateDTO {

    @NotBlank(message = "词条不能为空")
    @Size(max = 100, message = "词条最长 100 字")
    @Schema(description = "词条", example = "论文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String term;

    @Size(max = 20, message = "别名最多 20 个")
    @Schema(description = "别名列表（可选）", example = "[\"毕设\",\"那个设计\"]")
    private List<String> aliases;

    @NotBlank(message = "解释不能为空")
    @Schema(description = "解释（注入 prompt 的依据）", example = "用户的毕业设计，RAG 检索方向",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;
}
