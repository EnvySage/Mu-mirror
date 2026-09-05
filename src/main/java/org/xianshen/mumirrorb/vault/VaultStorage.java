package org.xianshen.mumirrorb.vault;

import org.xianshen.mumirrorb.pojo.DO.VaultBlob;

/**
 * 资产存储抽象（storage_key 落库的接口化）
 *
 * <p>当前实现 {@link ByteaVaultStorage}（Postgres BYTEA 分表直存，数据主权 100% 收敛）；
 * 未来换对象存储（S3/OSS）只替换本接口实现，vault_items.storage_key 语义不变。</p>
 */
public interface VaultStorage {

    /**
     * 写入文件本体
     *
     * @param storageKey 存储键（{userId}/{yyyyMM}/{uuid}.ext）
     * @param data       文件字节
     */
    void put(String storageKey, byte[] data);

    /**
     * 读取文件本体（不存在返回 null）
     */
    VaultBlob get(String storageKey);

    /**
     * 删除文件本体（硬删除；不存在静默幂等）
     */
    void delete(String storageKey);
}
