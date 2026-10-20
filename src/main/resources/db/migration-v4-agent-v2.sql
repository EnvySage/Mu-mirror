-- Read-only data boundary for the Python graph_v2 engine.
-- Requires migration-v3-record-ai-authorization.sql to have been applied.
-- The migration is staged with Tasks 2 and 6: controlled views first, then the
-- Java-owned chat run lifecycle below. Apply once after migration-v3.

CREATE SCHEMA IF NOT EXISTS mirror_ai;

CREATE OR REPLACE VIEW mirror_ai.record_chunks WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE r.source = 'user'
  AND r.status = 'done'
  AND r.deleted_at IS NULL
  AND r.ai_processing_authorized = TRUE
  AND c.ai_memory_approved = TRUE
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> '';

CREATE OR REPLACE VIEW mirror_ai.glossary_terms WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    term,
    aliases,
    description,
    query_hit_count,
    last_confirmed_at
FROM user_terms
WHERE status = 'confirmed';

CREATE OR REPLACE VIEW mirror_ai.chat_history WITH (security_barrier = true) AS
SELECT
    h.id,
    h.user_id,
    h.session_id,
    h.role,
    h.content,
    h.is_fallback,
    h.created_at
FROM conversation_history AS h
JOIN chat_sessions AS s
  ON s.id = h.session_id
 AND s.user_id = h.user_id;

CREATE OR REPLACE VIEW mirror_ai.profiles WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    snapshot_type,
    period_month,
    mood_analysis,
    learning_analysis,
    todo_analysis,
    rhythm_analysis,
    user_tags,
    overall_summary,
    embedding,
    created_at
FROM profile_snapshots
WHERE invalidated = FALSE;

CREATE OR REPLACE VIEW mirror_ai.vault_metadata WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    original_name,
    mime,
    size_bytes,
    category,
    description,
    digest_status,
    created_at
FROM vault_items
WHERE deleted_at IS NULL;

-- Only the selected segment is exposed. In the current Java pipeline,
-- vault chunks store this same segment in chunks.content; keeping the view on
-- the fragment column avoids exposing diary record content through this API.
CREATE OR REPLACE VIEW mirror_ai.vault_content WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.vault_item_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN vault_items AS v
  ON v.id = c.vault_item_id
 AND v.user_id = c.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE v.digest_status = 'confirmed'
  AND v.deleted_at IS NULL
  AND r.source = 'vault'
  AND r.deleted_at IS NULL
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> ''
  AND COALESCE(c.metadata->>'keyChunk', 'false') <> 'true';

CREATE OR REPLACE VIEW mirror_ai.vault_keys WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.vault_item_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN vault_items AS v
  ON v.id = c.vault_item_id
 AND v.user_id = c.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE v.digest_status = 'confirmed'
  AND v.deleted_at IS NULL
  AND r.source = 'vault'
  AND r.deleted_at IS NULL
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> ''
  AND c.metadata->>'keyChunk' = 'true';

CREATE OR REPLACE VIEW mirror_ai.todos WITH (security_barrier = true) AS
SELECT
    t.id,
    t.user_id,
    t.title,
    t.current_status,
    t.source_chunk_id,
    c.record_id,
    r.created_at
FROM todo_registry AS t
JOIN chunks AS c
  ON c.id = t.source_chunk_id
 AND c.user_id = t.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE t.deleted_at IS NULL
  AND r.source = 'user'
  AND r.status = 'done'
  AND r.deleted_at IS NULL
  AND c.todo_approved = TRUE;

COMMENT ON SCHEMA mirror_ai IS
    'Fixed, read-only projections for the graph_v2 Python service';
COMMENT ON VIEW mirror_ai.record_chunks IS
    'Only user-owned, completed, AI-authorized records and separately approved non-empty chunks';
COMMENT ON VIEW mirror_ai.vault_content IS
    'Confirmed live vault body segments for explicit file reads; key chunks are excluded';
COMMENT ON VIEW mirror_ai.vault_keys IS
    'Confirmed live vault key segments for file discovery';
COMMENT ON VIEW mirror_ai.todos IS
    'Todos with independent todo_approved authorization; memory authorization is not required';

-- Java-owned request/run lifecycle. session_id deliberately has no FK so deleting
-- a chat can retain the short-lived idempotency tombstone without restoring text.
CREATE TABLE IF NOT EXISTS agent_runs (
    run_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    session_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    engine VARCHAR(16) NOT NULL CHECK (engine IN ('legacy', 'graph_v2')),
    mode VARCHAR(12) NOT NULL DEFAULT 'live' CHECK (mode IN ('live', 'shadow')),
    parent_run_id UUID REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    question_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'running', 'cancel_requested', 'completed', 'failed', 'cancelled', 'abandoned'
    )),
    outcome VARCHAR(32),
    user_message_id BIGINT REFERENCES conversation_history(id) ON DELETE SET NULL,
    assistant_message_id BIGINT REFERENCES conversation_history(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    stop_reason VARCHAR(64),
    error_code VARCHAR(64),
    plan_rounds INTEGER NOT NULL DEFAULT 0 CHECK (plan_rounds >= 0),
    tool_call_count INTEGER NOT NULL DEFAULT 0 CHECK (tool_call_count >= 0),
    last_event_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0),
    glossary_hits_applied BOOLEAN NOT NULL DEFAULT FALSE,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_runs_user_request
    ON agent_runs(user_id, client_request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_runs_active_live_session
    ON agent_runs(session_id)
    WHERE mode = 'live' AND status IN ('running', 'cancel_requested');
CREATE INDEX IF NOT EXISTS idx_agent_runs_lease
    ON agent_runs(lease_expires_at)
    WHERE status IN ('running', 'cancel_requested');
CREATE INDEX IF NOT EXISTS idx_agent_runs_retention
    ON agent_runs(finished_at)
    WHERE finished_at IS NOT NULL;

ALTER TABLE conversation_history
    ADD COLUMN IF NOT EXISTS run_id UUID REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS created_records JSONB NOT NULL DEFAULT '[]'::jsonb;
CREATE UNIQUE INDEX IF NOT EXISTS uq_history_run_role
    ON conversation_history(run_id, role)
    WHERE run_id IS NOT NULL;

ALTER TABLE tool_calls
    ADD COLUMN IF NOT EXISTS run_id UUID REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS call_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS engine VARCHAR(16),
    ADD COLUMN IF NOT EXISTS code VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tool_calls_run_call
    ON tool_calls(run_id, call_id)
    WHERE run_id IS NOT NULL AND call_id IS NOT NULL;

-- Successful create_record receipts survive record deletion and are retained with the run.
CREATE TABLE IF NOT EXISTS agent_command_receipts (
    user_id UUID NOT NULL REFERENCES users(id),
    call_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES agent_runs(run_id),
    command_name VARCHAR(32) NOT NULL CHECK (command_name = 'create_record'),
    request_hash CHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, call_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_command_receipts_run
    ON agent_command_receipts(run_id, created_at);
