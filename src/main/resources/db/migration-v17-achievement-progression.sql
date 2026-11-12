-- Apply after V16. Durable, validated observations and independent plating grants.
ALTER TABLE user_achievement ADD COLUMN IF NOT EXISTS plated_at TIMESTAMPTZ,
 ADD COLUMN IF NOT EXISTS plating_rule_version INTEGER,
 ADD COLUMN IF NOT EXISTS plating_acknowledged_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS user_achievement_plating_pending ON user_achievement(user_id,plated_at,code)
 WHERE plated_at IS NOT NULL AND plating_acknowledged_at IS NULL;
CREATE TABLE IF NOT EXISTS user_achievement_activity (
 id BIGSERIAL PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind TEXT NOT NULL, subject_id TEXT NOT NULL, related_id TEXT NOT NULL DEFAULT '',
 context_id TEXT NOT NULL DEFAULT '', value TEXT NOT NULL DEFAULT '',
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(), day_key DATE NOT NULL,
 UNIQUE(user_id,kind,subject_id,related_id,context_id,value,day_key)
);
CREATE INDEX IF NOT EXISTS achievement_activity_user_kind ON user_achievement_activity(user_id,kind,occurred_at,id);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS achievement_settings JSONB NOT NULL DEFAULT '{}';
ALTER TABLE records ADD COLUMN IF NOT EXISTS achievement_default_mode TEXT;

CREATE OR REPLACE FUNCTION achievement_observe(p_user UUID,p_kind TEXT,p_subject TEXT,
 p_related TEXT DEFAULT '',p_context TEXT DEFAULT '',p_value TEXT DEFAULT '',p_at TIMESTAMPTZ DEFAULT now())
 RETURNS VOID LANGUAGE SQL AS $$
 INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,context_id,value,occurred_at,day_key)
 VALUES(p_user,p_kind,p_subject,coalesce(p_related,''),coalesce(p_context,''),coalesce(p_value,''),p_at,
   (p_at AT TIME ZONE 'Asia/Shanghai')::date) ON CONFLICT DO NOTHING
$$;
CREATE OR REPLACE FUNCTION achievement_observation_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO achievement_event(user_id,event_key,kind)
 VALUES(NEW.user_id,'observation:'||NEW.id,'activity.changed') ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS achievement_observed ON user_achievement_activity;
CREATE TRIGGER achievement_observed AFTER INSERT ON user_achievement_activity
 FOR EACH ROW EXECUTE FUNCTION achievement_observation_changed();

-- The configuration identity is a one-way fingerprint, never a copied credential.
CREATE OR REPLACE FUNCTION achievement_connection_key(p_user UUID,p_kind TEXT) RETURNS TEXT LANGUAGE SQL STABLE AS $$
 SELECT CASE p_kind WHEN 'chat' THEN md5(concat_ws(chr(31),ai_provider,ai_protocol,ai_base_url,ai_model,ai_api_key))
 WHEN 'embedding' THEN md5(concat_ws(chr(31),embedding_source,embedding_base_url,embedding_model,embedding_api_key)) END
 FROM user_settings WHERE user_id=p_user
$$;
CREATE OR REPLACE FUNCTION achievement_capture_model() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 SELECT p.id INTO NEW.achievement_model_profile_id FROM user_settings s JOIN user_model_profiles p
  ON p.id=s.active_model_profile_id AND p.user_id=s.user_id
  AND p.ai_provider IS NOT DISTINCT FROM s.ai_provider AND p.ai_protocol IS NOT DISTINCT FROM s.ai_protocol
  AND p.ai_model IS NOT DISTINCT FROM s.ai_model AND p.ai_api_key IS NOT DISTINCT FROM s.ai_api_key
  AND coalesce(p.ai_base_url,'')=coalesce(s.ai_base_url,'') WHERE s.user_id=NEW.user_id;
 SELECT jsonb_build_object('chat',achievement_connection_key(NEW.user_id,'chat'),
  'embedding',achievement_connection_key(NEW.user_id,'embedding'),
  'record',s.default_record_mode,'memory',s.rag_half_life::text,'lookback',s.mirror_lookback::text,
  'chatVerified',EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=NEW.user_id
    AND a.kind='connection.verified' AND a.subject_id='chat' AND a.context_id=achievement_connection_key(NEW.user_id,'chat'))
    OR EXISTS(SELECT 1 FROM user_activity_fact f WHERE f.user_id=NEW.user_id AND f.kind='model.verified'
      AND f.subject_key=achievement_profile_key(NEW.achievement_model_profile_id)),
  'embeddingVerified',EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=NEW.user_id
    AND a.kind='connection.verified' AND a.subject_id='embedding' AND a.context_id=achievement_connection_key(NEW.user_id,'embedding')))
 INTO NEW.achievement_settings FROM user_settings s WHERE s.user_id=NEW.user_id;
 NEW.achievement_settings:=coalesce(NEW.achievement_settings,'{}');
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION achievement_capture_record_mode() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 SELECT default_record_mode INTO NEW.achievement_default_mode FROM user_settings WHERE user_id=NEW.user_id;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS achievement_record_mode ON records;
CREATE TRIGGER achievement_record_mode BEFORE INSERT ON records FOR EACH ROW EXECUTE FUNCTION achievement_capture_record_mode();

