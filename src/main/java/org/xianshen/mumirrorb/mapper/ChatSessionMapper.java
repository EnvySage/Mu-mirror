package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;

/**
 * 会话 Mapper（设计文档 6.6）
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM chat_sessions WHERE user_id=#{userId}::uuid AND day_key=#{day}")
    ChatSession findDaily(@org.apache.ibatis.annotations.Param("userId") java.util.UUID userId,
                          @org.apache.ibatis.annotations.Param("day") java.time.LocalDate day);

    @org.apache.ibatis.annotations.Insert("""
      INSERT INTO chat_sessions(id,user_id,title,day_key,timezone,created_at,updated_at)
      VALUES(#{id}::uuid,#{userId}::uuid,#{title},#{dayKey},#{timezone},#{createdAt},#{updatedAt})
      ON CONFLICT(user_id,day_key) WHERE day_key IS NOT NULL DO NOTHING""")
    int insertDaily(ChatSession session);
}
