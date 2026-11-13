#!/usr/bin/env bash
# 后端发布（在 Jenkins 的 workspace 里执行，Jenkins 与后端同机）
#
#   1. jar 落到 releases/<时间戳>/app.jar
#   2. 备份数据库 + 重放幂等迁移（数据库是唯一不能回滚的东西，必须先 dump）
#   3. current 软链原子切换（切换瞬间不会出现半截文件）
#   4. 重启 + 探活 /api/auth/status（白名单接口，无需引 actuator）
#   5. 探活失败 → 自动切回上一版并重启
#   6. 只保留最近 N 个 release / N 份备份
#
# 用法：bash deploy/backend-release.sh
set -euo pipefail

MIRROR_HOME="${MIRROR_HOME:-/opt/mirror}"
SERVICE="${BACKEND_SERVICE:-mirror-backend}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:10002/api/auth/status}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"      # 30 × 2s = 最长等 60s
KEEP_RELEASES="${KEEP_RELEASES:-5}"
KEEP_BACKUPS="${KEEP_BACKUPS:-10}"
MIGRATION="${MIGRATION:-src/main/resources/db/migration-v2.sql}"
RECORD_CATEGORY_MIGRATION="${RECORD_CATEGORY_MIGRATION:-src/main/resources/db/migration-v13-plan-to-todo.sql}"
RECORD_SELECTIVE_INDEX_MIGRATION="${RECORD_SELECTIVE_INDEX_MIGRATION:-src/main/resources/db/migration-v18-record-selective-index.sql}"
LEDGER_MIGRATION="src/main/resources/db/migration-v19-ledger.sql"
LEDGER_PLANNING_MIGRATION="src/main/resources/db/migration-v20-ledger-planning.sql"
ENV_FILE="$MIRROR_HOME/shared/backend.env"

