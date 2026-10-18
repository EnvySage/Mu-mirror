package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.xianshen.mumirrorb.service.ActionProposalService;

@Component
public class RecordMemoryActionCompletionListener {
    private final ActionProposalService actions;
    private final ObjectMapper mapper;

    public RecordMemoryActionCompletionListener(ActionProposalService actions, ObjectMapper mapper) {
        this.actions = actions;
        this.mapper = mapper;
    }

    @TransactionalEventListener
    public void complete(RecordMemoryActionCompletedEvent event) {
        var result = mapper.createObjectNode().put("state", "SUCCEEDED")
                .put("recordId", event.recordId()).put("memoryState", event.memoryState())
                .put("outcome", "READY".equals(event.memoryState()) ? "APPLIED" : "SAVED_ONLY_FALLBACK");
        if (event.errorCode() != null) result.put("errorCode", event.errorCode());
        if (event.memoryFailureReason() != null) result.put("memoryFailureReason", event.memoryFailureReason());
        if ("NONE".equals(event.memoryState())) result.put("message", "记录仅保存，记忆未加入；已确认的待办效果保留");
        actions.completeAsync(event.actionId(), event.executionVersion(), result);
    }
}
