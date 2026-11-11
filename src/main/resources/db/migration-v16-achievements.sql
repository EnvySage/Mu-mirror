-- Apply after v15. Repeatable; definitions are loaded by the application catalog.
CREATE TABLE IF NOT EXISTS achievement_definition (
 code TEXT PRIMARY KEY, definition JSONB NOT NULL, rule_version INTEGER NOT NULL CHECK(rule_version>0),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS achievement_event (
 id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 event_key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 processed_at TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0,
 available_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error TEXT
);
CREATE INDEX IF NOT EXISTS achievement_event_due ON achievement_event(available_at,id) WHERE processed_at IS NULL;
CREATE TABLE IF NOT EXISTS user_activity_fact (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL, subject_key TEXT NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(user_id,kind,subject_key)
);
CREATE TABLE IF NOT EXISTS user_achievement (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 code TEXT NOT NULL REFERENCES achievement_definition(code), rule_version INTEGER NOT NULL,
 unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(), acknowledged_at TIMESTAMPTZ,
 PRIMARY KEY(user_id,code)
);
CREATE INDEX IF NOT EXISTS user_achievement_pending ON user_achievement(user_id,unlocked_at,code) WHERE acknowledged_at IS NULL;
CREATE TABLE IF NOT EXISTS user_achievement_showcase (
 user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 capacity INTEGER NOT NULL DEFAULT 8 CHECK(capacity IN(8,10)), codes JSONB NOT NULL DEFAULT '[]',
 version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(jsonb_typeof(codes)='array' AND jsonb_array_length(codes)<=capacity)
);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS achievement_model_profile_id UUID;

-- A profile revision is an opaque identity. No credentials or content enter facts.
CREATE OR REPLACE FUNCTION achievement_profile_key(p UUID) RETURNS TEXT LANGUAGE SQL STABLE AS $$
 SELECT id::text || ':' || extract(epoch from updated_at)::text FROM user_model_profiles WHERE id=p
$$;

CREATE OR REPLACE FUNCTION achievement_fact_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO achievement_event(user_id,event_key,kind)
 VALUES(NEW.user_id,'fact:'||NEW.user_id||':'||NEW.kind||':'||NEW.subject_key,'activity.changed')
 ON CONFLICT(event_key) DO NOTHING;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS achievement_fact_changed ON user_activity_fact;
CREATE TRIGGER achievement_fact_changed AFTER INSERT ON user_activity_fact
 FOR EACH ROW EXECUTE FUNCTION achievement_fact_change();

CREATE OR REPLACE FUNCTION achievement_capture_model() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 SELECT active_model_profile_id INTO NEW.achievement_model_profile_id FROM user_settings WHERE user_id=NEW.user_id;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS achievement_run_model ON agent_runs;
CREATE TRIGGER achievement_run_model BEFORE INSERT ON agent_runs FOR EACH ROW EXECUTE FUNCTION achievement_capture_model();

CREATE OR REPLACE FUNCTION achievement_domain_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
 row_data JSONB; prior JSONB; owner UUID; subject TEXT; profile_key TEXT;
BEGIN
 row_data := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 prior := CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
 owner := (row_data->>'user_id')::uuid;
 IF NOT EXISTS(SELECT 1 FROM users WHERE id=owner) THEN RETURN NULL; END IF;
 subject := coalesce(row_data->>'id',row_data->>'run_id',row_data->>'dimension_id',owner::text);
 INSERT INTO achievement_event(user_id,event_key,kind)
 VALUES(owner,TG_TABLE_NAME||':'||owner||':'||subject||':'||txid_current(),TG_TABLE_NAME||'.changed')
 ON CONFLICT(event_key) DO NOTHING;
 IF TG_OP='DELETE' THEN RETURN NULL; END IF;
 IF TG_TABLE_NAME='agent_runs' AND row_data->>'mode'='live'
    AND row_data->>'status'='completed' AND prior->>'status' IS DISTINCT FROM 'completed'
    AND EXISTS(SELECT 1 FROM conversation_history WHERE id=(row_data->>'assistant_message_id')::bigint
      AND user_id=owner AND NOT is_fallback AND btrim(content)<>'') THEN
   INSERT INTO user_activity_fact(user_id,kind,subject_key,occurred_at)
   VALUES(owner,'chat.completed',subject,coalesce((row_data->>'finished_at')::timestamptz,now()))
   ON CONFLICT DO NOTHING;
   IF row_data->>'achievement_model_profile_id' IS NOT NULL THEN
     INSERT INTO user_activity_fact(user_id,kind,subject_key)
     VALUES(owner,'chat.model_used',row_data->>'achievement_model_profile_id') ON CONFLICT DO NOTHING;
   END IF;
   IF coalesce((row_data->>'glossary_hits_applied')::boolean,false)
      OR EXISTS(SELECT 1 FROM user_activity_fact WHERE user_id=owner AND kind='glossary.matched' AND subject_key=subject) THEN
     INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(owner,'glossary.used',subject) ON CONFLICT DO NOTHING;
   END IF;
 ELSIF TG_TABLE_NAME='todo_registry' AND row_data->>'current_status'='completed'
   AND row_data->>'deleted_at' IS NULL AND (row_data->>'origin_kind'='direct' OR row_data->>'source_chunk_id' IS NOT NULL) THEN
   INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(owner,'todo.completed',subject) ON CONFLICT DO NOTHING;
 ELSIF TG_TABLE_NAME='growth_axes' AND TG_OP='UPDATE'
   AND (row_data->>'name' IS DISTINCT FROM prior->>'name' OR row_data->>'description' IS DISTINCT FROM prior->>'description') THEN
   INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(owner,'growth.customized',subject) ON CONFLICT DO NOTHING;
 ELSIF TG_TABLE_NAME='user_settings' AND row_data->>'active_model_profile_id' IS NOT NULL THEN
   SELECT achievement_profile_key(p.id) INTO profile_key FROM user_model_profiles p
   WHERE p.id=(row_data->>'active_model_profile_id')::uuid AND p.user_id=owner
     AND p.ai_provider=row_data->>'ai_provider' AND p.ai_protocol=row_data->>'ai_protocol'
     AND p.ai_model=row_data->>'ai_model' AND p.ai_api_key=row_data->>'ai_api_key'
     AND coalesce(p.ai_base_url,'')=coalesce(row_data->>'ai_base_url','');
   IF profile_key IS NOT NULL THEN
     INSERT INTO user_activity_fact(user_id,kind,subject_key) VALUES(owner,'model.activated',profile_key) ON CONFLICT DO NOTHING;
   END IF;
 END IF;
 RETURN NULL;
END $$;
DO $$
DECLARE relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['records','chunks','agent_runs','user_settings','user_model_profiles',
   'todo_registry','assistant_actions','growth_entries','growth_axes','profile_snapshots',
   'tarot_daily_draw','tarot_collection','vault_items','user_terms']
 LOOP
   EXECUTE format('DROP TRIGGER IF EXISTS achievement_changed ON %I',relation);
   EXECUTE format('CREATE TRIGGER achievement_changed AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION achievement_domain_change()',relation);
 END LOOP;
END $$;

-- Reconcile users created before this migration. Re-running does not enqueue twice.
INSERT INTO user_activity_fact(user_id,kind,subject_key,occurred_at)
 SELECT a.user_id,'chat.completed',a.run_id::text,coalesce(a.finished_at,a.started_at)
 FROM agent_runs a JOIN conversation_history h ON h.id=a.assistant_message_id AND h.user_id=a.user_id
 WHERE a.mode='live' AND a.status='completed' AND NOT h.is_fallback AND btrim(h.content)<>''
 ON CONFLICT DO NOTHING;
INSERT INTO user_activity_fact(user_id,kind,subject_key,occurred_at)
 SELECT a.user_id,'glossary.used',a.run_id::text,coalesce(a.finished_at,a.started_at)
 FROM agent_runs a JOIN conversation_history h ON h.id=a.assistant_message_id AND h.user_id=a.user_id
 WHERE a.mode='live' AND a.status='completed' AND a.glossary_hits_applied AND NOT h.is_fallback AND btrim(h.content)<>''
 ON CONFLICT DO NOTHING;
INSERT INTO user_activity_fact(user_id,kind,subject_key,occurred_at)
 SELECT user_id,'todo.completed',id::text,coalesce(closed_at,updated_at,created_at)
 FROM todo_registry WHERE current_status='completed' AND deleted_at IS NULL
 AND (origin_kind='direct' OR source_chunk_id IS NOT NULL) ON CONFLICT DO NOTHING;
INSERT INTO user_activity_fact(user_id,kind,subject_key)
 SELECT user_id,'growth.customized',dimension_id FROM growth_axes WHERE definition_version>1
 ON CONFLICT DO NOTHING;
INSERT INTO achievement_event(user_id,event_key,kind)
 SELECT id,'v16:bootstrap:'||id,'catalog.reconcile' FROM users ON CONFLICT DO NOTHING;
COMMENT ON TABLE achievement_event IS 'Transactional outbox; no diary text, API keys or model prompts';
COMMENT ON TABLE user_activity_fact IS 'Durable deduplicated milestones and validated UI opens; current-state metrics read business tables';
