-- Mirror snapshots are durable user-approved archives. Context invalidation no longer
-- implies that a snapshot should disappear or be physically deleted.
-- Safe to re-run; this migration preserves snapshot content, embeddings, and invalidated.

ALTER TABLE profile_snapshots
    ADD COLUMN IF NOT EXISTS version_no INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS lineage_tracked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS invalidated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS invalidated_reason VARCHAR(80);

UPDATE profile_snapshots
SET invalidated_at = created_at,
    invalidated_reason = 'legacy_invalidated'
WHERE invalidated IS TRUE
  AND invalidated_at IS NULL
  AND invalidated_reason IS NULL;

DROP INDEX IF EXISTS uq_snapshots_user_period;
CREATE UNIQUE INDEX IF NOT EXISTS uq_snapshots_user_period_current
    ON profile_snapshots(user_id, period_month)
    WHERE snapshot_type = 'monthly'
      AND period_month IS NOT NULL
      AND is_current = TRUE;

CREATE TABLE IF NOT EXISTS snapshot_inputs (
    snapshot_id BIGINT NOT NULL REFERENCES profile_snapshots(id) ON DELETE CASCADE,
    input_kind VARCHAR(16) NOT NULL,
    input_id BIGINT NOT NULL,
    input_role VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (snapshot_id, input_kind, input_id, input_role)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_inputs_source
    ON snapshot_inputs(input_kind, input_id, snapshot_id);

COMMENT ON COLUMN profile_snapshots.version_no IS '版本号；同一月重生成保留旧版并递增';
COMMENT ON COLUMN profile_snapshots.is_current IS '是否为该月份当前版本；仅当前版本可参与累计镜子';
COMMENT ON COLUMN profile_snapshots.lineage_tracked IS '是否已保存本快照的输入来源依赖清单';
COMMENT ON COLUMN profile_snapshots.invalidated_at IS '退出后续 AI 上下文的时间';
COMMENT ON COLUMN profile_snapshots.invalidated_reason IS '退出后续 AI 上下文的原因';
COMMENT ON TABLE snapshot_inputs IS '快照实际使用的父快照与原始来源 ID；不存正文，来源删除后保留归档墓碑';
