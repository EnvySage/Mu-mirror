-- Registry owns current todo state. Existing NULL-source rows remain record orphans.
-- This migration never updates source chunks or authorizes their use as memory.
ALTER TABLE todo_registry ADD COLUMN IF NOT EXISTS origin_kind VARCHAR(16) NOT NULL DEFAULT 'record';
ALTER TABLE todo_registry ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE todo_registry ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE todo_registry ADD COLUMN IF NOT EXISTS due_at TIMESTAMPTZ;
ALTER TABLE todo_registry ALTER COLUMN title TYPE VARCHAR(200);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'todo_registry_origin_kind_check' AND conrelid = 'todo_registry'::regclass) THEN
        ALTER TABLE todo_registry ADD CONSTRAINT todo_registry_origin_kind_check CHECK (origin_kind IN ('record', 'direct'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'todo_registry_version_check' AND conrelid = 'todo_registry'::regclass) THEN
        ALTER TABLE todo_registry ADD CONSTRAINT todo_registry_version_check CHECK (version > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'todo_registry_direct_source_check' AND conrelid = 'todo_registry'::regclass) THEN
        ALTER TABLE todo_registry ADD CONSTRAINT todo_registry_direct_source_check CHECK (origin_kind != 'direct' OR source_chunk_id IS NULL);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_todo_registry_active_owner ON todo_registry(user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
-- All domain updates/deletes use owner + version + deleted_at predicates and increment version.
-- Run only in a provisioned isolated database until the application migration is approved.
