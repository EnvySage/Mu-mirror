-- 记录保存与 AI 使用授权拆分。
-- 旧数据授权来源无法从现有列可靠辨别，新增列一律默认 false（fail closed）；
-- 不删除原文、片段、向量或历史摘要。历史快照继续保留并导出，但不再进入后续 AI 推断。
ALTER TABLE records
    ADD COLUMN IF NOT EXISTS ai_processing_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS processing_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS ai_memory_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS todo_approved BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'profile_snapshots'
          AND column_name = 'invalidated'
    ) THEN
        -- 只有首次增列时才将历史快照标为失效，重复执行不会影响新生成的快照。
        ALTER TABLE profile_snapshots ADD COLUMN invalidated BOOLEAN;
        UPDATE profile_snapshots SET invalidated = TRUE;
        ALTER TABLE profile_snapshots ALTER COLUMN invalidated SET DEFAULT FALSE;
        ALTER TABLE profile_snapshots ALTER COLUMN invalidated SET NOT NULL;
    END IF;
END $$;

COMMENT ON COLUMN records.ai_processing_authorized IS
    '用户是否显式请求对该记录进行 AI 整理；不代表片段已获长期记忆授权';
COMMENT ON COLUMN records.processing_version IS
    '异步处理代次；记录删除或重试时递增，防止旧任务回写';
COMMENT ON COLUMN chunks.ai_memory_approved IS
    '用户是否确认该片段可用于后续 AI 检索、摘要和画像';
COMMENT ON COLUMN chunks.todo_approved IS
    '用户是否单独确认该片段登记为待办';
COMMENT ON COLUMN profile_snapshots.invalidated IS
    '来源记忆授权变化后保留展示但不再进入后续AI推断';
