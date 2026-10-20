-- Read-only data boundary for the Python graph_v2 engine.
-- Requires migration-v3-record-ai-authorization.sql to have been applied.
-- This M1 migration adds views only. Run lifecycle/receipt tables belong to the
-- later Java lifecycle task and are deliberately not created here.

CREATE SCHEMA IF NOT EXISTS mirror_ai;

CREATE OR REPLACE VIEW mirror_ai.record_chunks WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE r.source = 'user'
  AND r.status = 'done'
  AND r.deleted_at IS NULL
  AND r.ai_processing_authorized = TRUE
  AND c.ai_memory_approved = TRUE
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> '';

CREATE OR REPLACE VIEW mirror_ai.glossary_terms WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    term,
    aliases,
    description,
    query_hit_count,
    last_confirmed_at
FROM user_terms
WHERE status = 'confirmed';

CREATE OR REPLACE VIEW mirror_ai.chat_history WITH (security_barrier = true) AS
SELECT
    h.id,
    h.user_id,
    h.session_id,
    h.role,
    h.content,
    h.is_fallback,
    h.created_at
FROM conversation_history AS h
JOIN chat_sessions AS s
  ON s.id = h.session_id
 AND s.user_id = h.user_id;

CREATE OR REPLACE VIEW mirror_ai.profiles WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    snapshot_type,
    period_month,
    mood_analysis,
    learning_analysis,
    todo_analysis,
    rhythm_analysis,
    user_tags,
    overall_summary,
    embedding,
    created_at
FROM profile_snapshots
WHERE invalidated = FALSE;

CREATE OR REPLACE VIEW mirror_ai.vault_metadata WITH (security_barrier = true) AS
SELECT
    id,
    user_id,
    original_name,
    mime,
    size_bytes,
    category,
    description,
    digest_status,
    created_at
FROM vault_items
WHERE deleted_at IS NULL;

-- Only the selected segment is exposed. In the current Java pipeline,
-- vault chunks store this same segment in chunks.content; keeping the view on
-- the fragment column avoids exposing diary record content through this API.
CREATE OR REPLACE VIEW mirror_ai.vault_content WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.vault_item_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN vault_items AS v
  ON v.id = c.vault_item_id
 AND v.user_id = c.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE v.digest_status = 'confirmed'
  AND v.deleted_at IS NULL
  AND r.source = 'vault'
  AND r.deleted_at IS NULL
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> ''
  AND COALESCE(c.metadata->>'keyChunk', 'false') <> 'true';

CREATE OR REPLACE VIEW mirror_ai.vault_keys WITH (security_barrier = true) AS
SELECT
    c.id AS chunk_id,
    c.record_id,
    c.vault_item_id,
    c.user_id,
    c.segment,
    c.metadata,
    c.embedding,
    r.created_at
FROM chunks AS c
JOIN vault_items AS v
  ON v.id = c.vault_item_id
 AND v.user_id = c.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE v.digest_status = 'confirmed'
  AND v.deleted_at IS NULL
  AND r.source = 'vault'
  AND r.deleted_at IS NULL
  AND c.segment IS NOT NULL
  AND btrim(c.segment) <> ''
  AND c.metadata->>'keyChunk' = 'true';

CREATE OR REPLACE VIEW mirror_ai.todos WITH (security_barrier = true) AS
SELECT
    t.id,
    t.user_id,
    t.title,
    t.current_status,
    t.source_chunk_id,
    c.record_id,
    r.created_at
FROM todo_registry AS t
JOIN chunks AS c
  ON c.id = t.source_chunk_id
 AND c.user_id = t.user_id
JOIN records AS r
  ON r.id = c.record_id
 AND r.user_id = c.user_id
WHERE t.deleted_at IS NULL
  AND r.source = 'user'
  AND r.status = 'done'
  AND r.deleted_at IS NULL
  AND c.todo_approved = TRUE;

COMMENT ON SCHEMA mirror_ai IS
    'Fixed, read-only projections for the graph_v2 Python service';
COMMENT ON VIEW mirror_ai.record_chunks IS
    'Only user-owned, completed, AI-authorized records and separately approved non-empty chunks';
COMMENT ON VIEW mirror_ai.vault_content IS
    'Confirmed live vault body segments for explicit file reads; key chunks are excluded';
COMMENT ON VIEW mirror_ai.vault_keys IS
    'Confirmed live vault key segments for file discovery';
COMMENT ON VIEW mirror_ai.todos IS
    'Todos with independent todo_approved authorization; memory authorization is not required';
