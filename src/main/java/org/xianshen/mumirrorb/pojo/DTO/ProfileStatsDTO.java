package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 五维统计视图（GenerateProfile 输入的数据载体，设计文档 6.5）
 *
 * <p>Java SQL 统计 → 组装 MirrorProfileProto 消息。仅 Service 层内部使用，不直接暴露给前端。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "画像五维统计（内部载体）")
public class ProfileStatsDTO {

    @Schema(description = "未完成待办（taskStatus != completed 的 todo/plan）")
    private List<TodoItemDTO> todos;

    @Schema(description = "最近学习条目")
    private List<LearningItemDTO> learnings;

    @Schema(description = "情绪分布")
    private List<MoodStatDTO> moodStats;

    @Schema(description = "关键词频次")
    private List<KeywordStatDTO> keywords;

    @Schema(description = "小时分布（0-23 → 次数）")
    private List<HourCountDTO> hourDistribution;

    @Schema(description = "星期分布（0=周日 → 次数）")
    private List<HourCountDTO> weekdayDistribution;

    @Schema(description = "活跃时段峰值（如 '21点'）")
    private String peakHour;

    @Schema(description = "统计范围内总记录数")
    private Integer totalRecords;

    @Schema(description = "统计范围描述（如 '最近30天'）")
    private String timeRange;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoItemDTO {
        private Long recordId;
        private String title;
        private String summary;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningItemDTO {
        private Long recordId;
        private String title;
        private String summary;
        private List<String> keywords;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoodStatDTO {
        private String mood;
        private Integer count;
        private Float percentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeywordStatDTO {
        private String keyword;
        private Integer count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourCountDTO {
        private Integer bucket;
        private Integer count;
    }
}
