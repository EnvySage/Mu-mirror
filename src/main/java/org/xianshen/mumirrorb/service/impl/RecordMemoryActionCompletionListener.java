package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.service.ActionProposalService;

@Component
public class RecordMemoryActionCompletionListener {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecordMemoryActionCompletionListener.class);
    private final ActionProposalService actions;
    private final ObjectMapper mapper;

    public RecordMemoryActionCompletionListener(ActionProposalService actions, ObjectMapper mapper) {
        this.actions = actions;
        this.mapper = mapper;
    }

    // 直接监听而不是事务阶段监听：索引完成回调发生在 after-commit 阶段，
    // 该阶段没有可参与的事务，TransactionalEventListener 会丢弃事件，action 永远停在 EXECUTING。
    // completeAsync 自身带行锁和状态/执行代次校验，重复投递是幂等的。
    @org.springframework.context.event.EventListener
    public void complete(RecordMemoryActionCompletedEvent event) {
        log.info("收到操作卡完成事件，actionId={}, executionVersion={}, recordId={}, memoryState={}",
                event.actionId(), event.executionVersion(), event.recordId(), event.memoryState());
        var result = mapper.createObjectNode().put("state", "SUCCEEDED")
                .put("recordId", event.recordId()).put("memoryState", event.memoryState())
                .put("outcome", "READY".equals(event.memoryState()) ? "APPLIED" : "SAVED_ONLY_FALLBACK");
        if (event.errorCode() != null) result.put("errorCode", event.errorCode());
        if (event.memoryFailureReason() != null) result.put("memoryFailureReason", event.memoryFailureReason());
        if ("NONE".equals(event.memoryState())) result.put("message", "记录仅保存，记忆未加入；已确认的待办效果保留");
        actions.completeAsync(event.actionId(), event.executionVersion(), result);
    }
}
