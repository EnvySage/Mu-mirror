package org.xianshen.mumirrorb.pojo.DTO;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;

import java.util.List;

/**
 * 记录请求 DTO（创建 + 更新共用）
 *
 * 创建时：content 必填，其他字段由 AI 生成（不需要传）
 * 审核时：只传需要修改的字段，content 不传（不能改原始内容）
 */
@Data
public class RecordDTO {

    /**
     * 用户输入的内容（创建时必填，更新时不传）
     */
    @Size(max = 2000, message = "内容长度不能超过 2000 字")
    private String content;

    /**
     * 标题（AI 生成，审核时可修改）
     */
    @Size(max = 200, message = "标题长度不能超过 200 字")
    private String title;

    /**
     * 摘要（AI 生成，审核时可修改）
     */
    @Size(max = 500, message = "摘要长度不能超过 500 字")
    private String summary;

    /**
     * 内容类型
     */
    private ContentType contentType;

    /**
     * 情绪标签（多选，如 ["happy", "calm"]）
     */
    private List<String> mood;

    /**
     * 处理状态
     */
    private RecordStatus status;

    /**
     * 关键词列表（替换原有标签）
     */
    private List<String> keywords;
}
