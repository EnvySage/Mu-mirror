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
import org.xianshen.mumirrorb.common.handler.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户资产元数据实体（对应 vault_items 表，toolcalling-vault-design.md 3.1）
 *
 * <p>本体 BYTEA 在 vault_blobs 分表（{@link VaultBlob}），列表查询永不拉 blob。</p>
 *
 * <p>状态机（digest_status）：pending（待消化）→ done（全消化，文本/PDF/docx）/
 * skipped（零消化，音视频元数据卡）→ failed（消化失败，管道隔离不影响主服务）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("vault_items")
@Schema(description = "用户资产元数据 - 对应 vault_items 表")
public class VaultItem {

    @TableId(type = IdType.AUTO)
    @Schema(description = "资产ID", example = "1")
    private Long id;

    @TableField(typeHandler = UuidTypeHandler.class)
    @Schema(description = "所属用户ID")
    private UUID userId;

    /** 清洗后原文件名（路径符号/控制字符清洗 + 255 截断；非 LLM 命名） */
    @Schema(description = "清洗后原文件名", example = "开题报告-v3.pdf")
    private String originalName;

    /** 存储键 {userId}/{yyyyMM}/{uuid}.ext（VaultStorage 抽象参数，当前实现=BYTEA 分表行） */
    @Schema(description = "存储键", example = "de9bc0fa.../202609/ab12....pdf")
    private String storageKey;

    @Schema(description = "字节数", example = "1048576")
    private Long sizeBytes;

    /** magic bytes 校验后的真实 mime（不信扩展名） */
    @Schema(description = "真实 MIME", example = "application/pdf")
    private String mime;

    /** SHA-256（同用户同内容去重；64 hex） */
    @Schema(description = "内容 SHA-256", example = "e3b0c44298fc1c149afbf4c8996fb924...")
    private String sha256;

    /** 分类（复用 contentType 8 类口径：learning/work/todo...） */
    @Schema(description = "分类", example = "learning")
    private String category;

    /** 一句话描述（用户提示 / LLM 自动命名，三层渐进 key） */
    @Schema(description = "描述", example = "RAG 方向毕设开题报告")
    private String description;

    /** 消化状态：pending/done/skipped/failed */
    @Schema(description = "消化状态", example = "done", allowableValues = {"pending", "done", "skipped", "failed"})
    private String digestStatus;

    /** 全消化的代表 chunk（挂 vault_item_id 管道产物；删除时 SET NULL） */
    @Schema(description = "代表chunk ID", example = "42")
    private Long sourceChunkId;

    @Schema(description = "上传时间")
    private OffsetDateTime createdAt;

    /** 删除瞬间记录（审计位，非软删恢复）；非本人/已删资源一律 404 */
    @Schema(description = "删除时间（审计位）")
    private OffsetDateTime deletedAt;
}
