package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;

import java.util.List;

/** Builds the action metadata exposed to the model, keeping unconfirmed memory drafts private. */
public final class ActionStatusSummary {
    private ActionStatusSummary() {}

    public static String format(List<ActionProposalVO> actions) {
        StringBuilder summary = new StringBuilder();
        if (actions == null) return "";
        for (ActionProposalVO action : actions) {
            summary.append("id=").append(action.getId()).append(" kind=").append(action.getKind())
                    .append(" state=").append(action.getState()).append(" version=").append(action.getVersion())
                    .append(" requiresConfirmation=").append("PENDING".equals(action.getState()));
            summary.append('\n');
        }
        return summary.toString();
    }
}
