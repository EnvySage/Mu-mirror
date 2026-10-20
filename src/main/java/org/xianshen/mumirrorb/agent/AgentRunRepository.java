package org.xianshen.mumirrorb.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Explicit SQL boundary for run state and lease transitions. */
@Repository
@RequiredArgsConstructor
public class AgentRunRepository {

    private final JdbcTemplate jdbc;
    private final AgentRunMapper mapper;

    public AgentRun findByRequest(UUID userId, UUID requestId) {
        return mapper.findByClientRequestId(userId, requestId);
    }

    public AgentRun findById(UUID runId) {
        return mapper.findByRunId(runId);
    }

    public AgentRun lockRun(UUID runId) {
        return jdbc.query("""
                SELECT run_id, user_id, session_id, client_request_id, request_hash, engine, mode,
                       parent_run_id, question_hash, status, outcome, user_message_id, assistant_message_id,
                       started_at, finished_at, lease_expires_at, stop_reason, error_code,
                       plan_rounds, tool_call_count, last_event_seq, glossary_hits_applied, metrics::text AS metrics_json
                FROM agent_runs WHERE run_id = ? FOR UPDATE
                """, result -> {
            if (!result.next()) return null;
            return AgentRun.builder()
                    .runId(result.getObject("run_id", UUID.class))
                    .userId(result.getObject("user_id", UUID.class))
                    .sessionId(result.getObject("session_id", UUID.class))
                    .clientRequestId(result.getObject("client_request_id", UUID.class))
                    .requestHash(result.getString("request_hash"))
                    .engine(result.getString("engine")).mode(result.getString("mode"))
                    .parentRunId(result.getObject("parent_run_id", UUID.class))
                    .questionHash(result.getString("question_hash")).status(result.getString("status"))
                    .outcome(result.getString("outcome"))
                    .userMessageId((Long) result.getObject("user_message_id"))
                    .assistantMessageId((Long) result.getObject("assistant_message_id"))
                    .startedAt(result.getObject("started_at", OffsetDateTime.class))
                    .finishedAt(result.getObject("finished_at", OffsetDateTime.class))
                    .leaseExpiresAt(result.getObject("lease_expires_at", OffsetDateTime.class))
                    .stopReason(result.getString("stop_reason")).errorCode(result.getString("error_code"))
                    .planRounds((Integer) result.getObject("plan_rounds"))
                    .toolCallCount((Integer) result.getObject("tool_call_count"))
                    .lastEventSeq((Long) result.getObject("last_event_seq"))
                    .glossaryHitsApplied(result.getBoolean("glossary_hits_applied"))
                    .metricsJson(result.getString("metrics_json")).build();
        }, runId);
    }

    public AgentRun findActiveBySession(UUID sessionId) {
        return mapper.findActiveBySession(sessionId);
    }

