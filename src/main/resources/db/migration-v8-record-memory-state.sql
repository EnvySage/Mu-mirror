-- Task 4 partial v8 migration. Task 5 will add memory lifecycle fields and backfill rules.
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
