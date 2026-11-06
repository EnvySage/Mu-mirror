-- Persist named LLM connections while retaining user_settings as the effective runtime config.
-- Apply after v14. Existing settings are lazily imported without changing any effective LLM fields.
CREATE TABLE IF NOT EXISTS user_model_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    ai_provider VARCHAR(50) NOT NULL,
    ai_protocol VARCHAR(20) NOT NULL CHECK (ai_protocol IN ('openai', 'anthropic')),
    ai_base_url TEXT,
    ai_model VARCHAR(100) NOT NULL,
    ai_api_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, id)
);
CREATE INDEX IF NOT EXISTS idx_user_model_profiles_user_id ON user_model_profiles(user_id);
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS active_model_profile_id UUID;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_settings_active_model_profile_fk'
        AND conrelid = 'user_settings'::regclass) THEN
        ALTER TABLE user_settings ADD CONSTRAINT user_settings_active_model_profile_fk
            FOREIGN KEY (user_id, active_model_profile_id) REFERENCES user_model_profiles(user_id, id);
    END IF;
END $$;
