package org.xianshen.mumirrorb.action;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.xianshen.mumirrorb.service.ActionProposalService;

/** Starts candidate generation only after the cancellable RUNNING card is committed. */
@Component
@RequiredArgsConstructor
public class ActionPreparationListener {
    private final ActionProposalService proposals;

    @Async("vaultDigestExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void prepare(ActionPreparationRequestedEvent event) {
        proposals.completePreparation(event.userId(), event.actionId(), event.preparationVersion());
    }
}
