-- 用户表（对应设计文档）
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- ============================================================
-- 记录表（核心业务表，对应设计文档 5.1）
-- ============================================================
CREATE TABLE IF NOT EXISTS records (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id),                          -- 关联用户（预留多用户）
    content TEXT NOT NULL,                                       -- 用户原始输入
    title VARCHAR(200),                                          -- AI 生成的标题（10字以内）
    summary TEXT,                                                -- AI 生成的摘要（30字以内）
    content_type VARCHAR(20),                                    -- 内容类型："todo/thought/learning/plan/note/work/social/health"
    mood JSONB,                                                  -- 情绪：多选数组，如 ["happy", "calm"]
    status VARCHAR(20) DEFAULT 'processing',                    -- 处理状态：processing/done/failed
    user_reviewed BOOLEAN DEFAULT FALSE,                         -- 用户是否已审核标签
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- records 索引
CREATE INDEX IF NOT EXISTS idx_records_user_id ON records(user_id);
CREATE INDEX IF NOT EXISTS idx_records_content_type ON records(content_type);
CREATE INDEX IF NOT EXISTS idx_records_status ON records(status);
CREATE INDEX IF NOT EXISTS idx_records_created_at ON records(created_at DESC);




-- 给 records 表添加 deleted_at 字段
ALTER TABLE records
    ADD COLUMN deleted_at TIMESTAMPTZ DEFAULT NULL;

-- 添加索引（提高软删除查询性能）
CREATE INDEX idx_records_deleted_at ON records(deleted_at);

-- 可选：添加复合索引（用户 + 未删除 + 创建时间）
CREATE INDEX idx_records_user_not_deleted ON records(user_id, created_at)
    WHERE deleted_at IS NULL;

-- 拆分关联字段
ALTER TABLE records ADD COLUMN IF NOT EXISTS original_record_id BIGINT REFERENCES records(id);
CREATE INDEX IF NOT EXISTS idx_records_original_record_id ON records(original_record_id);


-- mood JSONB 的 GIN 索引（支持 @> 操作符查询，如查找包含 "anxious" 的记录）
CREATE INDEX IF NOT EXISTS idx_records_mood ON records USING GIN (mood);

-- ============================================================
-- 标签表（关键词标签，一条记录可有多个关键词）
-- ============================================================
CREATE TABLE IF NOT EXISTS tags (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT NOT NULL REFERENCES records(id) ON DELETE CASCADE,  -- 级联删除
    keyword VARCHAR(50) NOT NULL,                                -- 关键词
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- tags 索引
CREATE INDEX IF NOT EXISTS idx_tags_record_id ON tags(record_id);
CREATE INDEX IF NOT EXISTS idx_tags_keyword ON tags(keyword);

-- ============================================================
-- 用户配置表（AI 模型配置，每个用户一条）
-- ============================================================
CREATE TABLE IF NOT EXISTS user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(id),
    -- LLM 配置
    ai_provider VARCHAR(50),         -- AI 提供商：openai/zhipu/qwen
    ai_api_key TEXT,                 -- API Key（加密存储）
    ai_base_url TEXT,                -- API 地址
    ai_model VARCHAR(100),           -- 模型名称
    -- Embedding 配置
    embedding_source VARCHAR(20) DEFAULT 'local', -- local / api
    embedding_api_key TEXT,          -- Embedding API Key（加密）
    embedding_model VARCHAR(100),    -- Embedding 模型名
    -- 审核配置
    review_mode VARCHAR(20) DEFAULT 'manual', -- manual / auto
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- user_settings 索引
CREATE INDEX IF NOT EXISTS idx_user_settings_user_id ON user_settings(user_id);
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS ai_protocol VARCHAR(20) DEFAULT 'anthropic';
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS embedding_base_url TEXT;

ALTER TABLE records ADD COLUMN IF NOT EXISTS original_record_id BIGINT REFERENCES records(id);
CREATE INDEX IF NOT EXISTS idx_records_original_record_id ON records(original_record_id);
