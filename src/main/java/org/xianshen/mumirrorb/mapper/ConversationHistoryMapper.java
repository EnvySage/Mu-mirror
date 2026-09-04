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
}
