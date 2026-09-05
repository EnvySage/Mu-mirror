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
    embedding vector(1024),             -- BGE-m3，硬约束 1024 维（裁决 #18）
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunks_user_id ON chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_chunks_record_id ON chunks(record_id);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);

-- ============================================================
-- 用户配置表（AI 模型配置，每个用户一条）
-- rag_half_life：RAG 时间衰减半衰期（天，7-365），6.4 规划项
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
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_settings_user_id ON user_settings(user_id);

-- ============================================================
-- 画像快照（取代旧 mirror_profiles，6.5）
-- 每用户仅 ~14 份（手动保 2 + 月度保 12）→ 不建向量索引，顺序扫描更快（裁决 #14）
-- ============================================================
CREATE TABLE IF NOT EXISTS profile_snapshots (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    snapshot_type VARCHAR(20) NOT NULL,   -- manual（用户触发）/ monthly（每月1号定时）
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
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_user_terms UNIQUE (user_id, term)
);
CREATE INDEX IF NOT EXISTS idx_user_terms_user_status ON user_terms(user_id, status);
