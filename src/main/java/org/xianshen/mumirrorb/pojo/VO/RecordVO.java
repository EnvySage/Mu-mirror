package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记录视图对象（列表 + 详情通用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordVO {

    /**
     * 记录ID
     */
    private Long id;

    /**
     * 原始内容
     */
    private String content;

    /**
     * AI 生成的标题
     */
    private String title;

    /**
     * AI 生成的摘要
     */
    private String summary;

    /**
     * 内容类型
     */
    private ContentType contentType;

    /**
     * 情绪标签（多选）
     */
    private List<String> mood;

    /**
     * 处理状态
     */
    private RecordStatus status;

    /**
     * 用户是否已审核
     */
    private Boolean userReviewed;

    /**
     * 关键词标签列表
     */
    private List<String> keywords;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private OffsetDateTime updatedAt;
}
