package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 数据导出视图对象（GET /api/export/json，设计文档 6.9 / 裁决 #19）
 *
 * <p>自动排除所有 embedding 向量字段（只出不进，裁决 #19）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数据导出（JSON）")
public class ExportVO {

    @Schema(description = "导出格式版本", example = "v2")
    private String version;

    @Schema(description = "导出时间（ISO-8601）", example = "2026-09-04T10:00:00+08:00")
    private String exportedAt;

    @Schema(description = "用户名", example = "mirror_user")
    private String username;

    @Schema(description = "记录列表（含 chunks，无 embedding）")
    private List<RecordExportItem> records;

    @Schema(description = "画像快照列表（无 embedding）")
    private List<ProfileExportItem> profiles;

    @Schema(description = "会话列表（含消息与 sources）")
    private List<SessionExportItem> sessions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "导出记录")
    public static class RecordExportItem {

        @Schema(description = "记录ID", example = "1")
        private Long id;

        @Schema(description = "来源", example = "user", allowableValues = {"user", "system"})
        private String source;

        @Schema(description = "原始内容")
        private String content;

        @Schema(description = "状态", example = "done")
        private String status;

        @Schema(description = "创建时间")
        private String createdAt;

        @Schema(description = "片段列表（metadata 中的 title/summary/contentType/mood/keywords/taskStatus）")
        private List<Map<String, Object>> chunks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "导出画像快照")
    public static class ProfileExportItem {

        @Schema(description = "快照类型", example = "monthly")
        private String snapshotType;

        @Schema(description = "情绪/学习/待办/节奏分析 + 总体总结")
        private Map<String, Object> analysis;

        @Schema(description = "用户标签")
        private List<String> userTags;

        @Schema(description = "创建时间")
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "导出会话")
    public static class SessionExportItem {

        @Schema(description = "会话标题")
        private String title;

        @Schema(description = "创建时间")
        private String createdAt;

        @Schema(description = "消息列表（role/content/sources）")
        private List<Map<String, Object>> messages;
    }
}
