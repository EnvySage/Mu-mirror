package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/** Append-only audit ledger for replaced growth entries. */
@Mapper
public interface GrowthEntryHistoryMapper {

    @Insert("""
            INSERT INTO growth_entry_history(
                entry_id, user_id, dimension_id, points, reason,
                definition_version, entry_version, replacement_request_id
            ) VALUES(
                #{entryId}, #{userId}::uuid, #{dimensionId}, #{points}, #{reason},
                #{definitionVersion}, #{entryVersion}, #{replacementRequestId}::uuid
            )
            """)
    int insert(@Param("entryId") Long entryId,
               @Param("userId") UUID userId,
               @Param("dimensionId") String dimensionId,
               @Param("points") Integer points,
               @Param("reason") String reason,
               @Param("definitionVersion") Long definitionVersion,
               @Param("entryVersion") Long entryVersion,
               @Param("replacementRequestId") UUID replacementRequestId);
}
