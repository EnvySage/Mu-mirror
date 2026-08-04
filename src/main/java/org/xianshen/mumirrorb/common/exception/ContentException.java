package org.xianshen.mumirrorb.common.exception;

import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * 内容相关异常
 */
public class ContentException extends BusinessException {

    public ContentException(ResultCode resultCode) {
        super(resultCode);
    }

    public ContentException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    /**
     * 内容为空
     */
    public static ContentException empty() {
        return new ContentException(ResultCode.CONTENT_EMPTY);
    }

    /**
     * 内容过长
     */
    public static ContentException tooLong() {
        return new ContentException(ResultCode.CONTENT_TOO_LONG);
    }

    /**
     * 内容无意义
     */
    public static ContentException invalid() {
        return new ContentException(ResultCode.CONTENT_INVALID);
    }
}