log() { printf '\n\033[1;34m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
[[ -f "$LEDGER_MIGRATION" && -f "$LEDGER_PLANNING_MIGRATION" ]] || die "缺少记账 v19/v20 迁移"
[[ -f "$ENV_FILE" ]] || die "缺 $ENV_FILE —— 先跑 deploy/init-server.sh 并填密钥"
JAR="$(ls -1 target/*.jar 2>/dev/null | grep -v '\.original$' | head -n1 || true)"
[[ -n "$JAR" ]] || die "target/ 下没有可执行 jar，先执行 ./mvnw -B package"
[[ -f "$RECORD_CATEGORY_MIGRATION" ]] || die "缺少记录分类迁移 $RECORD_CATEGORY_MIGRATION，不能发布不完整版本"
[[ -f "$RECORD_SELECTIVE_INDEX_MIGRATION" ]] || die "缺少选择性索引迁移 $RECORD_SELECTIVE_INDEX_MIGRATION，不能发布不完整版本"

RELEASE_DIR="$MIRROR_HOME/backend/releases/$(date '+%Y%m%d-%H%M%S')"
CURRENT="$MIRROR_HOME/backend/current"
PREV=""
[[ -L "$CURRENT" ]] && PREV="$(readlink -f "$CURRENT")"

# ---------- 1. 产物就位 ----------
mkdir -p "$RELEASE_DIR"
cp "$JAR" "$RELEASE_DIR/app.jar"
chown -R mirror:mirror "$RELEASE_DIR" 2>/dev/null || true
log "产物就位：$RELEASE_DIR/app.jar（$(du -h "$RELEASE_DIR/app.jar" | cut -f1)）"

# ---------- 2. 备份 + 迁移 ----------
if [[ -f "$MIGRATION" || -f "$RECORD_CATEGORY_MIGRATION" || -f "$RECORD_SELECTIVE_INDEX_MIGRATION" ]]; then
  # 只取需要的变量，避免 source 整个文件时被特殊字符坑到
  DB_URL_V="$(grep -E '^DB_URL='          "$ENV_FILE" | head -n1 | cut -d= -f2-)"
  DB_USER_V="$(grep -E '^DB_USERNAME='    "$ENV_FILE" | head -n1 | cut -d= -f2-)"
  DB_PASS_V="$(grep -E '^DB_PASSWORD='    "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '"'"'"'')"
  [[ -n "$DB_URL_V" ]] || die "$ENV_FILE 里没读到 DB_URL"
  export PGPASSWORD="$DB_PASS_V"
  PG_URI="${DB_URL_V#jdbc:}"

  # 检查本次发布所需的完整 v17 结构面；没有迁移台账，不能据此证明历史执行顺序。
  # 不重放 v11：v18 追加视图列后，旧定义的 CREATE OR REPLACE 会尝试删列。
  BASELINE_READY="$(psql "$PG_URI" -U "$DB_USER_V" -v ON_ERROR_STOP=1 -Atqc "
    WITH required_tables(name) AS (VALUES
      ('public.records'),('public.chunks'),('public.users'),('public.user_achievement'),
      ('public.user_achievement_activity'),('public.agent_runs'),('public.achievement_event'),
      ('public.user_settings'),('public.user_model_profiles'),('public.user_activity_fact'),
      ('public.growth_axes'),('public.growth_entries'),('public.assistant_actions'),
      ('public.user_terms'),('public.todo_registry'),('public.conversation_history'),
      ('public.chat_sessions'),('public.chat_source_exclusions'),('public.profile_snapshots'),
      ('public.vault_items'),('public.tool_calls'),('public.agent_command_receipts')
    ), required_columns(relation,name,type) AS (VALUES
      ('public.records','id','bigint'),('public.records','user_id','uuid'),
      ('public.records','content','text'),('public.records','source','character varying(20)'),
      ('public.records','status','character varying(20)'),('public.records','user_reviewed','boolean'),
      ('public.records','ai_processing_authorized','boolean'),('public.records','processing_version','integer'),
      ('public.records','created_at','timestamp with time zone'),('public.records','deleted_at','timestamp with time zone'),
      ('public.records','memory_state','character varying(16)'),('public.records','achievement_default_mode','text'),
      ('public.chunks','id','bigint'),('public.chunks','record_id','bigint'),
      ('public.chunks','user_id','uuid'),('public.chunks','content','text'),
      ('public.chunks','segment','text'),('public.chunks','metadata','jsonb'),
      ('public.chunks','embedding','vector(1024)'),('public.chunks','ai_memory_approved','boolean'),
      ('public.chunks','embedding_status','character varying(24)'),
      ('public.user_achievement','plated_at','timestamp with time zone'),
      ('public.user_achievement','plating_rule_version','integer'),
      ('public.user_achievement','plating_acknowledged_at','timestamp with time zone'),
      ('public.agent_runs','achievement_settings','jsonb'),
      ('public.user_achievement_activity','id','bigint'),('public.user_achievement_activity','user_id','uuid'),
      ('public.user_achievement_activity','kind','text'),('public.user_achievement_activity','subject_id','text'),
      ('public.user_achievement_activity','related_id','text'),('public.user_achievement_activity','context_id','text'),
      ('public.user_achievement_activity','value','text'),('public.user_achievement_activity','occurred_at','timestamp with time zone'),
      ('public.user_achievement_activity','day_key','date')
    ), required_functions(signature,result) AS (VALUES
      ('public.record_memory_is_complete(bigint)','boolean'),
      ('public.achievement_observe(uuid,text,text,text,text,text,timestamptz)','void'),
      ('public.achievement_observation_changed()','trigger'),
      ('public.achievement_connection_key(uuid,text)','text'),
      ('public.achievement_capture_model()','trigger'),
      ('public.achievement_capture_record_mode()','trigger'),
      ('public.achievement_progress_change()','trigger')
    ), required_indexes(name,relation) AS (VALUES
      ('public.user_achievement_plating_pending','public.user_achievement'),
      ('public.achievement_activity_user_kind','public.user_achievement_activity')
    ), required_triggers(relation,name,signature) AS (
      VALUES ('public.user_achievement_activity','achievement_observed','public.achievement_observation_changed()'),
        ('public.records','achievement_record_mode','public.achievement_capture_record_mode()'),
        ('public.agent_runs','achievement_run_model','public.achievement_capture_model()')
      UNION ALL SELECT relation,'achievement_progress_changed','public.achievement_progress_change()'
        FROM (VALUES ('public.records'),('public.chunks'),('public.user_terms'),('public.todo_registry'),
          ('public.growth_axes'),('public.growth_entries'),('public.user_settings'),('public.agent_runs'),('public.assistant_actions')) AS t(relation)
    ), required_views(name,columns) AS (VALUES
      ('mirror_ai.record_chunks',ARRAY['chunk_id','record_id','user_id','segment','metadata','embedding','created_at']),
      ('mirror_ai.glossary_terms',ARRAY['id','user_id','term','aliases','description','query_hit_count','last_confirmed_at']),
      ('mirror_ai.chat_history',ARRAY['id','user_id','session_id','role','content','is_fallback','created_at']),
      ('mirror_ai.profiles',ARRAY['id','user_id','snapshot_type','period_month','mood_analysis','learning_analysis','todo_analysis','rhythm_analysis','user_tags','overall_summary','embedding','created_at']),
      ('mirror_ai.vault_metadata',ARRAY['id','user_id','original_name','mime','size_bytes','category','description','digest_status','created_at']),
      ('mirror_ai.vault_content',ARRAY['chunk_id','record_id','vault_item_id','user_id','segment','metadata','embedding','created_at']),
      ('mirror_ai.vault_keys',ARRAY['chunk_id','record_id','vault_item_id','user_id','segment','metadata','embedding','created_at']),
      ('mirror_ai.todos',ARRAY['id','user_id','title','current_status','source_chunk_id','record_id','created_at'])
    )
    SELECT NOT EXISTS (
      SELECT 1 FROM required_tables r WHERE NOT EXISTS (
        SELECT 1 FROM pg_class c WHERE c.oid=to_regclass(r.name) AND c.relkind IN ('r','p'))
    ) AND NOT EXISTS (
      SELECT 1 FROM required_columns r WHERE NOT EXISTS (
        SELECT 1 FROM pg_attribute a WHERE a.attrelid=to_regclass(r.relation) AND a.attname=r.name
          AND NOT a.attisdropped AND format_type(a.atttypid,a.atttypmod)=r.type)
    ) AND NOT EXISTS (
      SELECT 1 FROM required_functions r WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p WHERE p.oid=to_regprocedure(r.signature) AND p.prorettype=to_regtype(r.result))
    ) AND NOT EXISTS (
      SELECT 1 FROM required_indexes r WHERE NOT EXISTS (
        SELECT 1 FROM pg_index i WHERE i.indexrelid=to_regclass(r.name)
          AND i.indrelid=to_regclass(r.relation) AND i.indisvalid AND i.indisready)
    ) AND NOT EXISTS (
      SELECT 1 FROM required_triggers r WHERE NOT EXISTS (
        SELECT 1 FROM pg_trigger t WHERE t.tgrelid=to_regclass(r.relation) AND t.tgname=r.name
          AND t.tgfoid=to_regprocedure(r.signature) AND t.tgenabled<>'D' AND NOT t.tgisinternal)
    ) AND NOT EXISTS (
      SELECT 1 FROM required_views r WHERE NOT EXISTS (
        SELECT 1 FROM pg_class c WHERE c.oid=to_regclass(r.name) AND c.relkind='v'
          AND c.reloptions @> ARRAY['security_barrier=true']
          AND (SELECT array_agg(a.attname::text ORDER BY a.attnum) FROM pg_attribute a
            WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped)
          IN (r.columns, CASE WHEN r.name='mirror_ai.record_chunks'
            THEN r.columns||ARRAY['embedding_enabled','embedding_status','index_policy_version'] ELSE r.columns END))
    ) AND (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='mirror_ai' AND c.relkind='v' AND c.relname<>'ledger_cashflows')=8;
  ")" || die "v17 基线检查失败 —— 已中止发布"
  [[ "$BASELINE_READY" == "t" ]] || die "数据库缺少本次发布要求的 v17 结构；核对 docs/REFERENCE.md 的历史迁移与发布前提，再发布"

  mkdir -p "$MIRROR_HOME/shared/backup"
  BACKUP="$MIRROR_HOME/shared/backup/pre-migrate-$(date '+%Y%m%d-%H%M%S').dump"
  pg_dump "$PG_URI" -U "$DB_USER_V" -Fc -f "$BACKUP" \
    || die "数据库备份失败 —— 已中止发布（绝不在没有备份的情况下迁移）"
  log "数据库已备份：$(basename "$BACKUP")"

  if [[ -f "$MIGRATION" ]]; then
    psql "$PG_URI" -U "$DB_USER_V" -v ON_ERROR_STOP=1 -q -f "$MIGRATION" >/dev/null \
      || die "迁移执行失败 —— 已中止发布，代码未切换、服务未重启"
    log "迁移已重放：$MIGRATION（幂等脚本，重放无害）"
  fi
  psql "$PG_URI" -U "$DB_USER_V" -v ON_ERROR_STOP=1 -q -f "$RECORD_CATEGORY_MIGRATION" >/dev/null \
    || die "计划→待办迁移失败 —— 已中止发布，代码未切换、服务未重启"
  log "记录分类已同步：$RECORD_CATEGORY_MIGRATION"
  psql "$PG_URI" -U "$DB_USER_V" --single-transaction -v ON_ERROR_STOP=1 -q -f "$RECORD_SELECTIVE_INDEX_MIGRATION" >/dev/null \
    || die "选择性索引 v18 迁移失败 —— 已中止发布，代码未切换、服务未重启"
  log "选择性索引已同步：$RECORD_SELECTIVE_INDEX_MIGRATION（保留旧数据授权、向量与版本）"
  for ledger_migration in "$LEDGER_MIGRATION" "$LEDGER_PLANNING_MIGRATION"; do
    psql "$PG_URI" -U "$DB_USER_V" --single-transaction -v ON_ERROR_STOP=1 -q -f "$ledger_migration" >/dev/null \
      || die "记账迁移失败 —— 已中止发布"
    log "记账已同步：$ledger_migration"
  done
