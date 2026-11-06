package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import java.util.UUID;

/**
 * 用户配置 Mapper
 */
@Mapper
public interface SettingsMapper extends BaseMapper<UserSettings> {
    @Insert("INSERT INTO user_settings (user_id) VALUES (#{userId,typeHandler=org.xianshen.mumirrorb.common.handler.UuidTypeHandler}) ON CONFLICT (user_id) DO NOTHING")
    int ensureExists(@Param("userId") UUID userId);

    /** Serialize profile import, edits, deletion and activation for one user. */
    @Select("SELECT * FROM user_settings WHERE user_id = #{userId,typeHandler=org.xianshen.mumirrorb.common.handler.UuidTypeHandler} FOR UPDATE")
    UserSettings selectForUpdate(@Param("userId") UUID userId);
}
