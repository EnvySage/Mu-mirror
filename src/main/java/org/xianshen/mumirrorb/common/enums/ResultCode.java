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

    // ========== 认证 401 ==========
    UNAUTHORIZED(401, "未登录"),
    TOKEN_EXPIRED(4011, "登录已过期，请重新登录"),
    TOKEN_INVALID(4012, "无效的访问凭证"),
    PASSWORD_ERROR(4013, "密码错误"),

    // ========== 资源不存在 404 ==========
    RECORD_NOT_FOUND(4041, "记录不存在"),

    // ========== 服务端 500 ==========
    DB_ERROR(5001, "数据库异常"),
    INTERNAL_ERROR(5006, "系统内部错误");

    private final int code;
    private final String message;
}
