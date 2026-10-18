-- Persistent assistant proposals only. No business data backfill and no model calls.
-- UNIQUE(user_id, prepare_key) survives cancellation; never delete a key to retry a model call.
CREATE TABLE IF NOT EXISTS assistant_actions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    kind VARCHAR(100) NOT NULL,
    prepare_key VARCHAR(240) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'STALE')),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    preparation_status VARCHAR(24) NOT NULL DEFAULT 'READY'
        CHECK (preparation_status IN ('RUNNING', 'READY', 'FAILED')),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    preview JSONB NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
    target_versions JSONB NOT NULL CHECK (jsonb_typeof(target_versions) = 'object'),
    source_session_id UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    source_message_id BIGINT REFERENCES conversation_history(id) ON DELETE SET NULL,
    request_id VARCHAR(240),
    execution_version INTEGER,
    result JSONB,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_assistant_actions_prepare UNIQUE (user_id, prepare_key),
    CONSTRAINT ck_assistant_actions_success CHECK
        (state <> 'SUCCEEDED' OR (result IS NOT NULL AND
            result->>'outcome' IS NOT NULL AND result->>'outcome' IN ('APPLIED', 'SAVED_ONLY_FALLBACK'))),
    CONSTRAINT ck_assistant_actions_execution CHECK
        (state NOT IN ('EXECUTING', 'SUCCEEDED', 'FAILED') OR
            (execution_version IS NOT NULL AND execution_version > 0 AND request_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_assistant_actions_session
    ON assistant_actions(user_id, source_session_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_assistant_actions_state
    ON assistant_actions(user_id, state, updated_at);
CREATE INDEX IF NOT EXISTS idx_assistant_actions_executing
    ON assistant_actions(updated_at, id) WHERE state = 'EXECUTING';

COMMENT ON TABLE assistant_actions IS
    'AI business proposal: only authenticated explicit confirmation may submit; cancelled preparation keys are retained';

-- Confirmation transaction boundary (Spring @Transactional, PostgreSQL READ COMMITTED):
-- 1. SELECT ... WHERE id=:id AND user_id=:authenticatedOwner FOR UPDATE.
-- 2. Check proposal version/READY; handler locks targets in stable order and supplies current versions.
-- 3. Mismatch persists STALE and returns code 409 plus the current card (no domain write).
-- 4. Transition EXECUTING, execute domain SQL/enqueue durable jobs, persist actual result, COMMIT together.
--    A thrown handler/SQL/commit error rolls back all effects; the caller must GET before retrying.
--    Handler adapters must join this transaction; no external effects before commit / REQUIRES_NEW.
-- 5. Duplicate confirms lock the same card and return its durable state, regardless of request_id.
--    request_id is audit metadata, NOT the once-only execution gate.
-- Async recovery query (worker internal, never model-facing):
-- SELECT id, kind, user_id, execution_version, result FROM assistant_actions
-- WHERE state='EXECUTING' ORDER BY updated_at, id;
-- result.jobIds contains handler-specific durable job IDs. Reconcile existing job status, do not execute again.
-- completeAsync(id, execution_version, terminalResult) locks the card and only changes a matching
-- EXECUTING generation. Duplicate/late callbacks cannot overwrite terminal or cancelled state.
-- terminalResult includes state=SUCCEEDED|FAILED (SUCCEEDED default), actual object IDs/effects,
-- outcome=APPLIED|SAVED_ONLY_FALLBACK for success, errorCode for failure.
-- Validate migration, concurrent confirmation, and rollback in an isolated PostgreSQL test DB;
-- Mockito tests alone do not prove the transaction or concurrency contract.
