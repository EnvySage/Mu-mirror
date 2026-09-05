package org.xianshen.mumirrorb.pojo.DO;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资产二进制本体（对应 vault_blobs 表，BYTEA 直存）
 *
 * <p>与 vault_items 分表：列表/检索路径永不 SELECT 本表；
 * 仅 download/preview/digest 按主键单行拉取。级联删除（FK ON DELETE CASCADE）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("vault_blobs")
@Schema(description = "资产二进制本体 - 对应 vault_blobs 表（BYTEA）")
public class VaultBlob {

    /** 与 vault_items.id 一对一 */
    @TableId
    @Schema(description = "资产ID（=vault_items.id）", example = "1")
    private Long vaultItemId;

    @Schema(description = "文件字节")
    private byte[] data;
}
