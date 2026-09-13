#!/usr/bin/env bash
# 一次性服务器准备（需要 sudo）。幂等：可以反复执行。
#
#   bash deploy/init-server.sh
#
# 前提：已装 Java 21（openjdk-21-jre-headless）、nginx；（首次建库还需要 psql 客户端）
set -euo pipefail

MIRROR_HOME="${MIRROR_HOME:-/opt/mirror}"
OPS_SRC="$(cd "$(dirname "$0")" && pwd)"

say() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# ---------- 依赖自检（只警告不阻断，方便分批装） ----------
command -v java  >/dev/null || { echo "✗ 未检测到 java —— Spring Boot 21 需要：apt install openjdk-21-jre-headless" >&2; exit 1; }
command -v nginx >/dev/null || echo "⚠ 未检测到 nginx"
command -v psql  >/dev/null || echo "⚠ 未检测到 psql 客户端（跑迁移/备份需要 postgresql-client）"

say "1/6 创建运行用户"
id -u mirror >/dev/null 2>&1 || sudo useradd -r -m -s /usr/sbin/nologin mirror

say "2/6 创建目录骨架"
sudo mkdir -p "$MIRROR_HOME"/{backend/releases,frontend/releases,ai/app,shared/logs,shared/backup}
sudo chown -R mirror:mirror "$MIRROR_HOME"

say "3/6 生成密钥文件模板"
if [[ ! -f "$MIRROR_HOME/shared/backend.env" ]]; then
  sudo cp "$OPS_SRC/env/backend.env.example" "$MIRROR_HOME/shared/backend.env"
  sudo chown mirror:mirror "$MIRROR_HOME/shared/backend.env"
  sudo chmod 600 "$MIRROR_HOME/shared/backend.env"
  echo "  已生成 $MIRROR_HOME/shared/backend.env —— 发版前必须填真实 DB / JWT_SECRET"
else
  echo "  $MIRROR_HOME/shared/backend.env 已存在，跳过"
fi

say "4/6 安装 systemd unit"
sudo cp "$OPS_SRC/systemd/mirror-backend.service" /etc/systemd/system/
sudo cp "$OPS_SRC/systemd/mirror-ai.service"      /etc/systemd/system/
sudo systemctl daemon-reload
echo "  注意：此刻还不要 enable —— 第一次发版把产物放进去再 systemctl enable --now"

say "5/6 安装 nginx 站点"
if [[ -d /etc/nginx/conf.d ]]; then
  sudo cp "$OPS_SRC/nginx/mirror.conf" /etc/nginx/conf.d/mirror.conf
  echo "  已放入 /etc/nginx/conf.d/mirror.conf —— 改完 server_name 后：sudo nginx -t && sudo systemctl reload nginx"
else
  echo "  ⚠ 没有 /etc/nginx/conf.d，请把 deploy/nginx/mirror.conf 内容并进你的 nginx 主配置"
fi

say "6/6 sudo 白名单"
echo "  执行以下命令（注意先用 visudo -c 自检，写坏 /etc/sudoers.d 会让 sudo 全局失效）："
echo "    sudo cp $OPS_SRC/sudoers/mirror-deploy /etc/sudoers.d/mirror-deploy"
echo "    sudo chmod 440 /etc/sudoers.d/mirror-deploy"
echo "    sudo visudo -c"

cat <<'EOF'

准备完成。接下来：
  1) 填密钥：sudo -u mirror vi /opt/mirror/shared/backend.env
  2) 建库（首次）：createdb mu_mirror && psql mu_mirror -f src/main/resources/db/schema.sql
  3) 装 AI 环境（venv 只装一次，2C2G 必须用 minimal，不能带 torch）：
       sudo -u mirror python3 -m venv /opt/mirror/ai/venv
       sudo -u mirror /opt/mirror/ai/venv/bin/pip install -r requirements-minimal.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
  4) 加 swap（2C2G 本机构建的硬前提，否则 OOM Killer 会杀你的 PG）：
       sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
       sudo mkswap /swapfile && sudo swapon /swapfile
       echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
EOF
