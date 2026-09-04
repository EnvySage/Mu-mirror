package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.ConversationHistory;

import java.util.List;

/**
 * 对话历史 Mapper（设计文档 6.6）
 */
@Mapper
public interface ConversationHistoryMapper extends BaseMapper<ConversationHistory> {

    /**
     * 指定会话的历史消息（时间正序，前端渲染顺序）
     */
    @Select("""
            SELECT * FROM conversation_history
            WHERE session_id = #{sessionId}::uuid
              AND user_id = #{userId}::uuid
            ORDER BY created_at ASC
            """)
    List<ConversationHistory> selectBySession(@Param("sessionId") java.util.UUID sessionId,
                                               @Param("userId") java.util.UUID userId);

    /**
     * 会话的最近 N 条消息（时间倒序取 N 条，Java 侧再反转成正序）
     *
     * <p>用于对话上下文截断（最近 3 轮 = 6 条消息）。</p>
     */
    @Select("""
            SELECT * FROM conversation_history
            WHERE session_id = #{sessionId}::uuid
              AND user_id = #{userId}::uuid
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ConversationHistory> selectRecent(@Param("sessionId") java.util.UUID sessionId,
                                            @Param("userId") java.util.UUID userId,
                                            @Param("limit") int limit);
}