CREATE OR REPLACE FUNCTION achievement_progress_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE r JSONB; old_r JSONB; u UUID; subject TEXT; tag TEXT; message_id BIGINT; session_key TEXT; config JSONB;
 segment JSONB; old_segment JSONB; changed BOOLEAN;
BEGIN
 r:=to_jsonb(NEW); old_r:=CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
 u:=(r->>'user_id')::uuid;
 IF NOT EXISTS(SELECT 1 FROM users WHERE id=u) THEN RETURN NEW; END IF;
 subject:=coalesce(r->>'id',r->>'run_id',r->>'dimension_id');
 IF TG_TABLE_NAME='records' AND r->>'source'='user' AND r->>'deleted_at' IS NULL THEN
  IF TG_OP='UPDATE' AND old_r->>'status'='record_only'
   AND length(regexp_replace(r->>'content','[[:space:]　]','','g'))
     >length(regexp_replace(old_r->>'content','[[:space:]　]','','g')) THEN
   PERFORM achievement_observe(u,'record.edited',subject);
  END IF;
  IF r->>'status' IN('record_only','done') AND (TG_OP='INSERT' OR old_r->>'status' IS DISTINCT FROM r->>'status')
   AND ((r->>'achievement_default_mode'='ONLY_SAVE' AND r->>'status'='record_only')
    OR (r->>'achievement_default_mode'='AI_ASSISTED' AND r->>'status'='done' AND (r->>'ai_processing_authorized')::boolean)) THEN
   PERFORM achievement_observe(u,'settings.used','record','','',r->>'achievement_default_mode');
  END IF;
 ELSIF TG_TABLE_NAME='chunks' THEN
  IF TG_OP='UPDATE' AND NOT coalesce((old_r->>'ai_memory_approved')::boolean,false)
   AND coalesce((r->>'user_edited')::boolean,false)
   AND (r->>'segment' IS DISTINCT FROM old_r->>'segment' OR r->'metadata' IS DISTINCT FROM old_r->'metadata') THEN
   PERFORM achievement_observe(u,'memory.edited',subject,r->>'record_id');
  END IF;
  IF coalesce((r->>'ai_memory_approved')::boolean,false) AND NOT coalesce((old_r->>'ai_memory_approved')::boolean,false)
   AND EXISTS(SELECT 1 FROM user_achievement_activity a WHERE a.user_id=u AND a.kind='memory.edited'
     AND a.subject_id=subject AND a.related_id=r->>'record_id')
   AND EXISTS(SELECT 1 FROM records WHERE id=(r->>'record_id')::bigint AND user_id=u AND source='user' AND deleted_at IS NULL) THEN
   PERFORM achievement_observe(u,'memory.reviewed',subject,r->>'record_id');
  END IF;
  IF coalesce((r->>'user_edited')::boolean,false) AND (TG_OP='INSERT' OR r->'metadata' IS DISTINCT FROM old_r->'metadata') THEN
   FOR tag IN SELECT jsonb_array_elements_text(CASE
    WHEN jsonb_typeof(r->'metadata'->'userTags')='array' THEN r->'metadata'->'userTags'
    WHEN jsonb_typeof(r->'metadata'->'user_tags')='array' THEN r->'metadata'->'user_tags'
    WHEN jsonb_typeof(r->'metadata'->'tags')='array' THEN r->'metadata'->'tags' ELSE '[]'::jsonb END)
   LOOP
    IF nullif(btrim(tag),'') IS NOT NULL AND NOT coalesce(old_r->'metadata'->'userTags','[]'::jsonb) ? tag
     AND NOT coalesce(old_r->'metadata'->'user_tags','[]'::jsonb) ? tag
     AND NOT coalesce(old_r->'metadata'->'tags','[]'::jsonb) ? tag THEN
     PERFORM achievement_observe(u,'record.tagged',r->>'record_id',tag);
    END IF;
   END LOOP;
  END IF;
 ELSIF TG_TABLE_NAME='assistant_actions' AND TG_OP='UPDATE' AND r->>'state'='PENDING' AND old_r->>'state'='PENDING'
  AND r->>'preparation_status'='READY' AND old_r->>'preparation_status'='READY'
  AND r->>'kind' IN('record.create','record.organize') THEN
  FOR segment IN SELECT jsonb_array_elements(CASE WHEN jsonb_typeof(r->'payload'->'recordDraft'->'segments')='array'
    THEN r->'payload'->'recordDraft'->'segments' ELSE '[]'::jsonb END)
  LOOP
   IF nullif(segment->>'draftId','') IS NULL THEN CONTINUE; END IF;
   SELECT s INTO old_segment FROM jsonb_array_elements(CASE
    WHEN jsonb_typeof(old_r->'payload'->'recordDraft'->'segments')='array' THEN old_r->'payload'->'recordDraft'->'segments'
    ELSE '[]'::jsonb END) s WHERE s->>'draftId'=segment->>'draftId' LIMIT 1;
   IF old_segment IS NULL THEN
    SELECT NOT EXISTS(SELECT 1 FROM jsonb_array_elements(CASE
      WHEN jsonb_typeof(old_r->'payload'->'recordDraft'->'segments')='array' THEN old_r->'payload'->'recordDraft'->'segments'
      ELSE '[]'::jsonb END) s WHERE (s - ARRAY['memoryEnabled','todoEnabled','draftId']::text[])
       IS NOT DISTINCT FROM (segment - ARRAY['memoryEnabled','todoEnabled','draftId']::text[])) INTO changed;
   ELSE
    changed:=(segment - ARRAY['memoryEnabled','todoEnabled','draftId']::text[])
     IS DISTINCT FROM (old_segment - ARRAY['memoryEnabled','todoEnabled','draftId']::text[]);
   END IF;
   IF changed AND nullif(btrim(segment->>'text'),'') IS NOT NULL THEN
    PERFORM achievement_observe(u,'memory.draft_edited',subject,segment->>'draftId');
   END IF;
  END LOOP;
 ELSIF TG_TABLE_NAME='todo_registry' AND r->>'deleted_at' IS NULL THEN
  IF TG_OP='INSERT' AND r->>'origin_kind'='direct' AND nullif(btrim(r->>'notes'),'') IS NOT NULL THEN
   PERFORM achievement_observe(u,'todo.created',subject);
  ELSIF TG_OP='UPDATE' AND (r->>'title' IS DISTINCT FROM old_r->>'title'
   OR r->>'notes' IS DISTINCT FROM old_r->>'notes' OR r->>'current_status' IS DISTINCT FROM old_r->>'current_status'
   OR r->>'due_at' IS DISTINCT FROM old_r->>'due_at') THEN
   PERFORM achievement_observe(u,'todo.edited',subject);
  END IF;
 ELSIF TG_TABLE_NAME='growth_axes' AND TG_OP='UPDATE'
  AND (r->>'name' IS DISTINCT FROM old_r->>'name' OR r->>'description' IS DISTINCT FROM old_r->>'description')
  AND nullif(btrim(r->>'name'),'') IS NOT NULL AND nullif(btrim(r->>'description'),'') IS NOT NULL
  AND NOT EXISTS(SELECT 1 FROM growth_entries WHERE user_id=u AND dimension_id=r->>'dimension_id' AND points>0) THEN
  PERFORM achievement_observe(u,'growth.named',r->>'dimension_id');
 ELSIF TG_TABLE_NAME='growth_entries' AND (r->>'points')::integer>0
  AND (TG_OP='INSERT' OR coalesce((old_r->>'points')::integer,0)<=0) THEN
  PERFORM achievement_observe(u,'growth.scored',subject,r->>'dimension_id','','',
    CASE WHEN TG_OP='INSERT' THEN coalesce((r->>'created_at')::timestamptz,now()) ELSE now() END);
 ELSIF TG_TABLE_NAME='user_settings' AND TG_OP='UPDATE' THEN
  IF r->>'default_record_mode' IS DISTINCT FROM old_r->>'default_record_mode' THEN
   PERFORM achievement_observe(u,'settings.saved','record','','',r->>'default_record_mode'); END IF;
  IF r->>'rag_half_life' IS DISTINCT FROM old_r->>'rag_half_life' THEN
   PERFORM achievement_observe(u,'settings.saved','memory','','',r->>'rag_half_life'); END IF;
  IF r->>'mirror_lookback' IS DISTINCT FROM old_r->>'mirror_lookback' THEN
   PERFORM achievement_observe(u,'settings.saved','lookback','','',r->>'mirror_lookback'); END IF;
 ELSIF TG_TABLE_NAME='agent_runs' AND r->>'mode'='live' AND r->>'status'='completed'
  AND old_r->>'status' IS DISTINCT FROM 'completed' THEN
  message_id:=(r->>'assistant_message_id')::bigint;
  IF EXISTS(SELECT 1 FROM conversation_history h WHERE h.id=message_id AND h.user_id=u AND h.role='assistant'
    AND h.session_id=(r->>'session_id')::uuid AND NOT h.is_fallback AND btrim(h.content)<>'') THEN
   PERFORM achievement_observe(u,'chat.reply_completed',message_id::text,r->>'session_id',
    r->>'achievement_model_profile_id',subject,coalesce((r->>'finished_at')::timestamptz,now()));
   IF r->>'achievement_model_profile_id' IS NOT NULL THEN
    PERFORM achievement_observe(u,'chat.model_completed',subject,r->>'achievement_model_profile_id','','',
     coalesce((r->>'finished_at')::timestamptz,now()));
   END IF;
   IF EXISTS(SELECT 1 FROM conversation_history h CROSS JOIN LATERAL jsonb_array_elements(
     CASE WHEN jsonb_typeof(h.sources)='array' THEN h.sources ELSE '[]'::jsonb END) s
     JOIN records rec ON rec.id::text=coalesce(s->>'record_id',s->>'recordId') AND rec.user_id=u
      AND rec.source='user' AND rec.deleted_at IS NULL WHERE h.id=message_id) THEN
    PERFORM achievement_observe(u,'chat.memory_completed',message_id::text,r->>'session_id',
     r->>'achievement_model_profile_id',subject,coalesce((r->>'finished_at')::timestamptz,now()));
    IF coalesce((r->'achievement_settings'->>'chatVerified')::boolean,false)
      AND coalesce((r->'achievement_settings'->>'embeddingVerified')::boolean,false) THEN
     PERFORM achievement_observe(u,'chat.memory_configured',message_id::text,r->>'session_id',
      r->>'achievement_model_profile_id',subject,coalesce((r->>'finished_at')::timestamptz,now()));
    END IF;
    IF r->'achievement_settings'->>'memory' IS NOT NULL THEN
     PERFORM achievement_observe(u,'settings.used','memory','','',r->'achievement_settings'->>'memory',
      coalesce((r->>'finished_at')::timestamptz,now()));
    END IF;
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['records','chunks','user_terms','todo_registry','growth_axes','growth_entries','user_settings','agent_runs','assistant_actions'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS achievement_progress_changed ON %I',relation);
  EXECUTE format('CREATE TRIGGER achievement_progress_changed AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION achievement_progress_change()',relation);
 END LOOP;
END $$;

-- Existing successful runs prove references; historical viewing/editing is not inferred.
INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,occurred_at,day_key)
 SELECT user_id,'growth.scored',id::text,dimension_id,created_at,(created_at AT TIME ZONE 'Asia/Shanghai')::date
 FROM growth_entries WHERE points>0 ON CONFLICT DO NOTHING;
INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,context_id,value,occurred_at,day_key)
 SELECT a.user_id,'chat.reply_completed',a.assistant_message_id::text,a.session_id::text,
 coalesce(a.achievement_model_profile_id::text,''),a.run_id::text,coalesce(a.finished_at,a.started_at),
 (coalesce(a.finished_at,a.started_at) AT TIME ZONE 'Asia/Shanghai')::date
 FROM agent_runs a JOIN conversation_history h ON h.id=a.assistant_message_id AND h.user_id=a.user_id
 WHERE a.mode='live' AND a.status='completed' AND h.role='assistant' AND NOT h.is_fallback AND btrim(h.content)<>''
 ON CONFLICT DO NOTHING;
INSERT INTO user_achievement_activity(user_id,kind,subject_id,related_id,context_id,value,occurred_at,day_key)
 SELECT a.user_id,'chat.memory_completed',a.assistant_message_id::text,a.session_id::text,
 coalesce(a.achievement_model_profile_id::text,''),a.run_id::text,coalesce(a.finished_at,a.started_at),
 (coalesce(a.finished_at,a.started_at) AT TIME ZONE 'Asia/Shanghai')::date
 FROM agent_runs a JOIN conversation_history h ON h.id=a.assistant_message_id AND h.user_id=a.user_id
 WHERE a.mode='live' AND a.status='completed' AND h.role='assistant' AND NOT h.is_fallback AND btrim(h.content)<>''
 AND EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(h.sources)='array' THEN h.sources ELSE '[]'::jsonb END) s
  JOIN records rec ON rec.id::text=coalesce(s->>'record_id',s->>'recordId') AND rec.user_id=a.user_id
   AND rec.source='user' AND rec.deleted_at IS NULL) ON CONFLICT DO NOTHING;
INSERT INTO achievement_event(user_id,event_key,kind)
 SELECT id,'v17:bootstrap:'||id,'catalog.reconcile' FROM users ON CONFLICT DO NOTHING;
COMMENT ON TABLE user_achievement_activity IS 'Validated structured observations; IDs, categories, timestamps only; no diary body, prompts or credentials';