    public void insert(AgentRun run) {
        jdbc.update("""
                INSERT INTO agent_runs (
                    run_id, user_id, session_id, client_request_id, request_hash,
                    engine, mode, parent_run_id, question_hash, status, outcome,
                    user_message_id, started_at, lease_expires_at, metrics
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                run.getRunId(), run.getUserId(), run.getSessionId(), run.getClientRequestId(),
                run.getRequestHash(), run.getEngine(), run.getMode(), run.getParentRunId(),
                run.getQuestionHash(), run.getStatus(), run.getOutcome(), run.getUserMessageId(),
                run.getStartedAt(), run.getLeaseExpiresAt(),
                run.getMetricsJson() == null ? "{}" : run.getMetricsJson());
    }

    public boolean hasActiveForegroundGraph() {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM agent_runs
                    WHERE engine = 'graph_v2' AND mode = 'live'
                      AND status IN ('running', 'cancel_requested')
                )
                """, Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    public boolean requestShadowCancel(UUID runId, String reason) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'cancel_requested', stop_reason = ?
                WHERE run_id = ? AND mode = 'shadow' AND status = 'running'
                """, reason, runId) == 1;
    }

    public boolean completeShadow(AgentRun run, String terminalStatus, String outcome, String stopReason,
                                  String errorCode, int planRounds, int toolCallCount,
                                  long lastEventSeq, String metricsJson) {
        return jdbc.update("""
                UPDATE agent_runs
                SET status = CASE WHEN status = 'cancel_requested' THEN 'cancelled' ELSE ? END,
                    outcome = ?, stop_reason = COALESCE(stop_reason, ?), error_code = ?,
                    plan_rounds = ?, tool_call_count = ?, last_event_seq = GREATEST(last_event_seq, ?),
                    finished_at = NOW(), lease_expires_at = NOW(), metrics = ?::jsonb
                WHERE run_id = ? AND mode = 'shadow' AND status IN ('running', 'cancel_requested')
                """, terminalStatus, outcome, stopReason, errorCode, Math.max(0, planRounds),
                Math.max(0, toolCallCount), Math.max(0, lastEventSeq),
                metricsJson == null ? "{}" : metricsJson, run.getRunId()) == 1;
    }

    public boolean complete(AgentRun run, String terminalStatus, String outcome, String stopReason, String errorCode,
                            int planRounds, int toolCallCount, long lastEventSeq,
                            long assistantMessageId, String metricsJson) {
        return jdbc.update("""
                UPDATE agent_runs
                SET status = ?, outcome = ?, stop_reason = ?, error_code = ?,
                    plan_rounds = ?, tool_call_count = ?, last_event_seq = GREATEST(last_event_seq, ?),
                    assistant_message_id = ?, finished_at = NOW(), lease_expires_at = NOW(),
                    metrics = ?::jsonb
                WHERE run_id = ? AND user_id = ? AND status IN ('running', 'cancel_requested')
                  AND lease_expires_at > NOW()
                """,
                terminalStatus, outcome, stopReason, errorCode,
                Math.max(0, planRounds), Math.max(0, toolCallCount), Math.max(0, lastEventSeq),
                assistantMessageId, metricsJson == null ? "{}" : metricsJson,
                run.getRunId(), run.getUserId()) == 1;
    }

    public boolean recordEventSequence(UUID runId, long sequence) {
        return jdbc.update("""
                UPDATE agent_runs SET last_event_seq = ?
                WHERE run_id = ? AND status = 'running' AND last_event_seq < ?
                """, sequence, runId, sequence) == 1;
    }

    public void linkUserMessage(UUID runId, long userMessageId) {
        jdbc.update("UPDATE agent_runs SET user_message_id = ? WHERE run_id = ?",
                userMessageId, runId);
    }

    public boolean markAbandoned(UUID runId) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'abandoned', outcome = COALESCE(outcome, 'answer'),
                    stop_reason = 'lease_lost',
                    finished_at = NOW(), lease_expires_at = NOW()
                WHERE run_id = ? AND status IN ('running', 'cancel_requested')
                """, runId) == 1;
    }

    public boolean requestCancel(UUID runId, UUID userId) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'cancel_requested'
                WHERE run_id = ? AND user_id = ? AND status = 'running'
                """, runId, userId) == 1;
    }

    public boolean requestCancelForSession(UUID sessionId, UUID userId) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'cancel_requested'
                WHERE session_id = ? AND user_id = ? AND mode = 'live' AND status = 'running'
                """, sessionId, userId) == 1;
    }

    public boolean markCancelled(UUID runId, String reason) {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'cancelled', stop_reason = ?,
                    finished_at = NOW(), lease_expires_at = NOW()
                WHERE run_id = ? AND status = 'cancel_requested'
                """, reason, runId) == 1;
    }

    public boolean renewLease(UUID runId, int leaseSeconds) {
        return jdbc.update("""
                UPDATE agent_runs SET lease_expires_at = NOW() + make_interval(secs => ?)
                WHERE run_id = ? AND status = 'running'
                  AND lease_expires_at > NOW()
                """, Math.max(1, leaseSeconds), runId) == 1;
    }

    public int abandonExpired() {
        return jdbc.update("""
                UPDATE agent_runs SET status = 'abandoned', outcome = COALESCE(outcome, 'answer'),
                    stop_reason = COALESCE(stop_reason, 'lease_expired'),
                    finished_at = NOW(), lease_expires_at = NOW()
                WHERE status IN ('running', 'cancel_requested') AND lease_expires_at <= NOW()
                """);
    }

    @Transactional
    public int deleteExpired(OffsetDateTime cutoff) {
        jdbc.update("""
                DELETE FROM agent_command_receipts receipt
                USING agent_runs run
                WHERE receipt.run_id = run.run_id AND run.finished_at < ?
                """, cutoff);
        return jdbc.update("DELETE FROM agent_runs WHERE finished_at < ?", cutoff);
    }

    public int applyGlossaryHitsOnce(UUID userId, UUID runId) {
        return jdbc.update("""
                UPDATE agent_runs SET glossary_hits_applied = TRUE
                WHERE run_id = ? AND user_id = ? AND engine = 'graph_v2' AND mode = 'live'
                  AND status = 'running' AND glossary_hits_applied = FALSE
                  AND lease_expires_at > NOW()
                """, runId, userId);
    }
}
