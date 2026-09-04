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
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;
import org.xianshen.mumirrorb.common.handler.VectorTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 画像快照实体（对应 profile_snapshots 表，设计文档 3.3 / 6.5）
 *
 * <p>取代旧 mirror_profiles。分层保留：manual 保最近 2 份，monthly 保 12 份。</p>
 * <p>embedding：五维文本按固定顺序（mood→learning→todo→rhythm→overall）拼接后向量化，用于漂移检测。</p>
 * <p>不建向量索引：每用户仅 ~14 份快照，顺序扫描更快（裁决 #14）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "profile_snapshots", autoResultMap = true)
@Schema(description = "画像快照实体 - 对应 profile_snapshots 表")
public class ProfileSnapshot {

    @TableId(type = IdType.AUTO)
    @Schema(description = "快照ID", example = "1")
    private Long id;

    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    /**
     * 快照类型：manual（用户触发）/ monthly（每月1号 02:00 定时）
     */
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

    /**
     * 用户标签（JSONB 数组，如 ["技术学习", "夜猫子"]）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    @Schema(description = "用户标签", example = "[\"技术学习\", \"夜猫子\"]")
    private List<String> userTags;

    @Schema(description = "总体总结")
    private String overallSummary;

    /**
     * 五维文本拼接后的向量（1024 维），漂移检测用
     */
    @TableField(typeHandler = VectorTypeHandler.class)
    @Schema(description = "五维拼接向量（1024 维，漂移检测用）")
    private List<Float> embedding;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    /**
     * 五维文本按固定顺序拼接（Embedding 输入；漂移检测的比较对象）
     */
    public String joinedAnalysisText() {
        StringBuilder sb = new StringBuilder();
        if (moodAnalysis != null) sb.append(moodAnalysis).append("\n");
        if (learningAnalysis != null) sb.append(learningAnalysis).append("\n");
        if (todoAnalysis != null) sb.append(todoAnalysis).append("\n");
        if (rhythmAnalysis != null) sb.append(rhythmAnalysis).append("\n");
        if (overallSummary != null) sb.append(overallSummary);
        return sb.toString().trim();
    }
}
