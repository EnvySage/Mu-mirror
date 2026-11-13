-- Apply after v17. Existing records retain the complete-vector compatibility gate.
-- No historical authorization, content, vector or lifecycle state is rewritten.
-- Replay this file atomically (psql --single-transaction -v ON_ERROR_STOP=1 -f ...).
ALTER TABLE records
    ADD COLUMN IF NOT EXISTS index_policy_version SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS embedding_enabled BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'records'::regclass AND conname = 'records_index_policy_version_check'
    ) THEN
        ALTER TABLE records ADD CONSTRAINT records_index_policy_version_check
            CHECK (index_policy_version IN (0, 1));
    END IF;
END $$;

COMMENT ON COLUMN records.index_policy_version IS
    'Server-owned index policy: 0=legacy complete-vector gate, 1=reviewed authorized content readable independently of vectors';
COMMENT ON COLUMN chunks.embedding_enabled IS
    'Whether this chunk is eligible for vector indexing; independent of ai_memory_approved; missing legacy choice defaults true';
COMMENT ON COLUMN records.memory_state IS
    'Record-level AI gate: READY; version 0 additionally requires every approved vector ready, version 1 requires confirmed authorization';

-- Keep record_memory_is_complete unchanged for legacy callers and version 0 jobs.
CREATE OR REPLACE FUNCTION record_ai_is_readable(target_record_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM records r
        WHERE r.id = target_record_id
          AND (
              (r.index_policy_version = 0 AND record_memory_is_complete(r.id))
              OR
              (r.index_policy_version = 1
               AND r.source = 'user' AND r.deleted_at IS NULL AND r.status = 'done'
               AND r.user_reviewed = TRUE AND r.ai_processing_authorized = TRUE
               AND r.memory_state = 'READY'
               AND EXISTS (
                   SELECT 1 FROM chunks c
                   WHERE c.record_id = r.id AND c.user_id = r.user_id
                     AND c.ai_memory_approved = TRUE
                     AND c.segment IS NOT NULL AND btrim(c.segment) <> ''
               ))
          )
    );
$$;

-- Inline the gate so the view-only reader never needs privileges on base tables
-- to execute record_ai_is_readable/record_memory_is_complete. Existing columns
-- keep their original order; CREATE OR REPLACE preserves existing view grants.
CREATE OR REPLACE VIEW mirror_ai.record_chunks WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at,
    c.embedding_enabled,
    c.embedding_status,
    r.index_policy_version
FROM chunks AS c
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE r.source = 'user'
  AND r.status = 'done'
  AND r.deleted_at IS NULL
  AND r.memory_state = 'READY'
  AND (
      (r.index_policy_version = 0 AND NOT EXISTS (
          SELECT 1 FROM chunks AS incomplete
          WHERE incomplete.record_id = r.id
            AND incomplete.user_id = r.user_id
            AND incomplete.ai_memory_approved = TRUE
            AND (incomplete.embedding IS NULL OR incomplete.embedding_status <> 'ready')
      ))
      OR
      (r.index_policy_version = 1
       AND r.user_reviewed = TRUE AND r.ai_processing_authorized = TRUE)
  )
  AND c.ai_memory_approved = TRUE
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> '';
