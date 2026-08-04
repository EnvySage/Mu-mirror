package org.xianshen.mumirrorb.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.DTO.UserLoginDTO;
import org.xianshen.mumirrorb.pojo.DTO.UserRegisterDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.LoginVO;
import org.xianshen.mumirrorb.pojo.VO.UserVO;
import org.xianshen.mumirrorb.service.AuthService;

import java.util.UUID;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 检查认证状态
     */
    @GetMapping("/status")
    public R<String> status() {
        return R.ok("服务正常");
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public R<UserVO> register(@Valid @RequestBody UserRegisterDTO dto) {
        UserVO user = authService.register(dto);
        return R.ok("注册成功", user);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody UserLoginDTO dto) {
        LoginVO loginVO = authService.login(dto);
        return R.ok("登录成功", loginVO);
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public R<UserVO> me(HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute("userId");
        UserVO user = authService.getCurrentUser(userId);
        return R.ok(user);
    }
}
