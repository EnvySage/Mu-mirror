package org.xianshen.mumirrorb.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.xianshen.mumirrorb.common.enums.ResultCode;

import java.io.Serializable;

/**
 * 统一返回体
 *
 * <p>所有 API 接口的统一响应格式，包含：</p>
 * <ul>
 *   <li>code - 状态码（200表示成功，其他表示失败）</li>
 *   <li>message - 提示信息</li>
 *   <li>data - 响应数据（成功时返回，失败时为 null）</li>
 *   <li>timestamp - 响应时间戳</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>
 * // 成功响应
 * R.ok("操作成功", data)
 * R.ok(data)
 *
 * // 失败响应
 * R.fail(ResultCode.PARAM_ERROR, "参数错误")
 * R.fail(ResultCode.RECORD_NOT_FOUND)
 * </pre>
 */
@Data
@Schema(description = "统一响应体 - 所有API接口的返回格式")
public class R<T> implements Serializable {

    /**
     * 状态码
     *
     * <p>200 表示成功，其他值表示失败</p>
     */
    @Schema(description = "状态码（200表示成功）", example = "200")
    private int code;

    /**
     * 提示信息
     *
     * <p>成功时为操作提示，失败时为错误信息</p>
     */
    @Schema(description = "提示信息", example = "操作成功")
    private String message;

    /**
     * 响应数据
     *
     * <p>成功时返回具体数据，失败时为 null</p>
     */
    @Schema(description = "响应数据（成功时返回）")
    private T data;

    /**
     * 响应时间戳
     *
     * <p>毫秒级时间戳，用于调试和日志</p>
     */
    @Schema(description = "响应时间戳（毫秒）", example = "1691234567890")
    private long timestamp;

    private R() {
        this.timestamp = System.currentTimeMillis();
    }

    // ========== 成功 ==========

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(ResultCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    public static <T> R<T> ok(String message, T data) {
        R<T> r = new R<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(message);
        r.setData(data);
        return r;
    }

    // ========== 失败（用 ResultCode） ==========

    public static <T> R<T> fail(ResultCode resultCode) {
        R<T> r = new R<>();
        r.setCode(resultCode.getCode());
        r.setMessage(resultCode.getMessage());
        return r;
    }

    public static <T> R<T> fail(ResultCode resultCode, String message) {
        R<T> r = new R<>();
        r.setCode(resultCode.getCode());
        r.setMessage(message);
        return r;
    }

    // ========== 失败（直接传 code + message） ==========

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    // ========== 快捷方法 ==========

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
