package org.xianshen.mumirrorb.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.pojo.R;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 异常处理优先级：
 * 1. 业务异常（BusinessException 及其子类）
 * 2. Spring Security 异常
 * 3. 参数校验异常
 * 4. 其他异常
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /**
     * 处理业务异常（通用）
     */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.error("业务异常 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理资源不存在异常
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleResourceNotFoundException(ResourceNotFoundException e, HttpServletRequest request) {
        log.error("资源不存在 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理认证异常（自定义）
     */
    @ExceptionHandler(org.xianshen.mumirrorb.common.exception.AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleCustomAuthenticationException(
            org.xianshen.mumirrorb.common.exception.AuthenticationException e,
            HttpServletRequest request) {
        log.error("认证失败 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理 AI 服务异常
     */
    @ExceptionHandler(AiServiceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public R<Void> handleAiServiceException(AiServiceException e, HttpServletRequest request) {
        log.error("AI 服务异常 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理重复提交异常
     */
    @ExceptionHandler(DuplicateSubmitException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<Void> handleDuplicateSubmitException(DuplicateSubmitException e, HttpServletRequest request) {
        log.warn("重复提交 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理内容异常
     */
    @ExceptionHandler(ContentException.class)
    public R<Void> handleContentException(ContentException e, HttpServletRequest request) {
        log.error("内容异常 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    // ==================== Spring Security 异常 ====================

    /**
     * 处理 Spring Security 认证异常（用户名/密码错误）
     */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleBadCredentialsException(BadCredentialsException e, HttpServletRequest request) {
        log.error("认证失败 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(ResultCode.PASSWORD_ERROR);
    }

    /**
     * 处理用户名不存在异常
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleUsernameNotFoundException(UsernameNotFoundException e, HttpServletRequest request) {
        log.error("用户不存在 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(ResultCode.PASSWORD_ERROR, "用户名或密码错误");
    }

    /**
     * 处理 Spring Security 认证异常（通用）
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleAuthenticationException(AuthenticationException e, HttpServletRequest request) {
        log.error("认证异常 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 处理访问拒绝异常（权限不足）
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.error("访问拒绝 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(403, "权限不足，无法访问");
    }

    // ==================== 参数校验异常 ====================

    /**
     * 处理 @Valid 参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.error("参数校验失败 - URL: {}, 错误: {}", request.getRequestURI(), message);
        return R.fail(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.error("参数绑定失败 - URL: {}, 错误: {}", request.getRequestURI(), message);
        return R.fail(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e, HttpServletRequest request) {
        log.error("缺少参数 - URL: {}, 参数: {}", request.getRequestURI(), e.getParameterName());
        return R.fail(ResultCode.PARAM_MISSING.getCode(), "缺少参数: " + e.getParameterName());
    }

    /**
     * 处理参数类型不匹配异常
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.error("参数类型错误 - URL: {}, 参数: {}", request.getRequestURI(), e.getName());
        return R.fail(ResultCode.PARAM_TYPE_ERROR.getCode(), "参数类型错误: " + e.getName());
    }

    // ==================== 其他异常 ====================

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.error("请求方法不支持 - URL: {}, 方法: {}", request.getRequestURI(), e.getMethod());
        return R.fail(405, "请求方法不支持: " + e.getMethod());
    }

    /**
     * 处理 404 异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoHandlerFoundException(NoHandlerFoundException e, HttpServletRequest request) {
        log.error("接口不存在 - URL: {}", request.getRequestURI());
        return R.fail(404, "接口不存在");
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        log.error("非法参数 - URL: {}, 错误: {}", request.getRequestURI(), e.getMessage());
        return R.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
    }

    /**
     * 处理其他所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 - URL: {}", request.getRequestURI(), e);
        return R.fail(ResultCode.INTERNAL_ERROR);
    }
}
