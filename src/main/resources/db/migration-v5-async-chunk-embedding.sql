-- Durable, retryable embedding work for explicitly approved chunk content.
-- Safe to re-run; existing vectors and authorization flags are preserved.

ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(24) NOT NULL DEFAULT 'not_requested',
    ADD COLUMN IF NOT EXISTS content_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS embedding_error TEXT;

UPDATE chunks
SET embedding_status = 'ready'
WHERE embedding IS NOT NULL
  AND embedding_status = 'not_requested';

CREATE TABLE IF NOT EXISTS chunk_embedding_jobs (
    id BIGSERIAL PRIMARY KEY,
    chunk_id BIGINT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_version BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'processing', 'ready', 'failed', 'cancelled')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_jobs_due
    ON chunk_embedding_jobs(status, available_at, id)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_jobs_expired_lease
    ON chunk_embedding_jobs(lease_until, id)
    WHERE status = 'processing';

CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_embedding_job_active_version
    ON chunk_embedding_jobs(chunk_id, content_version)
    WHERE status IN ('pending', 'processing');

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_jobs_chunk
    ON chunk_embedding_jobs(chunk_id, created_at DESC);

COMMENT ON COLUMN chunks.embedding_status IS
    '当前已授权片段向量状态：not_requested / pending / ready / failed';
COMMENT ON COLUMN chunks.content_version IS
    '片段向量输入版本；正文变化时递增，防止迟到任务写入旧向量';
COMMENT ON COLUMN chunks.embedding_error IS
    '最近一次向量重建失败的可读错误摘要';
COMMENT ON TABLE chunk_embedding_jobs IS
    '持久化 chunk embedding 工作队列；任务正文不冗余保存，处理时从当前 chunk 加载';
