package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.JwtUtils;
import org.xianshen.mumirrorb.mapper.UserMapper;
import org.xianshen.mumirrorb.pojo.DO.User;
import org.xianshen.mumirrorb.pojo.DTO.UserLoginDTO;
import org.xianshen.mumirrorb.pojo.DTO.UserRegisterDTO;
import org.xianshen.mumirrorb.pojo.VO.LoginVO;
import org.xianshen.mumirrorb.pojo.VO.UserVO;
import org.xianshen.mumirrorb.service.AuthService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserVO register(UserRegisterDTO dto) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户名已存在");
        }

        // 创建用户（使用 BCrypt 加密密码）
        User user = User.builder()
                .id(UUID.randomUUID())
                .username(dto.getUsername())
                .passwordHash(passwordEncoder.encode(dto.getPassword()))
                .createdAt(LocalDateTime.now())
                .build();

        userMapper.insert(user);
        log.info("用户注册成功：{}", user.getUsername());

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    public LoginVO login(UserLoginDTO dto) {
        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR.getCode(), "用户名或密码错误");
        }

        // 验证密码（使用 BCrypt）
        if (!passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR.getCode(), "用户名或密码错误");
        }

        // 生成 Token
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        log.info("用户登录成功：{}", user.getUsername());

        UserVO userVO = UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();

        return LoginVO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400L) // 24小时
                .user(userVO)
                .build();
    }

    @Override
    public UserVO getCurrentUser(UUID userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户不存在");
        }

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
