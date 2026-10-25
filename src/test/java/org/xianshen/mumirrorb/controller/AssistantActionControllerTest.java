package org.xianshen.mumirrorb.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.xianshen.mumirrorb.common.exception.GlobalExceptionHandler;
import org.xianshen.mumirrorb.service.ActionProposalService;
import org.xianshen.mumirrorb.action.ActionConflictException;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssistantActionControllerTest {
    private final UUID owner = UUID.randomUUID();
    private ActionProposalService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ActionProposalService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AssistantActionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.toString(), "test", java.util.List.of()));
    }

    @AfterEach void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void prepareUsesAuthenticatedOwnerAndCannotSubmit() throws Exception {
        mvc.perform(post("/assistant/actions/prepare").contentType("application/json")
                        .content("{\"kind\":\"test.update\",\"payload\":{},\"prepareKey\":\"key\",\"userId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(service).prepare(eq(owner), any());
        verify(service, never()).confirm(any(), any(), any());
    }

    @Test
    void confirmationHasDedicatedEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/assistant/actions/" + id + "/confirm").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"requestId\":\"request\"}"))
                .andExpect(status().isOk());
        verify(service).confirm(eq(owner), eq(id), any());
    }

    @Test
    void editCancelGetAndSessionListUseAuthenticatedOwner() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        mvc.perform(patch("/assistant/actions/" + id).contentType("application/json")
                        .content("{\"expectedVersion\":1,\"payload\":{}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/assistant/actions/" + id + "/cancel").contentType("application/json")
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get("/assistant/actions/" + id)).andExpect(status().isOk());
        mvc.perform(get("/assistant/actions").param("sessionId", sessionId.toString())).andExpect(status().isOk());
        verify(service).edit(eq(owner), eq(id), any());
        verify(service).cancel(owner, id, 2);
        verify(service).get(owner, id);
        verify(service).listForSession(owner, sessionId);
    }

    @Test
    void missingAuthenticationCannotPrepareOrConfirm() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/assistant/actions/prepare").contentType("application/json")
                        .content("{\"kind\":\"test.update\",\"payload\":{},\"prepareKey\":\"key\"}"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/assistant/actions/" + UUID.randomUUID() + "/confirm").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"requestId\":\"request\"}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void targetConflictReturnsLatestCardInExistingResponseEnvelope() throws Exception {
        UUID id = UUID.randomUUID();
        ActionProposal current = new ActionProposal();
        current.setId(id);
        current.setVersion(2);
        current.setState("STALE");
        when(service.confirm(eq(owner), eq(id), any())).thenThrow(new ActionConflictException("目标已变化", current));
        mvc.perform(post("/assistant/actions/" + id + "/confirm").contentType("application/json")
                        .content("{\"expectedVersion\":1,\"requestId\":\"request\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.state").value("STALE"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void incompleteCancellationDoesNotReachService() throws Exception {
        mvc.perform(post("/assistant/actions/" + UUID.randomUUID() + "/cancel")
                        .contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void asyncCompletionIsNotAnHttpEndpoint() throws Exception {
        mvc.perform(post("/assistant/actions/" + UUID.randomUUID() + "/complete")
                        .contentType("application/json").content("{\"outcome\":\"APPLIED\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
