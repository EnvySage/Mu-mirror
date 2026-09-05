package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.ToolCall;

/**
 * 工具调用审计 Mapper（tool_calls；只写 + 按用户统计，无更新）
 */
@Mapper
public interface ToolCallMapper extends BaseMapper<ToolCall> {
}
