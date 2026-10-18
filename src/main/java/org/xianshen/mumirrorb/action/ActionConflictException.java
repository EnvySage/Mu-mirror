package org.xianshen.mumirrorb.action;

import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;

/** Carries the current card; STALE is committed even when confirmation returns a conflict. */
public class ActionConflictException extends BusinessException {
    private final ActionProposalVO latest;
    public ActionConflictException(String message, ActionProposal proposal) {
        super(409, message);
        latest = ActionProposalVO.from(proposal);
    }
    public ActionProposalVO getLatest() { return latest; }
}
