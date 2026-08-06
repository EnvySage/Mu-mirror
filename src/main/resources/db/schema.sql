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
