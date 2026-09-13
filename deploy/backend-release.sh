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
ENV_FILE="$MIRROR_HOME/shared/backend.env"

log() { printf '\n\033[1;34m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
[[ -f "$ENV_FILE" ]] || die "缺 $ENV_FILE —— 先跑 deploy/init-server.sh 并填密钥"
JAR="$(ls -1 target/*.jar 2>/dev/null | grep -v '\.original$' | head -n1 || true)"
[[ -n "$JAR" ]] || die "target/ 下没有可执行 jar，先执行 ./mvnw -B package"

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
if [[ -f "$MIGRATION" ]]; then
  # 只取需要的变量，避免 source 整个文件时被特殊字符坑到
  DB_URL_V="$(grep -E '^DB_URL='          "$ENV_FILE" | head -n1 | cut -d= -f2-)"
  DB_USER_V="$(grep -E '^DB_USERNAME='    "$ENV_FILE" | head -n1 | cut -d= -f2-)"
  DB_PASS_V="$(grep -E '^DB_PASSWORD='    "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '"'"'"'')"
  [[ -n "$DB_URL_V" ]] || die "$ENV_FILE 里没读到 DB_URL"
  export PGPASSWORD="$DB_PASS_V"
  PG_URI="${DB_URL_V#jdbc:}"

  mkdir -p "$MIRROR_HOME/shared/backup"
  BACKUP="$MIRROR_HOME/shared/backup/pre-migrate-$(date '+%Y%m%d-%H%M%S').dump"
  pg_dump "$PG_URI" -U "$DB_USER_V" -Fc -f "$BACKUP" \
    || die "数据库备份失败 —— 已中止发布（绝不在没有备份的情况下迁移）"
  log "数据库已备份：$(basename "$BACKUP")"

  psql "$PG_URI" -U "$DB_USER_V" -v ON_ERROR_STOP=1 -q -f "$MIGRATION" >/dev/null \
    || die "迁移执行失败 —— 已中止发布，代码未切换、服务未重启"
  log "迁移已重放：$MIGRATION（幂等脚本，重放无害）"
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
