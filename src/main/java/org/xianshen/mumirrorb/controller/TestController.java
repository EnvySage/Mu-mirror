package org.xianshen.mumirrorb.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.pojo.R;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 测试控制器（验证 JWT是否工作）
 */
@RestController
@RequestMapping("/api/test")
public class TestController {

    /**
     * 需要登录才能访问
     */
    @GetMapping("/hello")
    public R<Map<String, Object>> hello(HttpServletRequest request) {
        UUID userId = (UUID) request.getAttribute("userId");
        String username = (String) request.getAttribute("username");

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("username", username);
        data.put("message", "Hello! 你已通过 JWT 认证");

        return R.ok(data);
    }

    /**
     * 公开接口（不需要登录）
     */
    @GetMapping("/public")
    public R<String> publicApi() {
        return R.ok("这是公开接口", null);
    }
}
