package org.xianshen.mumirrorb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.action.ActionExecution;
import org.xianshen.mumirrorb.action.ActionHandler;
import org.xianshen.mumirrorb.action.AuthorizedAction;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DTO.ActionConfirmDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionEditDTO;
import org.xianshen.mumirrorb.pojo.DTO.ActionPrepareDTO;
import org.xianshen.mumirrorb.service.impl.ActionProposalServiceImpl;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests only: no Spring context, datasource, or application DB defaults. */
class ActionProposalServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID owner = UUID.randomUUID();
    private ActionProposalMapper mapper;
    private ActionProposalServiceImpl service;
    private RecordingHandler handler;
    private ActionProposal persisted;

    @BeforeEach
    void setUp() {
        mapper = mock(ActionProposalMapper.class);
        handler = new RecordingHandler();
        service = new ActionProposalServiceImpl(mapper, List.of(handler));
        when(mapper.findByPrepareKey(any(), anyString())).thenAnswer(call -> persisted != null
                && persisted.getUserId().equals(call.getArgument(0))
                && persisted.getPrepareKey().equals(call.getArgument(1)) ? persisted : null);
        when(mapper.insertIfAbsent(any())).thenAnswer(call -> {
            persisted = call.getArgument(0);
            return 1;
        });
        when(mapper.findOwned(any(), any())).thenAnswer(call -> owned(call.getArgument(0), call.getArgument(1)));
        when(mapper.lockOwned(any(), any())).thenAnswer(call -> owned(call.getArgument(0), call.getArgument(1)));
        when(mapper.save(any(), anyInt())).thenReturn(1);
    }

    @Test
    void prepareOnlyPersistsPendingAndDoesNotExecute() {
        var card = service.prepare(owner, request("key"));
        assertEquals("PENDING", card.getState());
        assertEquals("READY", card.getPreparationStatus());
        assertEquals(1, card.getVersion());
        assertEquals(0, handler.executions);
        assertEquals(json("{\"todo:7\":1}"), card.getTargetVersions());
    }

    @Test
    void otherUsersCannotGetEditOrConfirm() {
        var card = service.prepare(owner, request("key"));
        UUID stranger = UUID.randomUUID();
        assertEquals(4041, assertThrows(BusinessException.class, () -> service.get(stranger, card.getId())).getCode());
        assertEquals(4041, assertThrows(BusinessException.class, () -> service.edit(stranger, card.getId(),
                new ActionEditDTO(1, json("{}")))).getCode());
        assertEquals(4041, assertThrows(BusinessException.class, () -> service.confirm(stranger, card.getId(),
                new ActionConfirmDTO(1, "request"))).getCode());
        assertEquals(0, handler.executions);
    }

    @Test
    void unknownKindIsRejected() {
        ActionPrepareDTO body = request("key");
        body.setKind("arbitrary.sql");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.prepare(owner, body)).getCode());
        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void duplicateConfirmExecutesOnce() {
        var card = service.prepare(owner, request("key"));
        var first = service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "first-device"));
        var retry = service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "second-device"));
        assertEquals("SUCCEEDED", retry.getState());
        assertEquals(first.getResult(), retry.getResult());
        assertEquals("APPLIED", retry.getResult().path("outcome").asText());
        assertEquals(1, handler.executions);
    }

    @Test
    void lostResponseCanRecover() {
        var card = service.prepare(owner, request("key"));
        service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "lost-response"));
        var recovered = new ActionProposalServiceImpl(mapper, List.of(handler)).get(owner, card.getId());
        assertEquals("SUCCEEDED", recovered.getState());
        assertEquals(7, recovered.getResult().path("id").asInt());
        assertEquals(1, handler.executions);
    }

    @Test
    void staleTargetDoesNotExecute() {
        var card = service.prepare(owner, request("key"));
        handler.targetVersion++;
        var error = assertThrows(BusinessException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "request")));
        assertEquals(409, error.getCode());
        assertEquals("STALE", service.get(owner, card.getId()).getState());
        assertEquals(0, handler.executions);
    }

    @Test
    void cancelledPrepareKeyStaysCancelled() {
        var card = service.prepare(owner, request("key"));
        service.cancel(owner, card.getId(), 1);
        handler.targetVersion++;
        var retry = service.prepare(owner, request("key"));
        assertEquals(card.getId(), retry.getId());
        assertEquals("CANCELLED", retry.getState());
        assertThrows(BusinessException.class, () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(2, "retry")));
        assertEquals("PENDING", service.prepare(owner, request("new-user-instruction")).getState());
        assertEquals(0, handler.executions);
    }

    @Test
    void editingRequiresNewConfirmationVersion() {
        var card = service.prepare(owner, request("key"));
        var edited = service.edit(owner, card.getId(), new ActionEditDTO(1, json("{\"title\":\"new\"}")));
        assertEquals(2, edited.getVersion());
        assertThrows(BusinessException.class, () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "old")));
        assertEquals(0, handler.executions);
        assertEquals("SUCCEEDED", service.confirm(owner, card.getId(), new ActionConfirmDTO(2, "new")).getState());
    }

    @Test
    void runningPreparationCannotConfirm() {
        var card = service.prepare(owner, request("key"));
        persisted.setPreparationStatus("RUNNING");
        assertThrows(BusinessException.class, () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "request")));
        assertEquals(0, handler.executions);
    }

    @Test
    void runningPreparationCanStillBeCancelled() {
        var card = service.prepare(owner, request("key"));
        persisted.setPreparationStatus("RUNNING");
        assertEquals("CANCELLED", service.cancel(owner, card.getId(), 1).getState());
        assertEquals("CANCELLED", service.prepare(owner, request("key")).getState());
        assertEquals(0, handler.executions);
    }

    @Test
    void asynchronousPreparationPersistsRunningThenCompletesReady() {
        AsyncHandler async = new AsyncHandler();
        service = new ActionProposalServiceImpl(mapper, List.of(async));
        var request = new ActionPrepareDTO("test.async", json("{}"), "async-key", null, null);
        var running = service.prepare(owner, request);
        assertEquals("RUNNING", running.getPreparationStatus());
        assertEquals(1, running.getVersion());
        assertEquals(0, async.completions);
        when(mapper.lockOwned(owner, running.getId())).thenReturn(persisted);

        service.completePreparation(owner, running.getId(), running.getVersion());

        assertEquals("READY", persisted.getPreparationStatus());
        assertEquals(2, persisted.getVersion());
        assertEquals(1, async.completions);
    }

    @Test
    void asynchronousPreparationBecomesStaleWhenTargetChanges() {
        AsyncHandler async = new AsyncHandler();
        async.completedTargetVersions = "{\"record:1\":\"v2\"}";
        service = new ActionProposalServiceImpl(mapper, List.of(async));
        var request = new ActionPrepareDTO("test.async", json("{}"), "async-stale", null, null);
        var running = service.prepare(owner, request);
        when(mapper.lockOwned(owner, running.getId())).thenReturn(persisted);

        service.completePreparation(owner, running.getId(), running.getVersion());

        assertEquals("STALE", persisted.getState());
        assertEquals("FAILED", persisted.getPreparationStatus());
        assertEquals("TARGET_VERSION_CHANGED", persisted.getErrorCode());
        assertEquals(2, persisted.getVersion());
        assertEquals(1, async.completions);
    }

    @Test
    void cancelledPreparationDiscardsLateCompletion() {
        AsyncHandler async = new AsyncHandler();
        service = new ActionProposalServiceImpl(mapper, List.of(async));
        var request = new ActionPrepareDTO("test.async", json("{}"), "async-cancel", null, null);
        var running = service.prepare(owner, request);
        when(mapper.lockOwned(owner, running.getId())).thenReturn(persisted);
        service.cancel(owner, running.getId(), running.getVersion());

        service.completePreparation(owner, running.getId(), running.getVersion());

        assertEquals("CANCELLED", persisted.getState());
        assertEquals("RUNNING", persisted.getPreparationStatus());
        assertEquals(2, persisted.getVersion());
        assertEquals(0, async.completions);
    }

    @Test
    void asynchronousResultStaysExecutingUntilMatchingGenerationCompletes() {
        handler.execution = ActionExecution.executing(json("{\"jobIds\":[12],\"todoResolutionsConfirmed\":2,\"todoEffectsPreserved\":true}"));
        var card = service.prepare(owner, request("key"));
        var executing = service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "request"));
        assertEquals("EXECUTING", executing.getState());
        assertEquals(12, service.get(owner, card.getId()).getResult().path("jobIds").get(0).asInt());
        when(mapper.lockForCompletion(card.getId())).thenAnswer(call -> persisted);
        service.completeAsync(card.getId(), executing.getExecutionVersion() - 1,
                json("{\"outcome\":\"APPLIED\"}"));
        assertEquals("EXECUTING", persisted.getState());
        service.completeAsync(card.getId(), executing.getExecutionVersion(),
                json("{\"outcome\":\"SAVED_ONLY_FALLBACK\",\"recordId\":8}"));
        var completed = service.get(owner, card.getId());
        assertEquals("SUCCEEDED", completed.getState());
        assertEquals("已仅保存，未加入记忆", completed.getResult().path("message").asText());
        assertEquals(2, completed.getResult().path("todoResolutionsConfirmed").asInt());
        assertTrue(completed.getResult().path("todoEffectsPreserved").asBoolean());
        service.completeAsync(card.getId(), executing.getExecutionVersion(), json("{\"outcome\":\"APPLIED\"}"));
        assertEquals("SAVED_ONLY_FALLBACK", service.get(owner, card.getId()).getResult().path("outcome").asText());
        assertEquals(1, handler.executions);
    }

    @Test
    void transactionFailureDoesNotReportSuccess() {
        var card = service.prepare(owner, request("key"));
        when(mapper.save(any(), anyInt())).thenThrow(new IllegalStateException("SQL failed"));
        assertThrows(IllegalStateException.class,
                () -> service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "request")));
        assertEquals(0, handler.executions);
        // Real rollback/commit behavior is covered separately against PostgreSQL, never inferred here.
    }

    @Test
    void handlerRegistryRejectsDuplicateKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionProposalServiceImpl(mapper, List.of(handler, new RecordingHandler())));
    }

    @Test
    void sourceOwnershipIsCheckedBeforePreparing() {
        var request = request("key");
        request.setSourceSessionId(UUID.randomUUID());
        assertEquals(4041, assertThrows(BusinessException.class, () -> service.prepare(owner, request)).getCode());
        verify(mapper, never()).insertIfAbsent(any());
        assertThrows(BusinessException.class, () -> service.listForSession(owner, request.getSourceSessionId()));
    }

    @Test
    void sourceMessageMustBeOwnedUserMessageInSelectedSession() {
        var request = request("key");
        request.setSourceSessionId(UUID.randomUUID());
        request.setSourceMessageId(9L);
        when(mapper.sessionOwned(owner, request.getSourceSessionId())).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.prepare(owner, request));
        verify(mapper).sourceMessageOwned(owner, request.getSourceSessionId(), 9L);
        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void resultsRequireActualOutcomesAndDurableAsyncReferences() {
        assertThrows(IllegalArgumentException.class, () -> new ActionExecution("SUCCEEDED", json("{}"), null));
        assertThrows(IllegalArgumentException.class, () -> ActionExecution.executing(json("{}")));
        assertThrows(IllegalArgumentException.class, () -> ActionExecution.executing(json("{\"jobIds\":[]}")));
        assertEquals("SAVED_ONLY_FALLBACK", ActionExecution.savedOnlyFallback(json("{\"recordId\":7}"))
                .result().path("outcome").asText());
    }

    @Test
    void asynchronousFailureIsTerminalAndCannotBeConfirmedAgain() {
        handler.execution = ActionExecution.executing(json("{\"jobIds\":[12]}"));
        var card = service.prepare(owner, request("key"));
        var executing = service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "request"));
        when(mapper.lockForCompletion(card.getId())).thenAnswer(call -> persisted);
        service.completeAsync(card.getId(), executing.getExecutionVersion(), json("{\"state\":\"FAILED\",\"errorCode\":\"JOB_FAILED\"}"));
        assertEquals("FAILED", service.confirm(owner, card.getId(), new ActionConfirmDTO(1, "retry")).getState());
        assertEquals(1, handler.executions);
    }

    @Test
    void returnedCardCannotMutateStoredPayload() {
        var card = service.prepare(owner, request("key"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) card.getPayload()).put("title", "tampered");
        assertEquals("draft", service.get(owner, card.getId()).getPayload().path("title").asText());
    }

    private ActionProposal owned(UUID userId, UUID actionId) {
        return persisted != null && persisted.getUserId().equals(userId) && persisted.getId().equals(actionId)
                ? persisted : null;
    }

    private ActionPrepareDTO request(String key) {
        return new ActionPrepareDTO("test.update", json("{\"title\":\"draft\"}"), key, null, null);
    }

    private static JsonNode json(String text) {
        try { return JSON.readTree(text); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static class RecordingHandler implements ActionHandler {
        int executions;
        int targetVersion = 1;
        ActionExecution execution = ActionExecution.applied(json("{\"id\":7}"));

        @Override public Set<String> kinds() { return Set.of("test.update"); }
        @Override public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
            return json("{\"targetVersions\":{\"todo:7\":" + targetVersion + "},\"after\":" + payload + "}");
        }
        @Override public ActionExecution execute(AuthorizedAction action) {
            executions++;
            assertNotNull(action.getActionId());
            assertEquals("test.update", action.getKind());
            return execution;
        }
    }

    private static class AsyncHandler implements ActionHandler {
        int completions;
        String completedTargetVersions = "{}";
        @Override public Set<String> kinds() { return Set.of("test.async"); }
        @Override public boolean asynchronousPreparation(UUID userId, String kind, JsonNode payload) { return true; }
        @Override public JsonNode initialPreparationPreview(UUID userId, String kind, JsonNode payload) {
            return json("{\"preparationStatus\":\"RUNNING\",\"targetVersions\":{}}");
        }
        @Override public JsonNode completePreparationPreview(UUID userId, String kind, JsonNode payload) {
            completions++;
            return json("{\"preparationStatus\":\"READY\",\"targetVersions\":" + completedTargetVersions + "}");
        }
        @Override public JsonNode validateAndPreview(UUID userId, String kind, JsonNode payload) {
            return completePreparationPreview(userId, kind, payload);
        }
        @Override public ActionExecution execute(AuthorizedAction action) { return ActionExecution.applied(json("{}")); }
    }
}
