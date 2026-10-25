package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.ActionExecution;
import org.xianshen.mumirrorb.action.ActionHandler;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryCandidateCapTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void fourthMemoryCandidateForDailySessionIsRejected() {
        UUID user = UUID.randomUUID(); UUID session = UUID.randomUUID();
        ActionProposalMapper mapper = mock(ActionProposalMapper.class);
        ActionProposalServiceImpl service = new ActionProposalServiceImpl(mapper, List.of(memoryHandler()));
        when(mapper.findByPrepareKey(user, "day-key-new")).thenReturn(null);
        when(mapper.lockMemoryCandidateSession(user, session)).thenReturn(session);
        when(mapper.countMemoryCandidates(user, session)).thenReturn(3);
        when(mapper.sessionOwned(user, session)).thenReturn(true);
        when(mapper.sourceMessageOwned(user, session, 18L)).thenReturn(true);
        when(mapper.insertIfAbsent(any())).thenReturn(1);

        assertThrows(BusinessException.class, () -> service.prepare(user, memoryRequest(session, "day-key-new")));

        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test void repeatedPrepareKeyReturnsExistingCardEvenAtDailyLimit() {
        UUID user = UUID.randomUUID(); UUID session = UUID.randomUUID();
        ActionProposalMapper mapper = mock(ActionProposalMapper.class);
        ActionProposalServiceImpl service = new ActionProposalServiceImpl(mapper, List.of(memoryHandler()));
        ActionProposal existing = candidate("same-key");
        existing.setId(UUID.randomUUID()); existing.setUserId(user); existing.setSourceSessionId(session);
        when(mapper.findByPrepareKey(user, "same-key")).thenReturn(existing);

        var returned = service.prepare(user, memoryRequest(session, "same-key"));

        assertEquals(existing.getId(), returned.getId());
        verify(mapper, never()).lockMemoryCandidateSession(any(), any());
        verify(mapper, never()).countMemoryCandidates(any(), any());
        verify(mapper, never()).listForSession(any(), any());
        verify(mapper, never()).insertIfAbsent(any());
    }

    private static ActionPrepareDTO memoryRequest(UUID session, String key) {
        return new ActionPrepareDTO("memory.accept", JSON.createObjectNode().set("memoryDraft", JSON.createObjectNode()),
                key, session, 18L);
    }

    private static ActionProposal candidate(String key) {
        ActionProposal proposal = new ActionProposal();
        proposal.setId(UUID.randomUUID()); proposal.setUserId(UUID.randomUUID()); proposal.setKind("memory.accept");
        proposal.setPrepareKey(key); proposal.setState("PENDING"); proposal.setVersion(1);
        proposal.setPreparationStatus("READY"); proposal.setPayload(JSON.createObjectNode());
        proposal.setPreview(JSON.createObjectNode().set("targetVersions", JSON.createObjectNode()));
        proposal.setTargetVersions(JSON.createObjectNode());
        return proposal;
    }

    private static ActionHandler memoryHandler() {
        return new ActionHandler() {
            @Override public Set<String> kinds() { return Set.of("memory.accept"); }
            @Override public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
                return JSON.createObjectNode().set("targetVersions", JSON.createObjectNode());
            }
            @Override public ActionExecution execute(org.xianshen.mumirrorb.action.AuthorizedAction action) {
                return ActionExecution.applied(JSON.createObjectNode());
            }
        };
    }
}
