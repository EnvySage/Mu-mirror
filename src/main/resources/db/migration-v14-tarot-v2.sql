-- Additive and repeatable. Legacy draws were upright; keep their original card and collection.
ALTER TABLE tarot_daily_draw ADD COLUMN IF NOT EXISTS orientation TEXT NOT NULL DEFAULT 'upright' CHECK (orientation IN ('upright','reversed'));
ALTER TABLE tarot_reading ADD COLUMN IF NOT EXISTS orientation TEXT NOT NULL DEFAULT 'upright' CHECK (orientation IN ('upright','reversed'));
CREATE INDEX IF NOT EXISTS tarot_daily_history ON tarot_daily_draw(user_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS tarot_analysis_versions ON tarot_analysis(user_id,reading_id,created_at DESC,id DESC);
