package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 每日总结视图对象（GET /api/summaries 返回，设计文档 6.7）
 *
 * <p>summaryDate 来自 Chunk.metadata.summaryDate（日报锚定的日期，非生成时间）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "每日总结视图对象")
public class DailySummaryVO {

    @Schema(description = "系统记录ID（records.id，source='system'）", example = "100")
    private Long recordId;

    @Schema(description = "日报锚定日期（metadata.summaryDate）", example = "2026-09-02")
    private String summaryDate;

    @Schema(description = "日报文本（records.content）")
    private String content;

    @Schema(description = "统计信息（metadata.recordCount 等）")
    private Map<String, Object> stats;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "生成时间")
    private OffsetDateTime createdAt;

    /**
     * 列表接口不填充 content（前端列表只展示日期与摘要行，详情按 date 拉）
     */
    @Schema(description = "日报条目摘要（列表接口返回 content 前几行）")
    private List<String> highlights;
}
