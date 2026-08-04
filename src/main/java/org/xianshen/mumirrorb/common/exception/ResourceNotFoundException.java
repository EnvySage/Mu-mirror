package org.xianshen.mumirrorb.common.exception;

import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * 资源不存在异常
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(ResultCode resultCode) {
        super(resultCode);
    }

    public ResourceNotFoundException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public ResourceNotFoundException(String resourceName, String resourceId) {
        super(ResultCode.RECORD_NOT_FOUND, String.format("%s 不存在（ID: %s）", resourceName, resourceId));
    }
}
