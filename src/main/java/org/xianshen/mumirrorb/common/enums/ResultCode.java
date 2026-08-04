package org.xianshen.mumirrorb.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    // ========== 通用 ==========
    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),

    // ========== 参数校验 400 ==========
    PARAM_ERROR(400, "参数错误"),
    PARAM_MISSING(4001, "缺少必要参数"),
    PARAM_TYPE_ERROR(4002, "参数类型错误"),
    CONTENT_TOO_LONG(4003, "内容超过500字限制"),
    CONTENT_EMPTY(4004, "内容不能为空"),
    CONTENT_INVALID(4005, "内容无意义，已跳过"),

    // ========== 认证 401 ==========
    UNAUTHORIZED(401, "未登录"),
    TOKEN_EXPIRED(4011, "登录已过期，请重新登录"),
    TOKEN_INVALID(4012, "无效的访问凭证"),
    PASSWORD_ERROR(4013, "密码错误"),
    PASSWORD_NOT_SET(4014, "尚未设置密码"),

    // ========== 业务异常 409 ==========
    DUPLICATE_SUBMIT(4091, "重复提交，请勿频繁操作"),
    AI_PROCESS_FAILED(4092, "AI处理失败，请重试"),
    AI_FORMAT_ERROR(4093, "AI返回格式异常"),
    AI_TIMEOUT(4094, "AI服务响应超时"),
    EMBED_FAILED(4095, "向量化处理失败"),
    RETRY_EXHAUSTED(4096, "重试次数已用尽，请手动处理"),

    // ========== 资源不存在 404 ==========
    RECORD_NOT_FOUND(4041, "记录不存在"),
    SESSION_NOT_FOUND(4042, "会话不存在"),
    SUMMARY_NOT_FOUND(4043, "总结不存在"),
    PROFILE_NOT_FOUND(4044, "画像不存在"),

    // ========== 记录状态 409 ==========
    RECORD_NOT_FAILED(4097, "记录状态不是失败状态，无法重试"),
    RECORD_ALREADY_PROCESSED(4098, "记录已处理完成"),
    RECORD_PROCESSING(4099, "记录正在处理中"),

    // ========== 服务端 500 ==========
    DB_ERROR(5001, "数据库异常"),
    REDIS_ERROR(5002, "缓存服务异常"),
    AI_PROVIDER_NOT_FOUND(5003, "未配置AI模型，请先在设置中配置"),
    AI_PROVIDER_UNAVAILABLE(5004, "AI服务不可用"),
    NETWORK_ERROR(5005, "网络连接异常"),
    INTERNAL_ERROR(5006, "系统内部错误");

    private final int code;
    private final String message;
}
