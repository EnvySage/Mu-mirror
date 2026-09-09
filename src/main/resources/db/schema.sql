-- ============================================================
-- Mu-mirror-B 基准 DDL（设计文档 v2.1 对齐版）
-- 全量新建形态：不含任何 ALTER/DROP 堆叠，从空库一次建齐。
-- 幂等：可重复执行（IF NOT EXISTS）。
--
-- 执行顺序（docker-entrypoint-initdb.d 按文件名字典序）：
--   1. schema.sql        ← 本文件，唯一权威基准（chunks 已并入）
--   2. migration-v2.sql  ← 仅存量旧库迁移用；空库执行为无害空操作
--   3. chunks.sql        ← 已并入本文件，仅存占位说明，无 DDL
-- 对应设计文档：Mu-mirror-B/docs/2026-09-03-system-design-v2.md 第三章、3.4、裁决 #2/#6/#8/#9/#14/#17/#18/#20
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 用户表（不变）
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- ============================================================
-- 记录表（v2.1：source 区分用户/系统生成，裁决 #20；segment 列已废除，裁决 #2）
-- ============================================================
CREATE TABLE IF NOT EXISTS records (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    content TEXT NOT NULL,                              -- 原始输入，不可修改
    source VARCHAR(20) DEFAULT 'user',                  -- user=用户输入 / system=系统生成（每日总结，裁决 #17）
    status VARCHAR(20) DEFAULT 'processing',            -- processing/reviewing/done/failed
    user_reviewed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,                             -- 软删除
    fail_reason TEXT                                    -- 失败原因（仅 failed；前端卡片透出，8.2）
);
CREATE INDEX IF NOT EXISTS idx_records_user_id ON records(user_id);
CREATE INDEX IF NOT EXISTS idx_records_created_at ON records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_records_status ON records(status);
CREATE INDEX IF NOT EXISTS idx_records_deleted_at ON records(deleted_at);
CREATE INDEX IF NOT EXISTS idx_records_user_not_deleted ON records(user_id, created_at)
    WHERE deleted_at IS NULL;

-- ============================================================
-- 向量块表（v2.0 唯一业务单元：segment + AI 元数据 + 向量）
-- ============================================================
CREATE TABLE IF NOT EXISTS chunks (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    record_id BIGINT NOT NULL REFERENCES records(id),
    content TEXT NOT NULL,              -- 整条记录原文（冗余存储，检索展示用）
    segment TEXT,                       -- 语义片段（embedding 输入文本；唯一真源，裁决 #2）
    metadata JSONB,                     -- AI 元数据（title/summary/contentType/mood/keywords/taskStatus）
    classified_segment TEXT,            -- 生成当前 metadata 时所用的 segment 文本；NULL = 未分类/文本已改
    user_edited BOOLEAN DEFAULT FALSE,  -- 用户是否编辑过（文本或元数据），统计用
    vault_item_id BIGINT REFERENCES vault_items(id) ON DELETE CASCADE,  -- vault 全消化产物挂链（vault 删除时级联清，向量库无孤儿）
    embedding vector(1024),             -- BGE-m3，硬约束 1024 维（裁决 #18）
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunks_user_id ON chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_chunks_record_id ON chunks(record_id);
CREATE INDEX IF NOT EXISTS idx_chunks_vault_item ON chunks(vault_item_id);  -- vault 消化 chunks 溯源/级联清
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);

-- ============================================================
-- 用户配置表（AI 模型配置，每个用户一条）
-- rag_half_life：RAG 时间衰减半衰期（天，7-365），6.4 规划项
-- mirror_lookback：镜子回看深度档位 0-3（rolling-mirror-design.md §2）
-- ============================================================
CREATE TABLE IF NOT EXISTS user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(id),
    ai_provider VARCHAR(50),                        -- AI 提供商：openai/zhipu/qwen
    ai_protocol VARCHAR(20) DEFAULT 'anthropic',    -- openai / anthropic
    ai_api_key TEXT,                                -- API Key（AES-256-GCM 加密）
    ai_base_url TEXT,
    ai_model VARCHAR(100),
    embedding_source VARCHAR(20) DEFAULT 'local',   -- local / api
    embedding_base_url TEXT,
    embedding_api_key TEXT,                         -- 加密
    embedding_model VARCHAR(100),
    review_mode VARCHAR(20) DEFAULT 'manual',       -- manual / auto
    rag_half_life INT DEFAULT 30,
    mirror_lookback INT DEFAULT 1,                  -- 回看深度 0=纯继承上月镜子 / 1=上月原文 / 2=近三月原文 / 3=全部原文
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_settings_user_id ON user_settings(user_id);

