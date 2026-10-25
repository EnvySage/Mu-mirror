package org.xianshen.mumirrorb.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.xianshen.mumirrorb.pojo.DO.GrowthAxis;

@Mapper
public interface GrowthAxisMapper extends BaseMapper<GrowthAxis> {
    @Update("UPDATE growth_axes SET name=#{axis.name}, description=#{axis.description}, definition_version=#{axis.definitionVersion}, updated_at=#{axis.updatedAt} WHERE user_id=#{axis.userId} AND dimension_id=#{axis.dimensionId}")
    int updateDefinition(@Param("axis") GrowthAxis axis);
}
