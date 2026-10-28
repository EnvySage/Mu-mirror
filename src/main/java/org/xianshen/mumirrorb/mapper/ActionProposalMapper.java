package org.xianshen.mumirrorb.mapper;

import org.apache.ibatis.annotations.*;
import org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ActionProposalMapper {
    @Select("SELECT * FROM assistant_actions WHERE user_id = #{userId}::uuid AND prepare_key = #{prepareKey}")
    @Results(id = "actionProposal", value = {
            @Result(column = "payload", property = "payload", typeHandler = JsonNodeTypeHandler.class),
            @Result(column = "preview", property = "preview", typeHandler = JsonNodeTypeHandler.class),
            @Result(column = "target_versions", property = "targetVersions", typeHandler = JsonNodeTypeHandler.class),
            @Result(column = "result", property = "result", typeHandler = JsonNodeTypeHandler.class)
    })
    ActionProposal findByPrepareKey(@Param("userId") UUID userId, @Param("prepareKey") String prepareKey);

    @Select("SELECT id FROM chat_sessions WHERE id=#{sessionId}::uuid AND user_id=#{userId}::uuid FOR UPDATE")
    UUID lockMemoryCandidateSession(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Select("SELECT count(*) FROM assistant_actions WHERE user_id=#{userId}::uuid " +
            "AND source_session_id=#{sessionId}::uuid AND kind='memory.accept'")
    int countMemoryCandidates(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Select("SELECT * FROM assistant_actions WHERE user_id = #{userId}::uuid AND id = #{actionId}::uuid")
    @ResultMap("actionProposal")
    ActionProposal findOwned(@Param("userId") UUID userId, @Param("actionId") UUID actionId);

    @Select("SELECT * FROM assistant_actions WHERE user_id = #{userId}::uuid AND id = #{actionId}::uuid FOR UPDATE")
    @ResultMap("actionProposal")
    ActionProposal lockOwned(@Param("userId") UUID userId, @Param("actionId") UUID actionId);

    @Select("SELECT * FROM assistant_actions WHERE id = #{actionId}::uuid FOR UPDATE")
    @ResultMap("actionProposal")
    ActionProposal lockForCompletion(@Param("actionId") UUID actionId);

    @Select("""
            SELECT * FROM assistant_actions
            WHERE user_id = #{userId}::uuid AND source_session_id = #{sessionId}::uuid
            ORDER BY created_at, id
            """)
    @ResultMap("actionProposal")
    List<ActionProposal> listForSession(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Select("""
            SELECT * FROM assistant_actions
            WHERE user_id = #{userId}::uuid AND kind IN ('record.create', 'record.organize')
              AND (state IN ('PENDING', 'EXECUTING', 'FAILED', 'STALE')
                OR (state = 'SUCCEEDED' AND updated_at > NOW() - INTERVAL '1 day'))
            ORDER BY CASE WHEN state IN ('PENDING', 'EXECUTING') THEN 0 ELSE 1 END, created_at DESC, id
            LIMIT 200
            """)
    @ResultMap("actionProposal")
    List<ActionProposal> listRecordWorkflows(@Param("userId") UUID userId);

    @Select("SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE id = #{sessionId}::uuid AND user_id = #{userId}::uuid)")
    boolean sessionOwned(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM conversation_history
              WHERE id = #{messageId} AND user_id = #{userId}::uuid
                AND session_id = #{sessionId}::uuid AND role = 'user')
            """)
    boolean sourceMessageOwned(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId,
                               @Param("messageId") Long messageId);

    @Insert("""
            INSERT INTO assistant_actions
              (id, user_id, kind, prepare_key, state, version, preparation_status, payload, preview,
               target_versions, source_session_id, source_message_id, created_at, updated_at)
            VALUES (#{id}::uuid, #{userId}::uuid, #{kind}, #{prepareKey}, #{state}, #{version},
              #{preparationStatus}, #{payload,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              #{preview,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              #{targetVersions,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              #{sourceSessionId}::uuid, #{sourceMessageId}, #{createdAt}, #{updatedAt})
            ON CONFLICT (user_id, prepare_key) DO NOTHING
            """)
    int insertIfAbsent(ActionProposal proposal);

    @Update("""
            UPDATE assistant_actions SET state = #{p.state}, version = #{p.version},
              preparation_status = #{p.preparationStatus},
              payload = #{p.payload,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              preview = #{p.preview,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              target_versions = #{p.targetVersions,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              request_id = #{p.requestId}, execution_version = #{p.executionVersion},
              result = #{p.result,typeHandler=org.xianshen.mumirrorb.common.handler.JsonNodeTypeHandler},
              error_code = #{p.errorCode}, updated_at = #{p.updatedAt}
            WHERE id = #{p.id}::uuid AND user_id = #{p.userId}::uuid AND version = #{expectedVersion}
            """)
    int save(@Param("p") ActionProposal proposal, @Param("expectedVersion") int expectedVersion);
}
