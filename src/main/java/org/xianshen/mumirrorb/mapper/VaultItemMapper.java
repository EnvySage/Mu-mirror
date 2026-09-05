package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.VaultItem;

import java.util.List;
import java.util.UUID;

/**
 * 用户资产 Mapper（vault_items；本体在 vault_blobs 永不 JOIN 拉取）
 *
 * <p>列表/检索走 default 方法（LambdaQueryWrapper + autoResultMap 免 JSONB Handler 竞争坑）；
 * 全文关键词兜底用一条自定义 @Select（仅标量列，无实体 JSONB 映射）。</p>
 */
@Mapper
public interface VaultItemMapper extends BaseMapper<VaultItem> {

    /**
     * 用户在管资产（时间倒序；deleted_at IS NULL）
     */
    default List<VaultItem> selectAliveByUser(UUID userId) {
        return selectList(new LambdaQueryWrapper<VaultItem>()
                .eq(VaultItem::getUserId, userId)
                .isNull(VaultItem::getDeletedAt)
                .orderByDesc(VaultItem::getCreatedAt));
    }

    /**
     * 单条 alive 资产（ownership 校验前置：非本人/已删一律 null → 上层 404）
     */
    default VaultItem selectAliveById(Long id, UUID userId) {
        return selectOne(new LambdaQueryWrapper<VaultItem>()
                .eq(VaultItem::getId, id)
                .eq(VaultItem::getUserId, userId)
                .isNull(VaultItem::getDeletedAt));
    }

    /**
     * 用户在管资产总字节数（配额校验）
     */
    default Long sumAliveBytes(UUID userId) {
        List<VaultItem> all = selectAliveByUser(userId);
        return all.stream().mapToLong(i -> i.getSizeBytes() == null ? 0L : i.getSizeBytes()).sum();
    }

    /**
     * 三层漏斗 ① 精确层：文件名/描述 ILIKE（词典 term/别名由 Service 拼进 pattern）
     */
    @Select("""
            <script>
            SELECT * FROM vault_items
            WHERE user_id = #{userId}::uuid
              AND deleted_at IS NULL
              AND <foreach collection="patterns" item="p" open="(" separator=" OR " close=")">
                  original_name ILIKE #{p} OR description ILIKE #{p}
              </foreach>
            <if test="type != null">AND mime LIKE #{typePrefix}</if>
            <if test="since != null">AND created_at &gt;= #{since}</if>
            ORDER BY created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<VaultItem> searchByKeyword(@Param("userId") UUID userId,
                                    @Param("patterns") List<String> patterns,
                                    @Param("type") String type,
                                    @Param("typePrefix") String typePrefix,
                                    @Param("since") java.time.OffsetDateTime since,
                                    @Param("limit") int limit);

    /**
     * 类型 + 时间窗兜底检索（零 key 场景："昨天传的图片"）
     */
    default List<VaultItem> selectByTypeAndWindow(UUID userId, String typePrefix,
                                                  java.time.OffsetDateTime since, int limit) {
        return selectList(new LambdaQueryWrapper<VaultItem>()
                .eq(VaultItem::getUserId, userId)
                .isNull(VaultItem::getDeletedAt)
                .likeRight(VaultItem::getMime, typePrefix)
                .ge(since != null, VaultItem::getCreatedAt, since)
                .orderByDesc(VaultItem::getCreatedAt)
                .last("LIMIT " + limit));
    }
}
