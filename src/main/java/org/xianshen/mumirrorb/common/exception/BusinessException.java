package org.xianshen.mumirrorb.common.exception;

import lombok.Getter;
import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * 业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final int code;

    /**
     * 构造函数：使用 ResultCode
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 构造函数：使用 ResultCode + 自定义消息
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 构造函数：使用 code + message
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
