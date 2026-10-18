package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionEditDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import java.util.List;
import java.util.UUID;

public interface ActionProposalService {
    ActionProposalVO prepare(UUID userId, ActionPrepareDTO body);
    ActionProposalVO edit(UUID userId, UUID actionId, ActionEditDTO body);
    ActionProposalVO confirm(UUID userId, UUID actionId, ActionConfirmDTO body);
    ActionProposalVO cancel(UUID userId, UUID actionId, int expectedVersion);
    ActionProposalVO get(UUID userId, UUID actionId);
    List<ActionProposalVO> listForSession(UUID userId, UUID sessionId);
    /** Internal worker callback, never exposed as an HTTP endpoint or model tool. */
    void completeAsync(UUID actionId, int executionVersion, JsonNode result);
}
