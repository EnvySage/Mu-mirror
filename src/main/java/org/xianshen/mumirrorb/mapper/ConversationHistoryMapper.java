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

    default List<ConversationHistory> selectUserMessages(java.util.UUID sessionId, java.util.UUID userId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationHistory>()
                .eq(ConversationHistory::getSessionId, sessionId)
                .eq(ConversationHistory::getUserId, userId)
                .eq(ConversationHistory::getRole, "user")
                .notExists("SELECT 1 FROM chat_source_exclusions x "
                        + "WHERE x.user_id=conversation_history.user_id "
                        + "AND x.source_kind='chat_message' "
                        + "AND x.source_id=conversation_history.id")
                .orderByAsc(ConversationHistory::getCreatedAt)
                .orderByAsc(ConversationHistory::getId));
    }

    /**
     * 指定会话的历史消息（时间正序，前端渲染顺序）
     *
     * <p>实现走 MP wrapper（autoResultMap）：@Select 返回实体时 JSONB 字段靠全局注册表
     * 自动映射，多个 List handler 竞争会选中错误的 Handler（E2E 联调实测），wrapper
     * 查询按 @TableField 的 typeHandler 精确映射。</p>
     */
    default List<ConversationHistory> selectBySession(java.util.UUID sessionId, java.util.UUID userId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationHistory>()
                .eq(ConversationHistory::getSessionId, sessionId)
                .eq(ConversationHistory::getUserId, userId)
                .orderByAsc(ConversationHistory::getCreatedAt));
    }

    /**
     * 会话的最近 N 条消息（时间倒序取 N 条，Java 侧再反转成正序）
     *
     * <p>用于对话上下文截断（最近 3 轮 = 6 条消息）。</p>
     */
    default List<ConversationHistory> selectRecent(java.util.UUID sessionId, java.util.UUID userId, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationHistory>()
                .eq(ConversationHistory::getSessionId, sessionId)
                .eq(ConversationHistory::getUserId, userId)
                .orderByDesc(ConversationHistory::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /** Future-model context only: retained UI history is deliberately handled by selectBySession. */
    @org.apache.ibatis.annotations.Select("""
      SELECT h.id,h.session_id AS "sessionId",h.user_id AS "userId",h.role,h.content,
             h.is_fallback AS "isFallback",h.created_at AS "createdAt"
      FROM conversation_history h
      WHERE h.session_id=#{sessionId}::uuid AND h.user_id=#{userId}::uuid
        AND NOT EXISTS (SELECT 1 FROM chat_source_exclusions x
          WHERE x.user_id=h.user_id AND x.source_kind='chat_message' AND x.source_id=h.id)
        AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(h.context_source_message_ids) src(value)
          JOIN chat_source_exclusions x ON x.user_id=h.user_id AND x.source_kind='chat_message'
            AND x.source_id=src.value::bigint)
      ORDER BY h.created_at DESC,h.id DESC LIMIT #{limit}
      """)
    List<ConversationHistory> selectRecentForModel(@org.apache.ibatis.annotations.Param("sessionId") java.util.UUID sessionId,
                                                   @org.apache.ibatis.annotations.Param("userId") java.util.UUID userId,
                                                   @org.apache.ibatis.annotations.Param("limit") int limit);

    @org.apache.ibatis.annotations.Select("""
      SELECT DISTINCT h.id FROM conversation_history h
      JOIN LATERAL jsonb_array_elements_text(h.context_source_message_ids) src(value) ON TRUE
      WHERE h.user_id=#{userId}::uuid AND h.role='assistant'
        AND src.value::bigint = ANY(#{sourceIds}::bigint[])
      """)
    List<Long> selectAssistantRepliesDependingOn(@org.apache.ibatis.annotations.Param("userId") java.util.UUID userId,
                                                   @org.apache.ibatis.annotations.Param("sourceIds") Long[] sourceIds);
}
