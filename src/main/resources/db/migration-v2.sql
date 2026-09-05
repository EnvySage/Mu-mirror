-- ============================================================
-- 迁移脚本：存量旧库 → 设计文档 v2.1 基准形态
-- 一次性迁移；幂等，可重复执行。基准 DDL 见 schema.sql（全量新建形态）。
-- 新部署（空库）只需 schema.sql，本文件为无害空操作。
-- 覆盖任务：T-DB-1（v2 基线）/ T-DB-2（规划表）/ T-DB-3（HNSW）
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 1. records：删除已废除的列 ----------
-- 裁决 #2：segment 双份存储有一致性隐患，chunks.segment 是唯一真源
ALTER TABLE records DROP COLUMN IF EXISTS segment;
-- 裁决 #1：不再拆多条 Record，拆分组关系由"同一 record_id 的多个 chunk"表达
ALTER TABLE records DROP COLUMN IF EXISTS original_record_id;
-- 08854ba 已物理删除的旧列，幂等兜底
ALTER TABLE records DROP COLUMN IF EXISTS title;
ALTER TABLE records DROP COLUMN IF EXISTS summary;
ALTER TABLE records DROP COLUMN IF EXISTS content_type;
ALTER TABLE records DROP COLUMN IF EXISTS mood;

-- ---------- 2. records：新增 source 列（裁决 #20，每日总结统计口径分离） ----------
ALTER TABLE records ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'user';

-- ---------- 2.1 records：fail_reason（failed 卡片透出具体原因，8.2/8.5） ----------
ALTER TABLE records ADD COLUMN IF NOT EXISTS fail_reason TEXT;

-- ---------- 3. 废弃 tags 表（裁决 #6：关键词存 chunks.metadata.keywords） ----------
-- 被删结构备份：tags(id BIGSERIAL PK, record_id BIGINT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
--                  keyword VARCHAR(50) NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW())
--                  索引：idx_tags_keyword, idx_tags_record_id
DROP TABLE IF EXISTS tags;

-- ---------- 4. chunks：补齐 v2.0 核心列 ----------
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS segment TEXT;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS classified_segment TEXT;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS user_edited BOOLEAN DEFAULT FALSE;

-- ---------- 5. chunks：HNSW 向量索引（裁决 #18：维度硬约束 1024，一次建成） ----------
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);

-- ---------- 6. user_settings：补齐 v2.0/v2.1 列 ----------
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS ai_protocol VARCHAR(20) DEFAULT 'anthropic';
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS embedding_base_url TEXT;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS rag_half_life INT DEFAULT 30;

-- ---------- 7. 规划表（3.3 原样 DDL） ----------
-- 画像快照（取代 mirror_profiles，6.5；每用户 ~14 份，不建向量索引，裁决 #14）
CREATE TABLE IF NOT EXISTS profile_snapshots (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    snapshot_type VARCHAR(20) NOT NULL,
    mood_analysis TEXT,
    learning_analysis TEXT,
    todo_analysis TEXT,
    rhythm_analysis TEXT,
    user_tags JSONB,
    overall_summary TEXT,
    embedding vector(1024),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_snapshots_user ON profile_snapshots(user_id, snapshot_type, created_at DESC);

-- 会话（无 last_message_at，统一 updated_at，裁决 #9）
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id, updated_at DESC);

-- 对话历史（sources 落库，裁决 #8）
CREATE TABLE IF NOT EXISTS conversation_history (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    sources JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_history_session ON conversation_history(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_history_user ON conversation_history(user_id, created_at DESC);

-- ---------- 8. 已删除记录的 chunks 保留（软删除记录不进检索，由 SQL 关联过滤，见 ChunkMapper） ----------

-- 2026-09-05 个人词典 user_terms（lexicon-design.md）
CREATE TABLE IF NOT EXISTS user_terms (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    term VARCHAR(100) NOT NULL,
    aliases JSONB DEFAULT '[]'::jsonb,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    query_hit_count INT DEFAULT 0,
    content_hit_count INT DEFAULT 0,
    last_confirmed_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL,
    source_record_id BIGINT,                -- 佐证 chunk 所在记录（F 契约：跳记录详情直接用）
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_user_terms UNIQUE (user_id, term)
);
CREATE INDEX IF NOT EXISTS idx_user_terms_user_status ON user_terms(user_id, status);
ALTER TABLE user_terms ADD COLUMN IF NOT EXISTS source_record_id BIGINT; -- 2026-09-06 存量库补列（F 契约）
