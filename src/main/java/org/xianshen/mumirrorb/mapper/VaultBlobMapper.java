package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.VaultBlob;

/**
 * 资产二进制 Mapper（vault_blobs；仅 download/preview/digest 按主键单行拉取）
 */
@Mapper
public interface VaultBlobMapper extends BaseMapper<VaultBlob> {

    /**
     * 按主键拉本体（分表定稿：列表路径永不调本方法）
     */
    @Select("SELECT vault_item_id, data FROM vault_blobs WHERE vault_item_id = #{id}")
    VaultBlob selectByItemId(@Param("id") Long id);
}
