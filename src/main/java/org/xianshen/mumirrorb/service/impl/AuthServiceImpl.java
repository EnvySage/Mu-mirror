package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
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

import java.time.OffsetDateTime;

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
    private final AuthenticationManager authenticationManager;

    @Override
    public UserVO register(UserRegisterDTO dto) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "用户名已存在");
        }

        // 创建用户（使用 BCrypt 加密密码，ID 由数据库自动生成）
        User user = User.builder()
                .username(dto.getUsername())
                .passwordHash(passwordEncoder.encode(dto.getPassword()))
                .createdAt(OffsetDateTime.now())
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
        // 使用 Spring Security 认证（会自动调用 UserDetailsServiceImpl.loadUserByUsername()）
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword())
        );

        // 认证成功，获取用户信息
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String username = userDetails.getUsername();

        // 从数据库获取完整用户信息（包括 ID）
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

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
    public UserVO getCurrentUser(String userId) {
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
