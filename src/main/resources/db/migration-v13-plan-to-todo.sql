-- Canonical diary category: plan -> todo. Run after the compatible readers/writers are deployed.
-- A single atomic, repeatable statement; only the category key changes.
-- Preserve chunk/record IDs, source text, other metadata, embeddings and authorizations.
-- Todo registry rows, status and evidence links continue to reference the same chunk IDs.
-- Vault content and the separate chat-memory kind "plan" are outside this migration.
UPDATE chunks AS c
SET metadata = jsonb_set(c.metadata, '{contentType}', '"todo"'::jsonb, false)
FROM records AS r
WHERE r.id = c.record_id
  AND r.user_id = c.user_id
  AND r.source = 'user'
  AND c.metadata->>'contentType' = 'plan';
