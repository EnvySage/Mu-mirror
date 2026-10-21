package org.xianshen.mumirrorb.agent;

/** HTTP 410: request identity is retained, but its conversation body was deleted. */
public class RunGoneException extends RuntimeException {
    public RunGoneException(String message) {
        super(message);
    }
}
