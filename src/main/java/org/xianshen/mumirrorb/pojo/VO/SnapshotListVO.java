package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 快照历史列表项视图对象（前端契约，六章"快照历史与对比"，GET /api/mirror/snapshots）
 *
 * <p>轻量结构：只带时间线渲染所需字段，完整分析正文走 GET /api/mirror/snapshots/{id}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "快照历史列表项视图对象")
public class SnapshotListVO {

    @Schema(description = "快照ID", example = "5")
    private Long id;

    @Schema(description = "快照类型", example = "manual", allowableValues = {"manual", "monthly"})
    private String snapshotType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "快照生成时间")
    private OffsetDateTime createdAt;

    /**
     * 漂移（相对上一份 monthly 快照的余弦距离，0~2）
     *
     * <p>仅 monthly 快照有值；manual 无对比基线返回 null（前端可显示为"—"）。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "与上一份月度快照的余弦距离（漂移检测）", example = "0.12")
    private Double driftDistance;

    @Schema(description = "总体总结（前 50 字截断，截断时追加 ...）")
    private String overallSummary;
}
