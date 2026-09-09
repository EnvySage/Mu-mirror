package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 待办↔日记关联实体（对应 todo_registry_links 表，todo-registry-design.md §2）
 *
 * <p>证据链：origin = 登记时原始片段（registerFromRecord 落），
 * evidence = 确认建议时才落（机器猜的关联不落库，裁决 #3/#22 一脉——用户背书才落）。</p>
 *
 * <p>UNIQUE(todo_id, chunk_id)：同一 chunk 对同一待办只有一条关联（先 origin 后 evidence
 * 也只有一行，relation 以首次落库为准）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "todo_registry_links", autoResultMap = true)
@Schema(description = "待办关联 - 对应 todo_registry_links 表")
public class TodoRegistryLink {

    @TableId(type = IdType.AUTO)
    @Schema(description = "关联ID（自增主键）", example = "1")
    private Long id;

    @Schema(description = "待办登记ID", example = "1")
    private Long todoId;

    @Schema(description = "关联chunk ID", example = "42")
    private Long chunkId;

    /** origin / evidence */
    @Schema(description = "关联类型", example = "origin", allowableValues = {"origin", "evidence"})
    private String relation;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;
}
