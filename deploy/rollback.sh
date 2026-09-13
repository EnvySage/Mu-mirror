#!/usr/bin/env bash
# 手动回滚：把 current 指回上一个（或指定的）release 并重启
#
#   bash deploy/rollback.sh backend              # 回滚到上一个后端版本
#   bash deploy/rollback.sh backend 20260913-101500   # 回到指定版本
#   bash deploy/rollback.sh frontend             # 前端同理（只切链，不用重启任何进程）
#   bash deploy/rollback.sh list backend         # 看看现有哪些版本
#
# 注意：回滚的是代码，不回滚数据库。若新版本带了不可逆的迁移，需要手工处理数据。
set -euo pipefail

TARGET="${1:?用法: rollback.sh backend|frontend|ai [release|list]}"
ARG="${2:-}"
MIRROR_HOME="${MIRROR_HOME:-/opt/mirror}"

case "$TARGET" in
  backend)  RELEASES="$MIRROR_HOME/backend/releases";  SERVICE="mirror-backend";  HEALTH="http://127.0.0.1:10002/api/auth/status" ;;
  frontend) RELEASES="$MIRROR_HOME/frontend/releases"; SERVICE="" ;;
  *) echo "未知目标：$TARGET（只支持 backend | frontend）" >&2; exit 1 ;;
esac

[[ -d "$RELEASES" ]] || { echo "没有 releases 目录：$RELEASES" >&2; exit 1; }

if [[ "$ARG" == "list" ]]; then
  echo "$RELEASES 下的版本（新 → 旧）："
  ls -1dt "$RELEASES"/*/ 2>/dev/null | xargs -r -n1 basename
  exit 0
fi

CURRENT="$(dirname "$RELEASES")/current"

# 目标版本：显式指定优先，否则取"当前版本的上一版"
if [[ -n "$ARG" ]]; then
  DEST="$RELEASES/$ARG"
  [[ -d "$DEST" ]] || { echo "版本不存在：$DEST" >&2; exit 1; }
else
  CUR="$(readlink -f "$CURRENT" 2>/dev/null || true)"
  DEST="$(ls -1dt "$RELEASES"/*/ 2>/dev/null | sed 's:/$::' | grep -v "^$CUR$" | head -n1 || true)"
  [[ -n "$DEST" ]] || { echo "找不到可回滚的历史版本" >&2; exit 1; }
fi

echo "回滚 $TARGET：$(basename "$(readlink -f "$CURRENT" 2>/dev/null || echo '（无）')") → $(basename "$DEST")"
ln -sfn "$DEST" "$CURRENT"

if [[ -n "$SERVICE" ]]; then
  sudo systemctl restart "$SERVICE"
  for _ in $(seq 1 30); do
    curl -fsS -m 3 "$HEALTH" >/dev/null 2>&1 && { echo "✓ 回滚完成且探活通过"; exit 0; }
    sleep 2
  done
  echo "✗ 回滚后探活仍失败，请查 /opt/mirror/shared/logs/" >&2
  exit 1
fi

echo "✓ 回滚完成（前端是静态文件，无需重启进程，用户刷新即生效）"
