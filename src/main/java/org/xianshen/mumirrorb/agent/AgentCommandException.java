package org.xianshen.mumirrorb.agent;

import org.springframework.http.HttpStatus;

/** Stable command API failure without leaking stack traces or business internals. */
public class AgentCommandException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AgentCommandException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static AgentCommandException unauthorized() {
        return new AgentCommandException(HttpStatus.UNAUTHORIZED, "INVALID_COMMAND_GRANT");
    }

    public static AgentCommandException forbidden() {
        return new AgentCommandException(HttpStatus.FORBIDDEN, "COMMAND_NOT_AUTHORIZED");
    }

    public static AgentCommandException conflict(String code) {
        return new AgentCommandException(HttpStatus.CONFLICT, code);
    }

    public static AgentCommandException invalid(String code) {
        return new AgentCommandException(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }

    public static AgentCommandException unavailable() {
        return new AgentCommandException(HttpStatus.SERVICE_UNAVAILABLE, "WRITE_UNAVAILABLE");
    }
}
