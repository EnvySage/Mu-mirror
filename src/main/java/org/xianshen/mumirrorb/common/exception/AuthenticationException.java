package org.xianshen.mumirrorb.common.exception;

import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * 认证异常
 */
public class AuthenticationException extends BusinessException {

    public AuthenticationException(ResultCode resultCode) {
        super(resultCode);
    }

    public AuthenticationException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public AuthenticationException(String message) {
        super(ResultCode.UNAUTHORIZED, message);
    }
}
