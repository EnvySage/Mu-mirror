package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 个人词典视图对象（lexicon-design.md 5b/5c）
 *
 * <p>GET /api/glossary 返回三组分好：pending（badge 角标）/ confirmed（已生效）/
 * dismissed（已忽略，沉底不删行）。单项字段结构 {@link UserTermVO}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "个人词典分组视图 - 待确认/已生效/已忽略三组")
public class GlossaryGroupVO {

    @Schema(description = "待确认候选（sheet 分区 + 设置页 badge 角标数据源）")
    private List<UserTermVO> pending;

    @Schema(description = "已生效词条（注入 LLM 全链路）")
    private List<UserTermVO> confirmed;

    @Schema(description = "已忽略词条（30 天后可重新浮现）")
    private List<UserTermVO> dismissed;

    /**
     * 词典单项 VO（三分组共用结构）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "个人词典词条")
    public static class UserTermVO {

        @Schema(description = "词条ID", example = "1")
        private Long id;

        @Schema(description = "词条", example = "论文")
        private String term;

        @Schema(description = "别名列表", example = "[\"毕设\",\"那个设计\"]")
        private List<String> aliases;

        @Schema(description = "解释", example = "用户的毕业设计，RAG 检索方向")
        private String description;

        @Schema(description = "状态", example = "confirmed", allowableValues = {"pending", "confirmed", "dismissed"})
        private String status;

        @Schema(description = "提问命中数", example = "3")
        private Integer queryHitCount;

        @Schema(description = "内容命中数", example = "5")
        private Integer contentHitCount;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        @Schema(description = "最后确认时间（卡片显示'最后确认于x日'）")
        private OffsetDateTime lastConfirmedAt;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        @Schema(description = "最近语料出现时间")
        private OffsetDateTime lastSeenAt;

        @Schema(description = "佐证chunk ID（溯源；chunk 删除后可能为空）", example = "42")
        private Long sourceChunkId;

        @Schema(description = "佐证记录ID（前端跳记录详情直接用它；F 契约：source_chunk 所在 record）", example = "17")
        private Long sourceRecordId;

        @Schema(description = "佐证摘要（抽取时 LLM 给出，如'近14天出现3次'）", example = "近14天出现3次")
        private String evidence;

        @Schema(description = "候选类型（new/evidence/update；仅候选卡片用）", example = "new",
                allowableValues = {"new", "evidence", "update"})
        private String kind;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        @Schema(description = "创建时间")
        private OffsetDateTime createdAt;
    }
}