-- ============================================================
-- 画像快照（取代旧 mirror_profiles，6.5）
-- 每用户仅 ~14 份（手动保 2 + 月度保 12）→ 不建向量索引，顺序扫描更快（裁决 #14）
-- period_month：monthly 快照归属月份 yyyy-MM（rolling-mirror-design.md §4-B；幂等判断精确列）
-- ============================================================
CREATE TABLE IF NOT EXISTS profile_snapshots (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    snapshot_type VARCHAR(20) NOT NULL,   -- manual（用户触发）/ monthly（每月1号定时）
    period_month CHAR(7),                 -- monthly 归属月份 yyyy-MM（manual 为 NULL）；历史行按内容/created_at 补值
    mood_analysis TEXT,
    learning_analysis TEXT,
    todo_analysis TEXT,
    rhythm_analysis TEXT,
    user_tags JSONB,                      -- ["技术学习", "夜猫子"]
    overall_summary TEXT,
    embedding vector(1024),               -- 漂移检测用（五维文本按固定顺序拼接后向量化）
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_snapshots_user ON profile_snapshots(user_id, snapshot_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_snapshots_period ON profile_snapshots(user_id, snapshot_type, period_month);
-- 同一 (user, monthly, 归属月份) 唯一：幂等重生成即替换（部分唯一索引，NULL 不参与约束）
CREATE UNIQUE INDEX IF NOT EXISTS uq_snapshots_user_period
    ON profile_snapshots(user_id, period_month) WHERE snapshot_type = 'monthly' AND period_month IS NOT NULL;

-- ============================================================
-- 会话（6.6；无 last_message_at，统一用 updated_at，裁决 #9）
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()   -- 每次新消息触碰；会话列表按它倒序
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id, updated_at DESC);

-- ============================================================
-- 对话历史（sources 落库，裁决 #8：来源追溯是对话模块核心卖点）
-- ============================================================
CREATE TABLE IF NOT EXISTS conversation_history (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(20) NOT NULL,             -- user / assistant
    content TEXT NOT NULL,
    sources JSONB,                         -- [{record_id, quote, date}]，assistant 消息的来源追溯
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_history_session ON conversation_history(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_history_user ON conversation_history(user_id, created_at DESC);

-- ============================================================
-- user_terms 个人词典（lexicon-design.md v1.0，2026-09-05）
-- 机器猜的 pending 只展示不注入；confirmed 才生效（哲学同审核机制）
-- ============================================================
CREATE TABLE IF NOT EXISTS user_terms (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    term VARCHAR(100) NOT NULL,
    aliases JSONB DEFAULT '[]'::jsonb,     -- ["毕设","那个设计"]
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending/confirmed/dismissed
    query_hit_count INT DEFAULT 0,          -- 用户提问命中（注入优先级）
    content_hit_count INT DEFAULT 0,        -- 入库内容命中（过期沉底）
    last_confirmed_at TIMESTAMPTZ,          -- confirmed 卡片"最后确认于x日"
    last_seen_at TIMESTAMPTZ,               -- 最近语料出现（衰减依据）
    source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL,
    source_record_id BIGINT,                -- 佐证 chunk 所在记录（F 契约：跳记录详情直接用；@agent-DB 已在活库补列）
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_user_terms UNIQUE (user_id, term)
);
CREATE INDEX IF NOT EXISTS idx_user_terms_user_status ON user_terms(user_id, status);

-- ============================================================
-- vault 用户资产保管 + tool_calls 工具审计（dialogue-enhancement-ideas.md C/E 组，2026-09-05）
-- 存储定稿：Postgres BYTEA 直存（数据主权 100% 收敛），20MB 单文件上限（配置化）
-- 三档消化：文本全消化/图片借用户描述/音视频元数据；SHA-256 去重；硬删除
-- ============================================================
CREATE TABLE IF NOT EXISTS vault_items (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    original_name VARCHAR(255) NOT NULL,     -- 清洗后原文件名（路径穿越防护）
    storage_key VARCHAR(500) NOT NULL,       -- {userId}/{yyyyMM}/{uuid}.ext（VaultStorage 接口抽象参数）
    size_bytes BIGINT NOT NULL,
    mime VARCHAR(100) NOT NULL,              -- magic bytes 校验后的真实 mime
    sha256 VARCHAR(64),                      -- 同用户同内容去重
    category VARCHAR(20),                    -- 复用 contentType 枚举
    description VARCHAR(500),                -- 用户一句话提示 / LLM 自动命名（三层渐进）
    digest_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    -- 五态（fix-batch B7，§3.3b 确认门禁）：pending（排队）→ extracted（提取完待确认）
    --   → confirmed（已确认+已 embed，进检索）；skipped（音视频零消化）；failed（消化失败）
    --   旧 done 态迁移见 migration-v2.sql B7 段（文本 done→confirmed / 图片 done→extracted）
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

-- toolcalling 审计（论文素材：工具使用频率/成功率/延迟分布）
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

-- ============================================================
-- 待办登记表（todo-registry-design.md §2，2026-09-09）
-- 跨日记待办状态跟踪：新日记提及旧待办 → LLM 判别 → 建议更新 → 用户裁决。
-- 真源唯一（裁决 #33）：chunk.metadata.taskStatus 保持唯一真源，registry.current_status 是索引（物化）；
-- 关联是用户背书的产物：origin=登记时原始片段，evidence=确认建议时才落（机器猜的不落库，裁决 #22 一脉）。
-- ============================================================
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
