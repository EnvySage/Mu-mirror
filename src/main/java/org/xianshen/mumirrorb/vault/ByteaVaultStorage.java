package org.xianshen.mumirrorb.vault;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.VaultBlobMapper;
import org.xianshen.mumirrorb.pojo.DO.VaultBlob;

/**
 * Postgres BYTEA 直存实现（toolcalling-vault-design.md 3.1 存储定稿）
 *
 * <p>storage_key 与 vault_item_id 一对一映射：key 解析出 id（{userId}/{yyyyMM}/{uuid}.ext
 * 中 uuid 段不落库，真源是 vault_items 行；put 时行先建，blob 后写，get 按 key→item→id 需调用方给 id。
 * 为避免二次查库，key 约定为 {@code v:{itemId}:{uuid}.ext}——首段即主键）。</p>
 */
@Component
@RequiredArgsConstructor
public class ByteaVaultStorage implements VaultStorage {

    private final VaultBlobMapper blobMapper;

    @Override
    public void put(String storageKey, byte[] data) {
        long itemId = itemIdOf(storageKey);
        VaultBlob existing = blobMapper.selectById(itemId);
        VaultBlob blob = VaultBlob.builder().vaultItemId(itemId).data(data).build();
        if (existing != null) {
            blobMapper.updateById(blob);
        } else {
            blobMapper.insert(blob);
        }
    }

    @Override
    public VaultBlob get(String storageKey) {
        return blobMapper.selectByItemId(itemIdOf(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        blobMapper.delete(new LambdaQueryWrapper<VaultBlob>()
                .eq(VaultBlob::getVaultItemId, itemIdOf(storageKey)));
    }

    /**
     * storage_key 首段 = vault_item_id（写侧 VaultService 保证格式）
     */
    public static long itemIdOf(String storageKey) {
        String[] parts = storageKey.split(":", 2);
        try {
            return Long.parseLong(parts[0].replace("v", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("storage_key 格式非法: " + storageKey);
        }
    }
}
