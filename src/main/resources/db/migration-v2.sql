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
-- 2026-09-06 递归累计镜子（rolling-mirror-design.md §2）：回看深度档位 0-3，默认 1
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS mirror_lookback INT DEFAULT 1;

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

-- 2026-09-05/06 toolcalling+vault（toolcalling-vault-design.md；DB Agent af60c45 建表，B 补 chunks 挂链列）
CREATE TABLE IF NOT EXISTS vault_items (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    size_bytes BIGINT NOT NULL,
    mime VARCHAR(100) NOT NULL,
    sha256 VARCHAR(64),
    category VARCHAR(20),
    description VARCHAR(500),
    digest_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_vault_items_user ON vault_items(user_id, deleted_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vault_sha ON vault_items(user_id, sha256) WHERE sha256 IS NOT NULL AND deleted_at IS NULL;
CREATE TABLE IF NOT EXISTS vault_blobs (
    vault_item_id BIGINT PRIMARY KEY REFERENCES vault_items(id) ON DELETE CASCADE,
    data BYTEA NOT NULL
);
CREATE TABLE IF NOT EXISTS tool_calls (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    session_id UUID,
    tool VARCHAR(50) NOT NULL,
    args JSONB DEFAULT '{}'::jsonb,
    result_summary VARCHAR(500),
    success BOOLEAN NOT NULL DEFAULT true,
    latency_ms INT,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tool_calls_user ON tool_calls(user_id, created_at);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS vault_item_id BIGINT REFERENCES vault_items(id) ON DELETE CASCADE; -- vault 全消化 chunk 挂链（级联清，无孤儿）
CREATE INDEX IF NOT EXISTS idx_chunks_vault_item ON chunks(vault_item_id);

-- ---------- 9. 2026-09-06 递归累计镜子（rolling-mirror-design.md §4-B） ----------
-- monthly 快照归属月份精确列（幂等判断切精确列，替换此前 created_at 窗口近似方案）
ALTER TABLE profile_snapshots ADD COLUMN IF NOT EXISTS period_month CHAR(7);
CREATE INDEX IF NOT EXISTS idx_snapshots_period ON profile_snapshots(user_id, snapshot_type, period_month);
-- 同一 (user, 归属月份) 唯一：幂等重生成即替换（部分唯一索引，NULL 不参与约束）
CREATE UNIQUE INDEX IF NOT EXISTS uq_snapshots_user_period
    ON profile_snapshots(user_id, period_month) WHERE snapshot_type = 'monthly' AND period_month IS NOT NULL;
-- 存量 monthly 快照补值：seed 语义（内容归属月）优先于 created_at 推断——
-- 自动推断 SQL 供参考（按"定时任务次月生成上月画像"假设，createdAt 所在月-1）：
--   UPDATE profile_snapshots SET period_month = TO_CHAR((created_at AT TIME ZONE 'Asia/Shanghai')::date - INTERVAL '1 month', 'YYYY-MM')
--   WHERE snapshot_type='monthly' AND period_month IS NULL;
-- 本次活库已按内容语义手工校正（14 号 created 6/30 但内容为七月 → period_month='2026-07'）。

-- ---------- 10. 2026-09-06 审查修复批（fix-batch B7：digest_status 五态 + 确认门禁） ----------
-- 五态（toolcalling-vault-design.md §3.3b 用户定稿）：pending → extracted → confirmed；
-- skipped / failed 终态独立。应用层枚举迁移（列本身 VARCHAR 无 CHECK 约束，无需 ALTER CHECK）：
--   ① 旧 done（文本族/PDF/docx 全消化且已 embed）→ confirmed（确认门禁语义上"已可检索"）
--   ② 旧 done（图片半消化）→ extracted（Y4 如实口径：图片无全文索引，待用户确认）
-- 判定：join mime 前缀，image/ 开头的 done → extracted，其余 done → confirmed。
UPDATE vault_items SET digest_status = 'extracted'
WHERE digest_status = 'done' AND mime LIKE 'image/%';
UPDATE vault_items SET digest_status = 'confirmed'
WHERE digest_status = 'done' AND mime NOT LIKE 'image/%';
-- 注意：旧管道 done 时全文 chunk 已 embed，confirmed 语义成立；其 key chunk 由下次
-- confirm（幂等入口：confirmed 重复确认直接返回，不重建 key chunk）之外的场景补齐——
-- 存量已确认资产如需 key chunk，可由用户在资产页"改一改"再确认触发（Accept: extracted 后重复确认）。

-- ---------- 11. 2026-09-09 待办登记表（todo-registry-design.md §2） ----------
-- 跨日记待办状态跟踪三表。真源唯一（裁决 #33）：chunk.metadata.taskStatus 保持唯一真源，
-- registry.current_status 是索引（物化），确认时事务内双写；关联是用户背书的产物（裁决 #22）。
CREATE TABLE IF NOT EXISTS todo_registry (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,              -- 来自 chunk metadata.title
    current_status VARCHAR(20) NOT NULL DEFAULT 'not_started',  -- not_started/in_progress/completed
    source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL,  -- 原始待办片段；被删→orphan 关闭
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    closed_at TIMESTAMPTZ                     -- completed 时刻（追溯）
);
CREATE INDEX IF NOT EXISTS idx_todo_reg_user ON todo_registry(user_id, current_status);

CREATE TABLE IF NOT EXISTS todo_registry_links (
    id BIGSERIAL PRIMARY KEY,
    todo_id BIGINT NOT NULL REFERENCES todo_registry(id) ON DELETE CASCADE,
    chunk_id BIGINT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    relation VARCHAR(10) NOT NULL,            -- origin / evidence
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(todo_id, chunk_id)
);

CREATE TABLE IF NOT EXISTS todo_suggestions (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    todo_id BIGINT NOT NULL REFERENCES todo_registry(id) ON DELETE CASCADE,
    evidence_chunk_id BIGINT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,  -- 触发建议的新日记片段
    suggested_status VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending/confirmed/dismissed
    created_at TIMESTAMPTZ DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_todo_sug_user ON todo_suggestions(user_id, status);
