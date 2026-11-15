#!/usr/bin/env bash
# Repland 后端发布：本机 Gradle 构建 → scp → pm2 原子替换重启 → nginx 子路径反代。
# 在 Git Bash / Linux 下运行：bash server/deploy/deploy.sh
# 首次部署前先按 deploy.env.example 在服务器写好 <app 目录>/deploy.env（chmod 600）。
set -euo pipefail

KEY="${KEY:-$HOME/.ssh/id_ed25519_tencent}"
REMOTE="${REMOTE:-ubuntu@82.156.121.34}"
APP_DIR="/home/ubuntu/repland-server"
SITE="/etc/nginx/sites-enabled/ilovemxt"
SNIPPET=/etc/nginx/snippets/repland.conf
PUBLIC=https://ilovemxt.xyz/repland
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$HERE")"

echo "▶ 构建 installDist"
( cd "$SERVER_DIR" && "${GRADLE:-gradle}" installDist --console=plain )

echo "▶ 上传产物"
# 固定文件名：远端解压步骤按 /tmp/repland-dist.tgz 取包，mktemp 随机名会让两边对不上
TAR=/tmp/repland-dist.tgz
trap 'rm -f "$TAR"' EXIT
tar czf "$TAR" -C "$SERVER_DIR/build/install/repland-server" .
scp -i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o IdentitiesOnly=yes \
  "$TAR" "$HERE/ecosystem.config.js" "$HERE/nginx-repland.conf" "$REMOTE:/tmp/"

echo "▶ 解压到暂存目录并原子替换（保留 deploy.env 与 config/ 运行态）"
ssh -i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o IdentitiesOnly=yes \
  "$REMOTE" env APP_DIR="$APP_DIR" bash /dev/stdin <<'REMOTE_DEPLOY'
set -euo pipefail
test -f "$APP_DIR/deploy.env" || { echo "缺少 $APP_DIR/deploy.env —— 先按 deploy.env.example 创建并 chmod 600"; exit 1; }
STAGE="$APP_DIR.stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
tar xzf /tmp/repland-dist.tgz -C "$STAGE"
cp -f "$APP_DIR/deploy.env" "$STAGE/deploy.env"
cp -f /tmp/ecosystem.config.js "$STAGE/ecosystem.config.js"
mkdir -p "$STAGE/config"
# 运行态（auth-db / 队伍快照 / 台账）跨版本继承，不能被构建产物覆盖掉
if [ -d "$APP_DIR/config" ]; then cp -a "$APP_DIR/config/." "$STAGE/config/"; fi
rm -rf "$APP_DIR.prev"
mv "$APP_DIR" "$APP_DIR.prev"
mv "$STAGE" "$APP_DIR"
pm2 delete repland-server >/dev/null 2>&1 || true
pm2 start "$APP_DIR/ecosystem.config.js"
pm2 save
rm -rf "$APP_DIR.prev"
REMOTE_DEPLOY

echo "▶ nginx（只占 /repland/ 前缀，首次才改主配置）"
ssh -i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o IdentitiesOnly=yes \
  "$REMOTE" bash /dev/stdin <<REMOTE_NGINX
set -euo pipefail
if [ ! -f "$SNIPPET" ]; then
  sudo cp /tmp/nginx-repland.conf "$SNIPPET"
  sudo cp "$SITE" "$SITE.bak.\$(date +%Y%m%d%H%M%S)"
  sudo sed -i "s|^    include /etc/nginx/snippets/lamp.conf;|&\n    include $SNIPPET;|" "$SITE"
fi
sudo nginx -t
sudo systemctl reload nginx
REMOTE_NGINX

echo "▶ 公网自检"
sleep 2
curl -fsS "$PUBLIC/health"; echo
echo "✅ $PUBLIC"
