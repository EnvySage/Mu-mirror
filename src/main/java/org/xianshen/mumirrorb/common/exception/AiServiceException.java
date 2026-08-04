package org.xianshen.mumirrorb.common.exception;

import org.xianshen.mumirrorb.common.enums.ResultCode;

/**
 * AI 服务异常
 */
public class AiServiceException extends BusinessException {

    public AiServiceException(ResultCode resultCode) {
        super(resultCode);
    }

    public AiServiceException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    /**
     * AI 处理失败
     */
    public static AiServiceException processFailed() {
        return new AiServiceException(ResultCode.AI_PROCESS_FAILED);
    }

    /**
     * AI 返回格式错误
     */
    public static AiServiceException formatError() {
        return new AiServiceException(ResultCode.AI_FORMAT_ERROR);
    }

    /**
     * AI 服务超时
     */
    public static AiServiceException timeout() {
        return new AiServiceException(ResultCode.AI_TIMEOUT);
    }

    /**
     * Embedding 失败
     */
    public static AiServiceException embedFailed() {
        return new AiServiceException(ResultCode.EMBED_FAILED);
    }

    /**
     * AI 服务未配置
     */
    public static AiServiceException notConfigured() {
        return new AiServiceException(ResultCode.AI_PROVIDER_NOT_FOUND);
    }

    /**
     * AI 服务不可用
     */
    public static AiServiceException unavailable() {
        return new AiServiceException(ResultCode.AI_PROVIDER_UNAVAILABLE);
    }
}
