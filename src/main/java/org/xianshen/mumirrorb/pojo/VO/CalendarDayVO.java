package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日历日期统计视图对象
 *
 * <p>用于日历接口返回每天的记录数统计</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日历日期统计")
public class CalendarDayVO {

    /**
     * 日期（格式：yyyy-MM-dd）
     */
    @Schema(description = "日期", example = "2026-08-13")
    private String date;

    /**
     * 该日期的有效记录数
     */
    @Schema(description = "有效记录数", example = "3")
    private Integer cnt;
}
