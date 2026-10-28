-- Additive, repeatable tarot migration. Apply before deploying the tarot API.
CREATE TABLE IF NOT EXISTS tarot_daily_draw (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 draw_date DATE NOT NULL, card_id TEXT NOT NULL, catalog_version TEXT NOT NULL,
 client_request_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 UNIQUE(user_id,draw_date), UNIQUE(user_id,client_request_id), UNIQUE(user_id,id)
);
CREATE TABLE IF NOT EXISTS tarot_daily_request (
 user_id UUID NOT NULL, client_request_id UUID NOT NULL, draw_id UUID NOT NULL,
 PRIMARY KEY(user_id,client_request_id),
 FOREIGN KEY(user_id,draw_id) REFERENCES tarot_daily_draw(user_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS tarot_collection (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, card_id TEXT NOT NULL,
 first_obtained_at TIMESTAMPTZ NOT NULL, daily_draw_count BIGINT NOT NULL CHECK(daily_draw_count>0),
 PRIMARY KEY(user_id,card_id)
);
CREATE TABLE IF NOT EXISTS tarot_preferences (
 user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 diary_enabled BOOLEAN NOT NULL DEFAULT TRUE, diary_days INTEGER NOT NULL DEFAULT 7 CHECK(diary_days IN(7,30,90)),
 todos_enabled BOOLEAN NOT NULL DEFAULT FALSE, mirror_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tarot_reading (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 card_id TEXT NOT NULL, catalog_version TEXT NOT NULL, client_request_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, latest_analysis_id UUID,
 UNIQUE(user_id,client_request_id), UNIQUE(user_id,id)
);
CREATE INDEX IF NOT EXISTS tarot_reading_latest ON tarot_reading(user_id,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS tarot_analysis (
 id UUID PRIMARY KEY, user_id UUID NOT NULL, reading_id UUID NOT NULL, client_request_id UUID NOT NULL,
 payload_hash TEXT NOT NULL, question TEXT NOT NULL, options JSONB NOT NULL,
 status TEXT NOT NULL CHECK(status IN('pending','running','completed','failed','stale')),
 text TEXT, source_refs JSONB NOT NULL DEFAULT '[]', notices JSONB NOT NULL DEFAULT '[]', error_code TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(user_id,client_request_id), UNIQUE(user_id,id),
 FOREIGN KEY(user_id,reading_id) REFERENCES tarot_reading(user_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS tarot_analysis_one_running ON tarot_analysis(reading_id) WHERE status IN('pending','running');
CREATE TABLE IF NOT EXISTS tarot_chat_state (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
 suppressed BOOLEAN NOT NULL DEFAULT FALSE, last_offered_date DATE, last_offered_run_id UUID,
 PRIMARY KEY(user_id,session_id)
);
