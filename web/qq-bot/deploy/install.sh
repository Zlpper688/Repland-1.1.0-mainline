#!/usr/bin/env bash
# Run as root on the server after copying qq-bot/ to /opt/repland-qq.
set -euo pipefail

APP_DIR=/opt/repland-qq
cd "$APP_DIR"

if ! command -v node >/dev/null 2>&1 || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  echo "安装 Node.js 22 ..."
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

[ -f .env ] || { echo "缺少 $APP_DIR/.env，先把本地 qq-bot/.env 拷上来"; exit 1; }

npm install --omit=dev
node --input-type=module -e "await import('./api.mjs'); await import('./notify.mjs'); await import('./weekly-card.mjs'); console.log('模块加载正常')"

install -m 644 deploy/repland-qq.service /etc/systemd/system/repland-qq.service
touch /var/log/repland-qq.log
systemctl daemon-reload
systemctl enable --now repland-qq
sleep 6
systemctl --no-pager --lines=15 status repland-qq || true
