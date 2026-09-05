package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 个人词典编辑 DTO（lexicon-design.md 5c：PUT /api/glossary/{id}）
 *
 * <p>编辑词条/别名/解释；update 候选确认走 confirm（description 覆盖为新建议）。</p>
 */
@Data
@Schema(description = "个人词典编辑DTO")
public class GlossaryUpdateDTO {

    @NotBlank(message = "词条不能为空")
    @Size(max = 100, message = "词条最长 100 字")
    @Schema(description = "词条", example = "论文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String term;

    @Size(max = 20, message = "别名最多 20 个")
    @Schema(description = "别名列表（可选）", example = "[\"毕设\",\"那个设计\"]")
    private List<String> aliases;

    @NotBlank(message = "解释不能为空")
    @Schema(description = "解释", example = "用户的毕业设计，RAG 检索方向",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;
}
