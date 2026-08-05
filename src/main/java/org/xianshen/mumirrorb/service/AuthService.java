package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.UserLoginDTO;
import org.xianshen.mumirrorb.pojo.DTO.UserRegisterDTO;
import org.xianshen.mumirrorb.pojo.VO.LoginVO;
import org.xianshen.mumirrorb.pojo.VO.UserVO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 用户注册
     */
    UserVO register(UserRegisterDTO dto);

    /**
     * 用户登录
     */
    LoginVO login(UserLoginDTO dto);

    /**
     * 获取当前用户信息
     */
    UserVO getCurrentUser(String userId);
}
