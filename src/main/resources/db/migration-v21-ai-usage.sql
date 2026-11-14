-- V21: durable, owner-bound usage receipts; no model content or credentials.
CREATE TABLE IF NOT EXISTS ai_usage_tasks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scene VARCHAR(64) NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT TRUE,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_tasks_owner_updated ON ai_usage_tasks(user_id,updated_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS ai_usage_invocations (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scene VARCHAR(64) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    model VARCHAR(256) NOT NULL,
    profile_id UUID,
    channel_key VARCHAR(64) NOT NULL,
    channel_name VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','success','error','cancelled')),
    coverage VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK(coverage IN ('pending','complete','incomplete')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY(task_id,user_id) REFERENCES ai_usage_tasks(id,user_id) ON DELETE CASCADE,
    UNIQUE(id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_invocations_task ON ai_usage_invocations(task_id);
CREATE INDEX IF NOT EXISTS idx_ai_usage_invocations_owner_time ON ai_usage_invocations(user_id,started_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_invocations_pending ON ai_usage_invocations(started_at) WHERE status='pending';
CREATE TABLE IF NOT EXISTS ai_usage_attempts (
    invocation_id UUID NOT NULL,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 99),
    status VARCHAR(16) NOT NULL CHECK(status IN ('success','error','cancelled')),
    provider VARCHAR(128) NOT NULL,
    model VARCHAR(256) NOT NULL,
    input_tokens BIGINT CHECK(input_tokens>=0),
    output_tokens BIGINT CHECK(output_tokens>=0),
    total_tokens BIGINT CHECK(total_tokens>=0),
    credits NUMERIC(38,12) CHECK(credits>=0),
    credit_unit VARCHAR(32),
    usage_state VARCHAR(16) NOT NULL CHECK(usage_state IN ('complete','partial','unknown')),
    latency_ms BIGINT CHECK(latency_ms>=0),
    http_status INTEGER CHECK(http_status BETWEEN 100 AND 599),
    cache_read_tokens BIGINT CHECK(cache_read_tokens>=0),
    cache_creation_tokens BIGINT CHECK(cache_creation_tokens>=0),
    reasoning_tokens BIGINT CHECK(reasoning_tokens>=0),
    PRIMARY KEY(invocation_id,id),
    FOREIGN KEY(invocation_id,user_id) REFERENCES ai_usage_invocations(id,user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_attempts_owner ON ai_usage_attempts(user_id);
