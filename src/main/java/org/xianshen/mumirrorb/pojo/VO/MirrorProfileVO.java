package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    @Schema(description = "月度快照归属月份", example = "2026-08")
    private String periodMonth;

    @Schema(description = "该归属月份的版本号", example = "2")
    private Integer versionNo;

    @Schema(description = "是否为当前月度版本")
    private Boolean isCurrent;

    @Schema(description = "上下文状态", example = "archive_only", allowableValues = {"usable", "archive_only"})
    private String contextStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "退出后续 AI 上下文的时间")
    private OffsetDateTime invalidatedAt;

    @Schema(description = "退出上下文的原因")
    private String invalidatedReason;

    @Schema(description = "已删除或已清除的来源记录数")
    private Integer deletedSourceCount;

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
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "漂移参照快照时间")
    private OffsetDateTime driftBaselineAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "快照生成时间")
    private OffsetDateTime createdAt;
}
