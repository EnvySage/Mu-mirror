package org.xianshen.mumirrorb.pojo.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 记录列表查询条件 DTO
 *
 * <p>用于查询记录列表时的筛选条件，所有字段都是可选的。</p>
 *
 * <p><strong>日期查询规则：</strong></p>
 * <ul>
 *   <li>不传 startDate 和 endDate：默认查询今天的记录</li>
 *   <li>只传 startDate：查询该日期的记录</li>
 *   <li>只传 endDate：查询该日期的记录</li>
 *   <li>两个都传：查询日期范围内的记录（包含边界）</li>
 * </ul>
 *
 * <p><strong>示例：</strong></p>
 * <ul>
 *   <li>GET /api/records - 查询今天的记录</li>
 *   <li>GET /api/records?startDate=2026-08-01&endDate=2026-08-07 - 查询日期范围</li>
 * </ul>
 */
@Data
@Schema(description = "记录查询条件DTO - 用于筛选记录列表")
public class RecordQueryDTO {

    /**
     * 开始日期（筛选 created_at >= 此日期）
     *
     * <p>格式：yyyy-MM-dd</p>
     * <p>不传时，如果 endDate 也不传，默认为今天；如果传了 endDate，默认等于 endDate</p>
     */
    @Schema(
            description = "开始日期（格式：yyyy-MM-dd）。不传时默认查询今天的记录",
            example = "2026-08-01",
            format = "date",
            type = "string"
    )
    private LocalDate startDate;

    /**
     * 结束日期（筛选 created_at < 此日期的下一天）
     *
     * <p>格式：yyyy-MM-dd</p>
     * <p>查询范围：startDate <= createdAt < endDate + 1天</p>
     * <p>不传时，默认等于 startDate</p>
     */
    @Schema(
            description = "结束日期（格式：yyyy-MM-dd）。查询范围：startDate <= 创建时间 < endDate + 1天",
            example = "2026-08-07",
            format = "date",
            type = "string"
    )
    private LocalDate endDate;
}
