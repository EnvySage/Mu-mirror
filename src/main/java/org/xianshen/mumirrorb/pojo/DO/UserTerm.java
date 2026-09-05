package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.xianshen.mumirrorb.common.handler.JsonbTypeHandler;
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户个人词典词条实体（对应 user_terms 表，lexicon-design.md v1.0）
 *
 * <p>核心哲学：机器猜的 pending 只展示不注入，confirmed 才生效（与"审核过的才进记忆"同构）。</p>
 *
 * <p>状态机：pending（候选，仅展示）→ confirmed（生效，注入 LLM 全链路）→ dismissed（忽略，沉底不删行，
 * 30 天后可重新浮现）。hit_count 拆两字段：query 命中管"活跃度/注入优先级"，content 命中管"还活着没"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "user_terms", autoResultMap = true)
@Schema(description = "用户个人词典词条 - 对应 user_terms 表")
public class UserTerm {

    /** 词条ID（自增主键） */
    @TableId(type = IdType.AUTO)
    @Schema(description = "词条ID", example = "1")
    private Long id;

    /** 关联用户ID */
    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "关联用户ID")
    private UUID userId;

    /** 词条本身（用户+词条唯一） */
    @Schema(description = "词条", example = "论文")
    private String term;

    /** 别名（如 ["毕设","那个设计"]，JSONB） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    @Schema(description = "别名列表", example = "[\"毕设\",\"那个设计\"]")
    private List<String> aliases;

    /** 用户确认的解释（注入 prompt 的依据） */
    @Schema(description = "解释", example = "用户的毕业设计，RAG 检索方向")
    private String description;

    /** 状态：pending / confirmed / dismissed */
    @Schema(description = "状态", example = "pending", allowableValues = {"pending", "confirmed", "dismissed"})
    private String status;

    /** 用户提问命中数（注入优先级，高者优先注入） */
    @Schema(description = "提问命中数", example = "3")
    private Integer queryHitCount;

    /** 入库内容命中数（存活/沉底依据） */
    @Schema(description = "内容命中数", example = "5")
    private Integer contentHitCount;

    /** 最后确认时间（confirmed 卡片显示"最后确认于x日"） */
    @Schema(description = "最后确认时间")
    private OffsetDateTime lastConfirmedAt;

    /** 最近一次语料出现时间（衰减依据） */
    @Schema(description = "最近语料出现时间")
    private OffsetDateTime lastSeenAt;

    /** 佐证 chunk（溯源；chunk 删除时置 NULL） */
    @Schema(description = "佐证chunk ID", example = "42")
    private Long sourceChunkId;

    /**
     * 佐证 chunk 所在记录 ID（F 契约：前端跳记录详情直接用 record id）
     *
     * <p>冗余存 record_id：chunks 物理删除（审核阶段删片段）后 source_chunk_id 置 NULL，
     * 但"该词条证据来自哪条记录"仍应可跳转。</p>
     */
    @Schema(description = "佐证记录ID（跳记录详情）", example = "17")
    private Long sourceRecordId;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
