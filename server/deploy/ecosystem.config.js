// Repland 后端 pm2 入口。
// 环境变量只走这里的 env 块 —— Next.js/Ktor 之类的常驻进程用 start.sh export 或
// `VAR=x pm2 start` 内联都拿不到 env（踩过），pm2 会把 env 块真正写进进程环境。
// 真实取值在同目录 deploy.env（0600，永不进仓库）。
const fs = require('fs');
const path = require('path');

const env = {};
// 兼容 CRLF：JS 的 `.` 不匹配 \r，用 /\r?\n/ 切行，否则从 Windows 传上来的配置一行都解析不到
for (const line of fs.readFileSync(path.join(__dirname, 'deploy.env'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)$/);
  if (m) env[m[1]] = m[2].trim().replace(/^["']|["']$/g, '');
}

module.exports = {
  apps: [{
    name: 'repland-server',
    script: path.join(__dirname, 'bin', 'repland-server'),
    interpreter: 'none',          // 直接跑 Gradle 生成的 shell 启动脚本
    cwd: __dirname,               // config/ 相对路径以此为根
    exec_mode: 'fork',
    autorestart: true,
    env: Object.assign({ JAVA_OPTS: '-Xms32m -Xmx320m' }, env),
  }],
};
