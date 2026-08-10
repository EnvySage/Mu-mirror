package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;

/**
 * 用户配置 Mapper
 */
@Mapper
public interface SettingsMapper extends BaseMapper<UserSettings> {
}
