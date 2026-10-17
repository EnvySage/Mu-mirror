package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.SnapshotInput;

import java.util.List;

/** Persistence for snapshot source and parent-snapshot lineage IDs. */
@Mapper
public interface SnapshotInputMapper {

    @Insert("""
            <script>
            INSERT INTO snapshot_inputs (snapshot_id, input_kind, input_id, input_role)
            VALUES
            <foreach collection='inputs' item='input' separator=','>
              (#{snapshotId}, #{input.inputKind}, #{input.inputId}, #{input.inputRole})
            </foreach>
            ON CONFLICT (snapshot_id, input_kind, input_id, input_role) DO NOTHING
            </script>
            """)
    int insertAll(@Param("snapshotId") Long snapshotId,
                  @Param("inputs") List<SnapshotInput> inputs);

    @Select("""
            SELECT snapshot_id AS "snapshotId",
                   input_kind AS "inputKind",
                   input_id AS "inputId",
                   input_role AS "inputRole",
                   created_at AS "createdAt"
            FROM snapshot_inputs
            WHERE snapshot_id = #{snapshotId}
            ORDER BY input_kind, input_id, input_role
            """)
    List<SnapshotInput> selectBySnapshotId(@Param("snapshotId") Long snapshotId);
}