else
  log "跳过迁移（未找到 $MIGRATION）"
fi

# ---------- 3. 原子切换 + 重启 ----------
ln -sfn "$RELEASE_DIR" "$CURRENT"
log "current → $(basename "$RELEASE_DIR")"
sudo systemctl restart "$SERVICE"

# ---------- 4. 探活 ----------
log "等待服务就绪（最多 $((HEALTH_RETRIES * 2))s）…"
healthy=0
for i in $(seq 1 "$HEALTH_RETRIES"); do
  if curl -fsS -m 3 "$HEALTH_URL" >/dev/null 2>&1; then healthy=1; break; fi
  sleep 2
done

# ---------- 5. 失败自动回滚 ----------
if [[ "$healthy" != "1" ]]; then
  printf '\n\033[1;31m✗ 探活失败（%s 无响应）\033[0m\n' "$HEALTH_URL" >&2
  if [[ -n "$PREV" && -d "$PREV" ]]; then
    ln -sfn "$PREV" "$CURRENT"
    sudo systemctl restart "$SERVICE"
    log "已自动回滚到 $(basename "$PREV")"
  else
    echo "没有上一版可回滚（这是首次发布），请手动排查" >&2
  fi
  echo "查看日志：tail -100 $MIRROR_HOME/shared/logs/backend.err.log" >&2
  exit 1
fi

log "发布成功（$(basename "$RELEASE_DIR")，第 ${i} 次探活通过）"

# ---------- 6. 清理旧版本 ----------
( cd "$MIRROR_HOME/backend/releases" && ls -1dt */ 2>/dev/null | tail -n +$((KEEP_RELEASES + 1)) | xargs -r rm -rf )
( cd "$MIRROR_HOME/shared/backup"   && ls -1dt pre-migrate-*.dump 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f )
log "清理完成（保留最近 $KEEP_RELEASES 个 release / $KEEP_BACKUPS 份备份）"
