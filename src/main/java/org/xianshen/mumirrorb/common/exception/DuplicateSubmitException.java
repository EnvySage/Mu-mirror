package org.xianshen.mumirrorb.common.exception;

import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * 重复提交异常
 */
public class DuplicateSubmitException extends BusinessException {

    public DuplicateSubmitException() {
        super(ResultCode.DUPLICATE_SUBMIT);
    }

    public DuplicateSubmitException(String message) {
        super(ResultCode.DUPLICATE_SUBMIT, message);
    }
}
