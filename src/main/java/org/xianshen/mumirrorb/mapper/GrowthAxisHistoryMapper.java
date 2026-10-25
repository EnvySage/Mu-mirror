package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DTO.GrowthAxisSnapshotDTO;

import java.util.UUID;

/** Append-only audit ledger for growth axis definitions. */
@Mapper
public interface GrowthAxisHistoryMapper {

    @Insert("""
            INSERT INTO growth_axis_history(user_id, dimension_id, definition_version, name, description)
            VALUES(#{userId}::uuid, #{dimensionId}, #{definitionVersion}, #{name}, #{description})
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("dimensionId") String dimensionId,
                       @Param("definitionVersion") Long definitionVersion,
                       @Param("name") String name,
                       @Param("description") String description);

    @Select("""
            SELECT name, description FROM growth_axis_history
            WHERE user_id=#{userId}::uuid AND dimension_id=#{dimensionId}
              AND definition_version=#{definitionVersion}
            """)
    GrowthAxisSnapshotDTO findSnapshot(@Param("userId") UUID userId,
                                       @Param("dimensionId") String dimensionId,
                                       @Param("definitionVersion") Long definitionVersion);
}
