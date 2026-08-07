package org.xianshen.mumirrorb.common.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举
 *
 * <p>统一定义系统中所有的错误码和错误信息，便于：</p>
 * <ul>
 *   <li>前后端错误码对齐</li>
 *   <li>国际化支持</li>
 *   <li>错误监控和统计</li>
 * </ul>
 *
 * <p><strong>错误码规则：</strong></p>
 * <ul>
 *   <li>200: 成功</li>
 *   <li>400: 参数校验错误</li>
 *   <li>401: 认证相关错误</li>
 *   <li>404: 资源不存在</li>
 *   <li>500: 服务端错误</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@Schema(description = "全局错误码枚举")
public enum ResultCode {

    // ========== 通用 ==========

    /**
     * 操作成功
     */
    @Schema(description = "操作成功")
    SUCCESS(200, "操作成功"),

    /**
     * 操作失败（通用）
     */
    @Schema(description = "操作失败（通用）")
    FAIL(500, "操作失败"),

    // ========== 参数校验 400 ==========

    /**
     * 参数错误（通用）
     */
    @Schema(description = "参数错误（通用）")
    PARAM_ERROR(400, "参数错误"),

    /**
     * 缺少必要参数
     */
    @Schema(description = "缺少必要参数")
    PARAM_MISSING(4001, "缺少必要参数"),

    /**
     * 参数类型错误
     */
    @Schema(description = "参数类型错误")
    PARAM_TYPE_ERROR(4002, "参数类型错误"),

    // ========== 认证 401 ==========

    /**
     * 未登录
     */
    @Schema(description = "未登录")
    UNAUTHORIZED(401, "未登录"),

    /**
     * 登录已过期
     */
    @Schema(description = "登录已过期")
    TOKEN_EXPIRED(4011, "登录已过期，请重新登录"),

    /**
     * Token 无效
     */
    @Schema(description = "Token无效")
    TOKEN_INVALID(4012, "无效的访问凭证"),

    /**
     * 密码错误
     */
    @Schema(description = "密码错误")
    PASSWORD_ERROR(4013, "密码错误"),

    // ========== 资源不存在 404 ==========

    /**
     * 记录不存在
     */
    @Schema(description = "记录不存在或已被删除")
    RECORD_NOT_FOUND(4041, "记录不存在"),

    // ========== 服务端 500 ==========

    /**
     * 数据库异常
     */
    @Schema(description = "数据库异常")
    DB_ERROR(5001, "数据库异常"),

    /**
     * 系统内部错误
     */
    @Schema(description = "系统内部错误")
    INTERNAL_ERROR(5006, "系统内部错误");

    /**
     * 错误码
     */
    private final int code;

    /**
     * 错误信息
     */
    private final String message;
}
