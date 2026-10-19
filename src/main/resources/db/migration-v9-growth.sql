CREATE TABLE IF NOT EXISTS growth_axes (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 dimension_id VARCHAR(40) NOT NULL, name VARCHAR(80) NOT NULL, description TEXT NOT NULL DEFAULT '',
 definition_version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, dimension_id), CHECK(definition_version > 0)
);
ALTER TABLE records ADD COLUMN IF NOT EXISTS growth_suggestions TEXT NOT NULL DEFAULT '[]',
 ADD COLUMN IF NOT EXISTS growth_axes_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS growth_entries (
 id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 dimension_id VARCHAR(40) NOT NULL, points INTEGER NOT NULL CHECK(points BETWEEN 0 AND 100),
 reason TEXT NOT NULL, definition_version BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL,
 source_record_id BIGINT REFERENCES records(id) ON DELETE CASCADE, action_id UUID, correction_of BIGINT REFERENCES growth_entries(id),
 version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT growth_entry_source_check CHECK(source_type IN ('record','manual')),
 CONSTRAINT growth_entry_manual_record_check CHECK((source_type='manual' AND source_record_id IS NULL) OR (source_type='record' AND source_record_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS growth_axis_history (
 user_id UUID NOT NULL, dimension_id VARCHAR(40) NOT NULL, definition_version BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL, description TEXT NOT NULL, captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, dimension_id, definition_version)
);
CREATE TABLE IF NOT EXISTS growth_entry_history (
 id BIGSERIAL PRIMARY KEY, entry_id BIGINT NOT NULL, user_id UUID NOT NULL, dimension_id VARCHAR(40) NOT NULL,
 points INTEGER NOT NULL, reason TEXT NOT NULL, definition_version BIGINT NOT NULL, entry_version BIGINT NOT NULL,
 replaced_at TIMESTAMPTZ NOT NULL DEFAULT now(), replacement_request_id UUID
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_growth_record_axis ON growth_entries(user_id, source_record_id, dimension_id) WHERE source_record_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_growth_entries_user_recent ON growth_entries(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_growth_entries_record ON growth_entries(user_id, source_record_id) WHERE source_record_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_growth_manual_request ON growth_entries(user_id, action_id) WHERE source_type='manual' AND action_id IS NOT NULL;
