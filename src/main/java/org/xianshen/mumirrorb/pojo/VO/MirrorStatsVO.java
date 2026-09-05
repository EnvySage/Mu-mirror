package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 镜子统计数据视图对象（前端契约，镜子页图表系统数据源）
 *
 * <p>GET /api/mirror/stats?days=30。全部 JSON 字段 camelCase（前端 convertKeys 转 snake_case）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "镜子统计数据")
public class MirrorStatsVO {

    @Schema(description = "统计窗口天数（已 clamp 到 [7,90]）", example = "30")
    private Integer days;

    @Schema(description = "按日情绪计数（堆叠色带；窗口内每一天都有，无数据天 moods 为空数组）")
    private List<MoodDayVO> moodDaily;

    @Schema(description = "活跃时段分布（0-23 全 24 格，无数据补零）")
    private List<BucketCountVO> hourDist;

    @Schema(description = "星期分布（0-6 全 7 格，周一=0，无数据补零）")
    private List<BucketCountVO> weekdayDist;

    @Schema(description = "关键词频次 Top 10")
    private List<KeywordCountVO> keywordTop;

    @Schema(description = "待办统计（状态计数 + 未完成明细前 10 条）")
    private TodoStatsVO todo;

    @Schema(description = "按日记录数（频率柱状；窗口内每一天都有，无数据天为 0）")
    private List<DayCountVO> recordDaily;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单日情绪计数")
    public static class MoodDayVO {
        @Schema(description = "日期", example = "2026-09-01")
        private String date;

        @Schema(description = "当日各情绪计数")
        private List<MoodCountVO> moods;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "情绪计数")
    public static class MoodCountVO {
        @Schema(description = "情绪值", example = "satisfied")
        private String mood;

        @Schema(description = "次数", example = "3")
        private Integer count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "桶计数（小时/星期共用）")
    public static class BucketCountVO {
        @Schema(description = "桶（hourDist: 0-23；weekdayDist: 周一=0）", example = "0")
        private Integer bucket;

        @Schema(description = "次数", example = "5")
        private Integer count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "关键词计数")
    public static class KeywordCountVO {
        @Schema(description = "关键词", example = "Three.js")
        private String keyword;

        @Schema(description = "次数", example = "12")
        private Integer count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "待办统计")
    public static class TodoStatsVO {
        @Schema(description = "待办/计划 chunk 总数", example = "6")
        private Integer total;

        @Schema(description = "已完成数", example = "3")
        private Integer completed;

        @Schema(description = "未开始数（含缺 taskStatus 的旧数据）", example = "2")
        private Integer notStarted;

        @Schema(description = "进行中数", example = "1")
        private Integer inProgress;

        @Schema(description = "未完成明细（最多 10 条，时间倒序）")
        private List<TodoItemVO> openItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "未完成待办明细")
    public static class TodoItemVO {
        @Schema(description = "记录ID", example = "1")
        private Long recordId;

        @Schema(description = "标题")
        private String title;

        @Schema(description = "摘要")
        private String summary;

        @Schema(description = "任务状态", example = "not_started", allowableValues = {"not_started", "in_progress"})
        private String taskStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单日记录数")
    public static class DayCountVO {
        @Schema(description = "日期", example = "2026-09-01")
        private String date;

        @Schema(description = "记录数", example = "4")
        private Integer count;
    }
}
