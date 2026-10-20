package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.tools.AuditService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AgentEventAdapterTest {

    private final UUID runId = UUID.randomUUID();
    private final AuditService auditService = mock(AuditService.class);
    private AgentEventAdapter adapter;

    @BeforeEach
    void setUp() {
        AgentRun run = AgentRun.builder().runId(runId).userId(UUID.randomUUID())
                .sessionId(UUID.randomUUID()).engine("graph_v2").mode("live").status("running").build();
        ChatInvocation invocation = new ChatInvocation(run.getUserId(), run.getSessionId(), runId,
                UUID.randomUUID(), 15L, "question", "graph_v2");
        adapter = new AgentEventAdapter(run, invocation, null, auditService, new ObjectMapper(), false);
    }

    @Test
    void rejectsWrongRunIdAndRepeatedOrOutOfOrderSequences() {
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(delta(UUID.randomUUID(), 1, "x")));
        adapter.accept(delta(runId, 1, "x"));
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(delta(runId, 1, "y")));
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(delta(runId, 3, "z")));
    }

    @Test
    void missingTerminalIsAFailedPartialResult() {
        adapter.accept(delta(runId, 1, "partial"));

        ChatFinalResult result = adapter.finishTransport();

        assertEquals("failed", result.status());
        assertEquals("partial", result.answerText());
        assertEquals("MISSING_TERMINAL", result.errorCode());
        assertEquals("partial_stream", result.stopReason());
    }

    @Test
    void requiresExactlyOneTerminalAndMatchingFinalText() {
        adapter.accept(delta(runId, 1, "answer"));
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(completed(runId, 2, "different")));
        assertEquals("AGENT_PROTOCOL_ERROR", adapter.protocolFailure("AGENT_PROTOCOL_ERROR").errorCode());
    }

    @Test
    void rejectsSecondTerminalEvent() {
        adapter.accept(completed(runId, 1, ""));

        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(completed(runId, 2, "")));
    }

    @Test
    void rejectsSkippedSourceNumbers() {
        AgentProto.AgentCompleted completed = AgentProto.AgentCompleted.newBuilder()
                .setAnswerText("")
                .addSources(MirrorChatProto.Source.newBuilder().setN(2).setRecordId(44).setQuote("q").setDate("2026-10-20"))
                .build();
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                        .setSeq(1).setCompleted(completed).build()));
    }

    @Test
    void auditsAReadToolOnceAndRejectsDuplicateCallId() {
        AgentProto.AgentToolTrace trace = AgentProto.AgentToolTrace.newBuilder()
                .setCallId("call-1").setTool("search_records").setArgsJson("{}")
                .setSummary("one result").setSuccess(true).build();
        AgentProto.AgentEvent first = AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                .setSeq(1).setToolFinished(trace).build();
        AgentProto.AgentEvent duplicate = first.toBuilder().setSeq(2).build();

        adapter.accept(first);
        assertThrows(AgentEventAdapter.AgentProtocolException.class, () -> adapter.accept(duplicate));

        verify(auditService).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.eq("call-1"),
                org.mockito.ArgumentMatchers.eq("graph_v2"), org.mockito.ArgumentMatchers.eq("search_records"),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq("one result"),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(""));
        verifyNoMoreInteractions(auditService);
    }

    @Test
    void shadowToolEventsAreNeverWrittenToTheFormalAuditService() {
        AgentRun run = AgentRun.builder().runId(runId).userId(UUID.randomUUID())
                .sessionId(UUID.randomUUID()).engine("graph_v2").mode("shadow").status("running").build();
        ChatInvocation invocation = new ChatInvocation(run.getUserId(), run.getSessionId(), runId,
                UUID.randomUUID(), 15L, "question", "graph_v2");
        AgentEventAdapter shadow = new AgentEventAdapter(run, invocation, null, auditService,
                new ObjectMapper(), true);
        AgentProto.AgentToolTrace trace = AgentProto.AgentToolTrace.newBuilder()
                .setCallId("shadow-call").setTool("search_records").setArgsJson("{\"query\":\"x\"}")
                .setSummary("one result").setSuccess(true).build();

        shadow.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                .setSeq(1).setToolFinished(trace).build());
        shadow.accept(delta(runId, 2, "answer"));
        shadow.accept(completed(runId, 3, "answer"));

        assertEquals("completed", shadow.result().status());
        verifyNoMoreInteractions(auditService);
    }

    private static AgentProto.AgentEvent delta(UUID runId, long sequence, String text) {
        return AgentProto.AgentEvent.newBuilder().setRunId(runId.toString()).setSeq(sequence)
                .setDelta(AgentProto.AgentText.newBuilder().setContent(text)).build();
    }

    private static AgentProto.AgentEvent completed(UUID runId, long sequence, String text) {
        return AgentProto.AgentEvent.newBuilder().setRunId(runId.toString()).setSeq(sequence)
                .setCompleted(AgentProto.AgentCompleted.newBuilder().setAnswerText(text)).build();
    }
}
