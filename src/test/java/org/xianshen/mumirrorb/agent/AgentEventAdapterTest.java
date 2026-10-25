package org.xianshen.mumirrorb.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.grpc.gen.AgentProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;
import org.xianshen.mumirrorb.tools.AuditService;
import org.xianshen.mumirrorb.tools.ToolExecutionResult;
import org.xianshen.mumirrorb.tools.ToolOrchestrator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentEventAdapterTest {

    private final UUID runId = UUID.randomUUID();
    private final AuditService auditService = mock(AuditService.class);
    private final ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
    private AgentEventAdapter adapter;

    @BeforeEach
    void setUp() {
        AgentRun run = AgentRun.builder().runId(runId).userId(UUID.randomUUID())
                .sessionId(UUID.randomUUID()).engine("graph_v2").mode("live").status("running").build();
        ChatInvocation invocation = new ChatInvocation(run.getUserId(), run.getSessionId(), runId,
                UUID.randomUUID(), 15L, "question", "graph_v2");
        adapter = new AgentEventAdapter(run, invocation, null, auditService, toolOrchestrator,
                new ObjectMapper(), false);
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
    void acceptsSkippedSourceNumbersInAnswerOrder() {
        AgentProto.AgentCompleted completed = AgentProto.AgentCompleted.newBuilder()
                .setAnswerText("")
                .addSources(source(6, 44))
                .addSources(source(3, 45))
                .build();

        adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                .setSeq(1).setCompleted(completed).build());

        ChatFinalResult result = adapter.result();
        assertEquals(2, result.sources().size());
        assertEquals(6, result.sources().get(0).get("n"));
        assertEquals(44L, result.sources().get(0).get("record_id"));
        assertEquals(3, result.sources().get(1).get("n"));
    }

    @Test
    void rejectsNonPositiveSourceNumber() {
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                        .setSeq(1).setCompleted(AgentProto.AgentCompleted.newBuilder()
                                .setAnswerText("").addSources(source(0, 44)).build()).build()));
    }

    @Test
    void rejectsDuplicateSourceNumber() {
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                        .setSeq(1).setCompleted(AgentProto.AgentCompleted.newBuilder()
                                .setAnswerText("").addSources(source(1, 44)).addSources(source(1, 45)).build()).build()));
    }

    @Test
    void rejectsSourceWithoutRecordId() {
        assertThrows(AgentEventAdapter.AgentProtocolException.class,
                () -> adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                        .setSeq(1).setCompleted(AgentProto.AgentCompleted.newBuilder()
                                .setAnswerText("").addSources(source(1, 0)).build()).build()));
    }

    private MirrorChatProto.Source source(int n, long recordId) {
        return MirrorChatProto.Source.newBuilder()
                .setN(n).setRecordId(recordId).setQuote("q").setDate("2026-10-20")
                .build();
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
                toolOrchestrator, new ObjectMapper(), true);
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

    @Test
    void preparesOnlyAConfirmationCardAndSuppliesJavaAuthoredReply() throws Exception {
        when(toolOrchestrator.prepareAgentAction(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("prepare_record"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ToolExecutionResult.builder().success(true).summary("已准备记录操作卡")
                        .payload(java.util.Map.of("proposal", java.util.Map.of("id", "proposal-1", "state", "PENDING")))
                        .build());
        AgentProto.AgentPrepareAction action = AgentProto.AgentPrepareAction.newBuilder()
                .setCallId("prepare-call-1").setTool("prepare_record")
                .setArgsJson("{\"kind\":\"record.create\",\"rawContent\":\"今天改 bug 改到头晕。\"}")
                .build();

        adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString())
                .setSeq(1).setPrepareAction(action).build());
        adapter.accept(completed(runId, 2, ""));

        assertEquals("我把内容放进记录卡了，你看一眼；确认后我再保存。", adapter.result().answerText());
        assertEquals("action_prepared", adapter.result().outcome());
        assertEquals(1, adapter.result().toolsUsed().size());
        verify(toolOrchestrator).prepareAgentAction(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("prepare_record"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reusedCancelledCardNeverGetsAPendingConfirmationReply() throws Exception {
        when(toolOrchestrator.prepareAgentAction(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("prepare_record"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ToolExecutionResult.builder().success(true).summary("reused")
                        .payload(java.util.Map.of("proposal", java.util.Map.of("id", "proposal-1", "state", "CANCELLED"))).build());
        var action = AgentProto.AgentPrepareAction.newBuilder().setCallId("prepare-call-cancelled")
                .setTool("prepare_record").setArgsJson("{\"kind\":\"record.create\",\"rawContent\":\"今天修bug\"}");
        adapter.accept(AgentProto.AgentEvent.newBuilder().setRunId(runId.toString()).setSeq(1).setPrepareAction(action).build());
        adapter.accept(completed(runId, 2, ""));
        org.junit.jupiter.api.Assertions.assertTrue(adapter.result().answerText().contains("已取消"));
        org.junit.jupiter.api.Assertions.assertFalse(adapter.result().answerText().contains("确认后"));
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
