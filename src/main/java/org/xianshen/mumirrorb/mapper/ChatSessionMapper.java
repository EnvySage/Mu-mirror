package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.ChatSession;

/**
 * 会话 Mapper（设计文档 6.6）
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
