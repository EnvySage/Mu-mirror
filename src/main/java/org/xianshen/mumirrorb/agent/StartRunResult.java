package org.xianshen.mumirrorb.agent;

import org.xianshen.mumirrorb.pojo.DO.AgentRun;

/** Result of the short request-acceptance transaction. */
public record StartRunResult(AgentRun run, ChatInvocation invocation, boolean created) {
}
