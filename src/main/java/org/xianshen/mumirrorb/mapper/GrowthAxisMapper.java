package org.xianshen.mumirrorb.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import org.xianshen.mumirrorb.pojo.DO.GrowthAxis;

@Mapper
public interface GrowthAxisMapper extends BaseMapper<GrowthAxis> {
    @Insert("""
        INSERT INTO growth_axes(user_id,dimension_id,name,description,definition_version,updated_at)
        VALUES(#{axis.userId},#{axis.dimensionId},#{axis.name},#{axis.description},#{axis.definitionVersion},#{axis.updatedAt})
        ON CONFLICT(user_id,dimension_id) DO NOTHING
        """)
    int insertDefaultIfAbsent(@Param("axis") GrowthAxis axis);
    @Update("UPDATE growth_axes SET name=#{axis.name}, description=#{axis.description}, definition_version=#{axis.definitionVersion}, updated_at=#{axis.updatedAt} WHERE user_id=#{axis.userId} AND dimension_id=#{axis.dimensionId}")
    int updateDefinition(@Param("axis") GrowthAxis axis);
}
