package org.xianshen.mumirrorb.agent;

/** HTTP 409 for request-id payload conflicts or another active live run. */
public class AgentRunConflictException extends RuntimeException {
    public AgentRunConflictException(String message) {
        super(message);
    }
}
