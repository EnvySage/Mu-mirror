-- Per-user daily sessions and review-only chat-memory candidates.
-- Legacy sessions intentionally retain NULL day_key/timezone; no history backfill.
-- Existing snapshots remain usable until a forget event conservatively retires unknown chat lineage.
ALTER TABLE profile_snapshots
    ADD COLUMN IF NOT EXISTS chat_lineage_known BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE profile_snapshots ALTER COLUMN chat_lineage_known SET DEFAULT TRUE;
COMMENT ON COLUMN profile_snapshots.chat_lineage_known IS
    'Whether chat-message lineage is complete; migrated snapshots remain unknown until rebuilt';

ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS day_key DATE;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_sessions_user_day
    ON chat_sessions(user_id, day_key) WHERE day_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS chat_day_summaries (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_date DATE NOT NULL,
    source_session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    summary TEXT,
    source_hash CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('PROCESSING','READY','FAILED')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_date)
);
CREATE INDEX IF NOT EXISTS idx_chat_day_summary_session ON chat_day_summaries(source_session_id);

CREATE TABLE IF NOT EXISTS chat_memories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    source_session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    source_message_ids JSONB NOT NULL,
    source_date DATE NOT NULL,
    event_date DATE,
    expires_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL CHECK (state IN ('ACTIVE','FORGOTTEN')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chat_memories_user_state ON chat_memories(user_id, state, expires_at);

-- Tombstones contain provenance IDs only, never copies of forgotten chat content.
CREATE TABLE IF NOT EXISTS chat_source_exclusions (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_kind VARCHAR(24) NOT NULL,
    source_id BIGINT NOT NULL,
    source_version BIGINT,
    source_fingerprint CHAR(64),
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_kind, source_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_source_exclusions_source
    ON chat_source_exclusions(source_kind, source_id);

-- IDs only: assistant turns can depend on older remembered chat sources.
ALTER TABLE conversation_history
    ADD COLUMN IF NOT EXISTS context_source_message_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
