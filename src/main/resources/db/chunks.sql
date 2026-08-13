-- ============================================================
-- 向量块表（pgvector，用于 RAG 检索）
-- 执行前确保 PostgreSQL 已安装 pgvector 扩展
-- ============================================================

-- 安装 pgvector 扩展（如果尚未安装）
CREATE EXTENSION IF NOT EXISTS vector;

-- 向量块表
CREATE TABLE IF NOT EXISTS chunks (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),      -- 所属用户
    record_id BIGINT NOT NULL REFERENCES records(id), -- 关联记录
    content TEXT NOT NULL,                            -- 切片内容（整条记录的原始内容）
    metadata JSONB,                                   -- 元数据（类型、情绪、时间等）
    embedding vector(1024),                           -- 向量嵌入（BGE-m3 默认 1024 维）
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- chunks 索引
CREATE INDEX IF NOT EXISTS idx_chunks_user_id ON chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_chunks_record_id ON chunks(record_id);

-- 向量相似度检索索引（IVFFlat，适合中等数据量）
-- 注意：需要先有一定数据量（建议 1000+ 条）才能创建此索引
-- CREATE INDEX idx_chunks_embedding ON chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
