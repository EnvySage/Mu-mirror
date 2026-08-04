package org.xianshen.mumirrorb.controller;

import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.*;
import org.xianshen.mumirrorb.pojo.R;

/**
 * 异常处理演示控制器（开发测试用）
 */
@RestController
@RequestMapping("/api/demo/exception")
public class ExceptionDemoController {

    /**
     * 演示业务异常
     */
    @GetMapping("/business")
    public R<Void> businessException() {
        throw new BusinessException(ResultCode.FAIL, "这是一个业务异常");
    }

    /**
     * 演示资源不存在异常
     */
    @GetMapping("/not-found")
    public R<Void> notFound() {
        throw new ResourceNotFoundException("记录", "123");
    }

    /**
     * 演示认证异常
     */
    @GetMapping("/auth")
    public R<Void> auth() {
        throw new org.xianshen.mumirrorb.common.exception.AuthenticationException("请先登录");
    }

    /**
     * 演示 AI 服务异常
     */
    @GetMapping("/ai")
    public R<Void> ai() {
        throw AiServiceException.timeout();
    }

    /**
     * 演示重复提交异常
     */
    @GetMapping("/duplicate")
    public R<Void> duplicate() {
        throw new DuplicateSubmitException();
    }

    /**
     * 演示内容异常
     */
    @GetMapping("/content-empty")
    public R<Void> contentEmpty() {
        throw ContentException.empty();
    }

    /**
     * 演示内容过长异常
     */
    @GetMapping("/content-too-long")
    public R<Void> contentTooLong() {
        throw ContentException.tooLong();
    }

    /**
     * 演示参数错误异常
     */
    @GetMapping("/param-error")
    public R<Void> paramError() {
        throw new IllegalArgumentException("参数不合法");
    }

    /**
     * 演示未知异常
     */
    @GetMapping("/unknown")
    public R<Void> unknown() {
        throw new RuntimeException("这是一个未知异常");
    }
}
