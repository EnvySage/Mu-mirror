package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 资产视图对象（vault REST + find_item/recall_item 工具共用）
 *
 * <p>永不携带文件本体（BYTEA 分表）；文件卡渲染字段全齐。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户资产 - 文件卡渲染结构")
public class VaultItemVO {

    @Schema(description = "资产ID", example = "1")
    private Long id;

    @Schema(description = "清洗后原文件名", example = "开题报告-v3.pdf")
    private String originalName;

    @Schema(description = "字节数", example = "1048576")
    private Long sizeBytes;

    @Schema(description = "真实 MIME", example = "application/pdf")
    private String mime;

    @Schema(description = "文件类型简称（前端图标用）", example = "pdf", allowableValues = {"pdf", "docx", "txt", "md", "csv", "jpg", "png", "webp", "gif", "mp3", "wav", "m4a"})
    private String fileType;

    @Schema(description = "大类（前端筛选：document/image/audio）", example = "document")
    private String kind;

    @Schema(description = "分类（contentType 口径）", example = "learning")
    private String category;

    @Schema(description = "描述（用户提示 / LLM 命名）", example = "RAG 方向毕设开题报告")
    private String description;

    @Schema(description = "消化状态", example = "done", allowableValues = {"pending", "done", "skipped", "failed"})
    private String digestStatus;

    @Schema(description = "配额占用（字节）", example = "134217728")
    private Long quotaUsedBytes;

    @Schema(description = "配额总量（字节）", example = "524288000")
    private Long quotaBytes;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "上传时间")
    private OffsetDateTime createdAt;

    // ==== find_item / recall_item 附加字段 ====

    @Schema(description = "引用强度（strong=内容命中/weak=描述命中/vague=时间类型兜底）", example = "strong",
            allowableValues = {"strong", "weak", "vague"})
    private String matchLayer;

    @Schema(description = "引用摘录（recall_item：source_chunk 文本截断）", example = "第一章 绪论……")
    private String quote;

    @Schema(description = "记录数（全消化抽出的 chunk 数）", example = "12")
    private Integer digestChunkCount;
}
