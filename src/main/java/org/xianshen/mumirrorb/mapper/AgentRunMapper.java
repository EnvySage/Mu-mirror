package org.xianshen.mumirrorb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xianshen.mumirrorb.pojo.DO.AgentRun;

import java.util.UUID;

/** Queries used by the run repository; writes use explicit SQL to cast JSONB safely. */
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
}
