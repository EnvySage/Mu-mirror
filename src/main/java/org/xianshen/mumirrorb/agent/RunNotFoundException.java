package org.xianshen.mumirrorb.agent;

/** HTTP 404 used for unknown runs and runs owned by another user. */
public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException() {
        super("对话运行不存在");
    }
}
