package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 镜子画像视图对象（前端契约，设计文档 6.5 / 十章 GET /api/mirror）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "镜子画像视图对象")
public class MirrorProfileVO {

    @Schema(description = "快照ID（无快照时为 null）")
    private Long id;

    @Schema(description = "快照类型", example = "manual", allowableValues = {"manual", "monthly"})
    private String snapshotType;

    @Schema(description = "情绪维度分析")
    private String moodAnalysis;

    @Schema(description = "学习维度分析")
    private String learningAnalysis;

    @Schema(description = "待办维度分析")
    private String todoAnalysis;

    @Schema(description = "节奏维度分析")
    private String rhythmAnalysis;

    @Schema(description = "用户标签", example = "[\"技术学习\", \"夜猫子\"]")
    private List<String> userTags;

    @Schema(description = "总体总结")
    private String overallSummary;

    /**
     * 漂移（相对上一份 monthly 快照的余弦距离，0~2）
     *
     * <p>null = 无可对比的历史快照或向量缺失。前端可换算为"变化幅度"百分比展示。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "与上一份月度快照的余弦距离（漂移检测）", example = "0.12")
    private Double driftDistance;

    /**
     * 漂移参照：上一份 monthly 快照的创建时间
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "漂移参照快照时间")
    private OffsetDateTime driftBaselineAt;

    @Schema(description = "快照生成时间")
    private OffsetDateTime createdAt;
}
