package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Agent-run lifecycle SQL; explicit casts keep JSONB writes safe. */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   client_request_id AS clientRequestId, request_hash AS requestHash,
                   engine, mode, parent_run_id AS parentRunId, question_hash AS questionHash,
                   status, outcome, user_message_id AS userMessageId,
                   assistant_message_id AS assistantMessageId, started_at AS startedAt,
                   finished_at AS finishedAt, lease_expires_at AS leaseExpiresAt,
                   stop_reason AS stopReason, error_code AS errorCode,
                   plan_rounds AS planRounds, tool_call_count AS toolCallCount,
                   last_event_seq AS lastEventSeq, glossary_hits_applied AS glossaryHitsApplied,
                   metrics::text AS metricsJson
            FROM agent_runs WHERE user_id = #{userId} AND client_request_id = #{requestId}
            """)
    AgentRun findByClientRequestId(@Param("userId") UUID userId,
                                   @Param("requestId") UUID requestId);

    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   client_request_id AS clientRequestId, request_hash AS requestHash,
                   engine, mode, parent_run_id AS parentRunId, question_hash AS questionHash,
                   status, outcome, user_message_id AS userMessageId,
                   assistant_message_id AS assistantMessageId, started_at AS startedAt,
                   finished_at AS finishedAt, lease_expires_at AS leaseExpiresAt,
                   stop_reason AS stopReason, error_code AS errorCode,
                   plan_rounds AS planRounds, tool_call_count AS toolCallCount,
                   last_event_seq AS lastEventSeq, glossary_hits_applied AS glossaryHitsApplied,
                   metrics::text AS metricsJson
            FROM agent_runs WHERE run_id = #{runId}
            """)
    AgentRun findByRunId(@Param("runId") UUID runId);

    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   client_request_id AS clientRequestId, request_hash AS requestHash,
                   engine, mode, parent_run_id AS parentRunId, question_hash AS questionHash,
                   status, outcome, user_message_id AS userMessageId,
                   assistant_message_id AS assistantMessageId, started_at AS startedAt,
                   finished_at AS finishedAt, lease_expires_at AS leaseExpiresAt,
                   stop_reason AS stopReason, error_code AS errorCode,
                   plan_rounds AS planRounds, tool_call_count AS toolCallCount,
                   last_event_seq AS lastEventSeq, glossary_hits_applied AS glossaryHitsApplied,
                   metrics::text AS metricsJson
            FROM agent_runs WHERE run_id = #{runId} FOR UPDATE
            """)
    AgentRun lockByRunId(@Param("runId") UUID runId);

    @Select("""
            SELECT run_id AS runId, user_id AS userId, session_id AS sessionId,
                   client_request_id AS clientRequestId, request_hash AS requestHash,
                   engine, mode, parent_run_id AS parentRunId, question_hash AS questionHash,
                   status, outcome, user_message_id AS userMessageId,
                   assistant_message_id AS assistantMessageId, started_at AS startedAt,
                   finished_at AS finishedAt, lease_expires_at AS leaseExpiresAt,
                   stop_reason AS stopReason, error_code AS errorCode,
                   plan_rounds AS planRounds, tool_call_count AS toolCallCount,
                   last_event_seq AS lastEventSeq, glossary_hits_applied AS glossaryHitsApplied,
                   metrics::text AS metricsJson
            FROM agent_runs WHERE session_id = #{sessionId} AND mode = 'live'
              AND status IN ('running', 'cancel_requested')
            ORDER BY started_at DESC LIMIT 1
            """)
    AgentRun findActiveBySession(@Param("sessionId") UUID sessionId);

    @Insert("""
            INSERT INTO agent_runs (
                run_id, user_id, session_id, client_request_id, request_hash,
                engine, mode, parent_run_id, question_hash, status, outcome,
                user_message_id, started_at, lease_expires_at, metrics
            ) VALUES (
                #{run.runId}, #{run.userId}, #{run.sessionId}, #{run.clientRequestId}, #{run.requestHash},
                #{run.engine}, #{run.mode}, #{run.parentRunId,jdbcType=OTHER}, #{run.questionHash},
                #{run.status}, #{run.outcome,jdbcType=VARCHAR}, #{run.userMessageId,jdbcType=BIGINT},
                #{run.startedAt}, #{run.leaseExpiresAt}, #{run.metricsJson}::jsonb
            )
            """)
    int insertRun(@Param("run") AgentRun run);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM agent_runs
                WHERE engine = 'graph_v2' AND mode = 'live'
                  AND status IN ('running', 'cancel_requested')
            )
            """)
    Boolean hasActiveForegroundGraph();

    @Update("""
            UPDATE agent_runs SET status = 'cancel_requested', stop_reason = #{reason}
            WHERE run_id = #{runId} AND mode = 'shadow' AND status = 'running'
            """)
    int requestShadowCancel(@Param("runId") UUID runId, @Param("reason") String reason);

    @Update("""
            UPDATE agent_runs
            SET status = CASE WHEN status = 'cancel_requested' THEN 'cancelled' ELSE #{terminalStatus} END,
                outcome = #{outcome,jdbcType=VARCHAR},
                stop_reason = COALESCE(stop_reason, #{stopReason,jdbcType=VARCHAR}),
                error_code = #{errorCode,jdbcType=VARCHAR},
                plan_rounds = #{planRounds}, tool_call_count = #{toolCallCount},
                last_event_seq = GREATEST(last_event_seq, #{lastEventSeq}),
                finished_at = NOW(), lease_expires_at = NOW(), metrics = #{metricsJson}::jsonb
            WHERE run_id = #{runId} AND mode = 'shadow' AND status IN ('running', 'cancel_requested')
            """)
    int completeShadow(@Param("runId") UUID runId,
                       @Param("terminalStatus") String terminalStatus,
                       @Param("outcome") String outcome,
                       @Param("stopReason") String stopReason,
                       @Param("errorCode") String errorCode,
                       @Param("planRounds") int planRounds,
                       @Param("toolCallCount") int toolCallCount,
                       @Param("lastEventSeq") long lastEventSeq,
                       @Param("metricsJson") String metricsJson);

    @Update("""
            UPDATE agent_runs
            SET status = #{terminalStatus}, outcome = #{outcome,jdbcType=VARCHAR},
                stop_reason = #{stopReason,jdbcType=VARCHAR}, error_code = #{errorCode,jdbcType=VARCHAR},
                plan_rounds = #{planRounds}, tool_call_count = #{toolCallCount},
                last_event_seq = GREATEST(last_event_seq, #{lastEventSeq}),
                assistant_message_id = #{assistantMessageId}, finished_at = NOW(),
                lease_expires_at = NOW(), metrics = #{metricsJson}::jsonb
            WHERE run_id = #{runId} AND user_id = #{userId}
              AND status IN ('running', 'cancel_requested')
              AND lease_expires_at > NOW()
            """)
    int complete(@Param("runId") UUID runId,
                 @Param("userId") UUID userId,
                 @Param("terminalStatus") String terminalStatus,
                 @Param("outcome") String outcome,
                 @Param("stopReason") String stopReason,
                 @Param("errorCode") String errorCode,
                 @Param("planRounds") int planRounds,
                 @Param("toolCallCount") int toolCallCount,
                 @Param("lastEventSeq") long lastEventSeq,
                 @Param("assistantMessageId") long assistantMessageId,
                 @Param("metricsJson") String metricsJson);

    @Update("""
            UPDATE agent_runs SET last_event_seq = #{sequence}
            WHERE run_id = #{runId} AND status = 'running' AND last_event_seq < #{sequence}
            """)
    int recordEventSequence(@Param("runId") UUID runId, @Param("sequence") long sequence);

    @Update("UPDATE agent_runs SET user_message_id = #{userMessageId} WHERE run_id = #{runId}")
    int linkUserMessage(@Param("runId") UUID runId, @Param("userMessageId") long userMessageId);

    @Update("""
            UPDATE agent_runs SET status = 'abandoned', outcome = COALESCE(outcome, 'answer'),
                stop_reason = 'lease_lost', finished_at = NOW(), lease_expires_at = NOW()
            WHERE run_id = #{runId} AND status IN ('running', 'cancel_requested')
            """)
    int markAbandoned(@Param("runId") UUID runId);

    @Update("""
            UPDATE agent_runs SET status = 'cancel_requested'
            WHERE run_id = #{runId} AND user_id = #{userId} AND status = 'running'
            """)
    int requestCancel(@Param("runId") UUID runId, @Param("userId") UUID userId);

    @Update("""
            UPDATE agent_runs SET status = 'cancel_requested'
            WHERE session_id = #{sessionId} AND user_id = #{userId}
              AND mode = 'live' AND status = 'running'
            """)
    int requestCancelForSession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

    @Update("""
            UPDATE agent_runs SET status = 'cancelled', stop_reason = #{reason},
                finished_at = NOW(), lease_expires_at = NOW()
            WHERE run_id = #{runId} AND status = 'cancel_requested'
            """)
    int markCancelled(@Param("runId") UUID runId, @Param("reason") String reason);

    @Update("""
            UPDATE agent_runs SET lease_expires_at = NOW() + make_interval(secs => #{leaseSeconds})
            WHERE run_id = #{runId} AND status = 'running' AND lease_expires_at > NOW()
            """)
    int renewLease(@Param("runId") UUID runId, @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE agent_runs SET status = 'abandoned', outcome = COALESCE(outcome, 'answer'),
                stop_reason = COALESCE(stop_reason, 'lease_expired'),
                finished_at = NOW(), lease_expires_at = NOW()
            WHERE status IN ('running', 'cancel_requested') AND lease_expires_at <= NOW()
            """)
    int abandonExpired();

    @Delete("""
            DELETE FROM agent_command_receipts receipt
            USING agent_runs run
            WHERE receipt.run_id = run.run_id AND run.finished_at < #{cutoff}
            """)
    int deleteExpiredCommandReceipts(@Param("cutoff") OffsetDateTime cutoff);

    @Delete("DELETE FROM agent_runs WHERE finished_at < #{cutoff}")
    int deleteExpiredRuns(@Param("cutoff") OffsetDateTime cutoff);

    @Update("""
            UPDATE agent_runs SET glossary_hits_applied = TRUE
            WHERE run_id = #{runId} AND user_id = #{userId} AND engine = 'graph_v2' AND mode = 'live'
              AND status = 'running' AND glossary_hits_applied = FALSE
              AND lease_expires_at > NOW()
            """)
    int applyGlossaryHitsOnce(@Param("userId") UUID userId, @Param("runId") UUID runId);
}
