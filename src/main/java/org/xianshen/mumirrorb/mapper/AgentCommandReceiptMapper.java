package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.AgentCommandReceipt;

import java.util.UUID;

@Mapper
public interface AgentCommandReceiptMapper {

    @Select("""
            SELECT user_id, call_id, run_id, command_name AS command, request_hash,
                   result_json::text AS result_json, created_at
            FROM agent_command_receipts
            WHERE user_id = #{userId} AND call_id = #{callId}
            """)
    AgentCommandReceipt findByUserAndCall(@Param("userId") UUID userId, @Param("callId") UUID callId);

    @Select("""
            SELECT user_id, call_id, run_id, command_name AS command, request_hash,
                   result_json::text AS result_json, created_at
            FROM agent_command_receipts
            WHERE user_id = #{userId} AND run_id = #{runId} AND call_id = #{callId}
            """)
    AgentCommandReceipt findByRunAndCall(@Param("userId") UUID userId,
                                         @Param("runId") UUID runId,
                                         @Param("callId") UUID callId);

    @Insert("""
            INSERT INTO agent_command_receipts(user_id, call_id, run_id, command_name, request_hash, result_json)
            VALUES (#{userId}, #{callId}, #{runId}, #{command}, #{requestHash}, CAST(#{resultJson} AS jsonb))
            """)
    int insert(AgentCommandReceipt receipt);
}
