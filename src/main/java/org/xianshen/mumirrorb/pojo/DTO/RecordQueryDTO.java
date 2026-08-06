package org.xianshen.mumirrorb.pojo.DTO;

import lombok.Data;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.MoodType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.time.LocalDate;

/**
 * 记录列表查询条件 DTO
 */
@Data
public class RecordQueryDTO {

    /**
     * 页码（从 1 开始，默认 1）
     */
    private Integer page = 1;

    /**
     * 每页条数（默认 20）
     */
    private Integer size = 20;

    /**
     * 按内容类型筛选
     */
    private ContentType contentType;

    /**
     * 按情绪筛选（查包含该情绪的记录）
     */
    private MoodType mood;

    /**
     * 按处理状态筛选
     */
    private RecordStatus status;

    /**
     * 开始日期（筛选 created_at >= 此日期）
     */
    private LocalDate startDate;

    /**
     * 结束日期（筛选 created_at < 此日期的下一天）
     */
    private LocalDate endDate;
}
