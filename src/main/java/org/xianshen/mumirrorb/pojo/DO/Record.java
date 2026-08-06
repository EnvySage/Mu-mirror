package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.enums.ContentType;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记录实体类（对应 records 表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "records", autoResultMap = true)
public class Record {

    /**
     * 记录ID（自增主键）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联用户ID
     */
    private String userId;

    /**
     * 用户原始输入内容
     */
    private String content;

    /**
     * AI 生成的标题（10字以内）
     */
    private String title;

    /**
     * AI 生成的摘要（30字以内）
     */
    private String summary;

    /**
     * 内容类型（枚举：todo/thought/learning/plan/note/work/social/health）
     */
    private ContentType contentType;

    /**
     * 情绪标签（JSONB 数组，多选，如 ["happy", "calm"]）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> mood;

    /**
     * 处理状态（枚举：processing/done/failed）
     */
    private RecordStatus status;

    /**
     * 用户是否已审核 AI 生成的标签
     */
    private Boolean userReviewed;

    /**
     * 创建时间
     */
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    private OffsetDateTime updatedAt;

    /**
     * 关键词标签（非数据库字段，不持久化）
     * 由管道 ClassifyProcessor 生成，Service 层取出后存入 tags 表
     */
    @TableField(exist = false)
    private List<String> keywords;
}
