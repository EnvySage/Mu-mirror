package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ChatMemoryMapper extends BaseMapper<ChatMemory> {
    @Select("SELECT * FROM chat_memories WHERE user_id=#{userId}::uuid AND state='ACTIVE' ORDER BY source_date DESC,created_at DESC")
    List<ChatMemory> listActive(@Param("userId") UUID userId);

    @Select("SELECT * FROM chat_memories WHERE user_id=#{userId}::uuid AND id=#{memoryId}::uuid")
    ChatMemory findOwned(@Param("userId") UUID userId, @Param("memoryId") UUID memoryId);

    @Select("""
      SELECT cm.* FROM chat_memories cm
      WHERE cm.user_id=#{userId}::uuid AND cm.state='ACTIVE'
        AND (cm.expires_at IS NULL OR cm.expires_at > #{now})
        AND jsonb_typeof(cm.source_message_ids)='array'
        AND jsonb_array_length(cm.source_message_ids) > 0
        AND NOT EXISTS (
          SELECT 1 FROM jsonb_array_elements_text(cm.source_message_ids) src(message_id)
          WHERE NOT EXISTS (
            SELECT 1 FROM conversation_history h
            WHERE h.id=src.message_id::bigint AND h.user_id=cm.user_id
              AND h.session_id=cm.source_session_id AND h.role='user'
          ) OR EXISTS (
            SELECT 1 FROM chat_source_exclusions x
            WHERE x.user_id=cm.user_id AND x.source_kind='chat_message'
              AND x.source_id=src.message_id::bigint
          )
        )
      ORDER BY cm.source_date DESC,cm.created_at DESC
      """)
    List<ChatMemory> selectAuthorizedForContext(@Param("userId") UUID userId,
                                                @Param("now") OffsetDateTime now);

    @Select("SELECT * FROM chat_memories WHERE id=#{id}::uuid AND user_id=#{userId}::uuid FOR UPDATE")
    ChatMemory lockOwned(@Param("userId") UUID userId, @Param("id") UUID id);

    @Update("""
      UPDATE chat_memories SET content=#{content},kind=#{kind},event_date=#{eventDate},expires_at=#{expiresAt},
        version=version+1 WHERE id=#{id}::uuid AND user_id=#{userId}::uuid AND state='ACTIVE' AND version=#{version}
      """)
    int updateActive(@Param("userId") UUID userId, @Param("id") UUID id, @Param("version") long version,
                     @Param("content") String content, @Param("kind") String kind,
                     @Param("eventDate") java.time.LocalDate eventDate,
                     @Param("expiresAt") java.time.OffsetDateTime expiresAt);

    @Update("UPDATE chat_memories SET state='FORGOTTEN',version=version+1 WHERE id=#{id}::uuid AND user_id=#{userId}::uuid AND state='ACTIVE' AND version=#{version}")
    int forgetOwned(@Param("userId") UUID userId, @Param("id") UUID id, @Param("version") long version);
    @Insert("""
      INSERT INTO chat_memories(id,user_id,content,kind,source_session_id,source_message_ids,source_date,event_date,expires_at,state,version,created_at)
      VALUES(#{id}::uuid,#{userId}::uuid,#{content},#{kind},#{sourceSessionId}::uuid,
      #{sourceMessageIds}::jsonb,#{sourceDate},#{eventDate},#{expiresAt},'ACTIVE',1,now())
      ON CONFLICT(id) DO NOTHING""")
    int insertConfirmed(ChatMemory memory);

    @Select("<script>SELECT count(*) FROM conversation_history h WHERE h.user_id=#{userId}::uuid " +
      "AND h.session_id=#{sessionId}::uuid AND h.role='user' AND h.id IN " +
      "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
      "AND NOT EXISTS (SELECT 1 FROM chat_source_exclusions x WHERE x.user_id=h.user_id " +
      "AND x.source_kind='chat_message' AND x.source_id=h.id)</script>")
    int countOwnedUserSources(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId,
                              @Param("ids") java.util.List<Long> ids);
}
