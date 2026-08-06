package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 标签实体类（对应 tags 表，关键词标签，一条记录可有多个）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tags")
public class Tag {

    /**
     * 标签ID（自增主键）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的记录ID
     */
    private Long recordId;

    /**
     * 关键词
     */
    private String keyword;

    /**
     * 创建时间
     */
    private OffsetDateTime createdAt;
}
