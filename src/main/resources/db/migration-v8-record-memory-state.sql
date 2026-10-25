-- Assistant record preference and the record memory lifecycle.
-- Existing users remain ONLY_SAVE; preference changes apply to future records only.
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS default_record_mode VARCHAR(20) NOT NULL DEFAULT 'ONLY_SAVE';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_settings_default_record_mode_check'
    ) THEN
        ALTER TABLE user_settings
            ADD CONSTRAINT user_settings_default_record_mode_check
            CHECK (default_record_mode IN ('ONLY_SAVE', 'AI_ASSISTED'));
    END IF;
END $$;

ALTER TABLE records
    ADD COLUMN IF NOT EXISTS memory_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS memory_action_id UUID,
    ADD COLUMN IF NOT EXISTS memory_action_execution_version INTEGER,
    ADD COLUMN IF NOT EXISTS memory_failure_reason TEXT,
    ADD COLUMN IF NOT EXISTS correction_of_record_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'records_correction_of_record_id_fkey') THEN
        ALTER TABLE records ADD CONSTRAINT records_correction_of_record_id_fkey
            FOREIGN KEY (correction_of_record_id) REFERENCES records(id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'records_memory_state_check'
    ) THEN
        ALTER TABLE records ADD CONSTRAINT records_memory_state_check
            CHECK (memory_state IN ('NONE', 'PENDING', 'READY', 'DISABLED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_records_memory_state_user
    ON records(user_id, memory_state, id) WHERE deleted_at IS NULL;

-- Conservative metadata-only backfill. READY requires proof that every explicitly
-- approved chunk has a stored vector and is marked ready. No model calls are made.
-- Existing records that cannot be proven complete remain NONE and can be reviewed again.
UPDATE records r
SET memory_state = 'READY'
WHERE r.memory_state = 'NONE'
  AND r.source = 'user'
  AND r.deleted_at IS NULL
  AND r.status = 'done'
  AND r.user_reviewed = TRUE
  AND EXISTS (
      SELECT 1 FROM chunks c
      WHERE c.record_id = r.id AND c.user_id = r.user_id AND c.ai_memory_approved = TRUE
  )
  AND NOT EXISTS (
      SELECT 1 FROM chunks c
      WHERE c.record_id = r.id AND c.user_id = r.user_id AND c.ai_memory_approved = TRUE
        AND (c.embedding IS NULL OR c.embedding_status <> 'ready')
  );

COMMENT ON COLUMN records.memory_state IS
    'Record-level memory gate: only READY with all approved chunks ready is available to AI; NONE/PENDING/DISABLED are excluded';

CREATE OR REPLACE FUNCTION record_memory_is_complete(target_record_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM records r
        WHERE r.id = target_record_id AND r.source = 'user' AND r.deleted_at IS NULL
          AND r.memory_state = 'READY'
          AND EXISTS (SELECT 1 FROM chunks c WHERE c.record_id = r.id AND c.user_id = r.user_id
                      AND c.ai_memory_approved = TRUE)
          AND NOT EXISTS (SELECT 1 FROM chunks c WHERE c.record_id = r.id AND c.user_id = r.user_id
                          AND c.ai_memory_approved = TRUE
                          AND (c.embedding IS NULL OR c.embedding_status <> 'ready'))
    );
$$;
