# Repland 后端部署（自托管 Ktor）

线上入口：**`https://ilovemxt.xyz/repland`**（健康检查 `GET /repland/health`）。
App 侧「设置 → 后端连接」里填这个地址即可，无需改代码、无需重建 APK。

## 拓扑

```
App ──443──> nginx /repland/ ──proxy_pass──> 127.0.0.1:3120 (Ktor/Netty, pm2 守护)
```

- 进程只绑回环（`REPLAND_SERVER_HOST=127.0.0.1`），公网唯一入口是 nginx 的 `/repland/` 前缀。
- nginx 的 `proxy_pass http://127.0.0.1:3120/;` 末尾带 `/`，会剥掉 `/repland` 前缀 → 服务端路由保持 `/v1/...` 原样。
- App 用 `baseUrl.trimEnd('/') + "/v1/..."` 拼 URL，所以换域名或加子路径只改配置面那一行。
- TLS 复用 `ilovemxt.xyz` 的 Let's Encrypt 证书：HTTPS 下不需要明文放行（本机 10.0.2.2 调试才需要）。

## 目录与文件

| 路径 | 说明 |
| --- | --- |
| `/home/ubuntu/repland-server/` | 构建产物：`bin/`、`lib/` |
| `/home/ubuntu/repland-server/deploy.env` | **真实配置，0600，永不进仓库** |
| `/home/ubuntu/repland-server/ecosystem.config.js` | pm2 入口（从 `deploy/` 拷来） |
| `/home/ubuntu/repland-server/config/` | 运行态：`auth-db.json`、`teams/`、台账 —— 跨版本继承，别覆盖 |
| `/etc/nginx/snippets/repland.conf` | 反代片段 |

## 首次部署

```bash
# 0) 服务器要有 JRE 21
sudo apt-get install -y openjdk-21-jre-headless

# 1) 建目录并写配置（照 server/deploy/deploy.env.example）
mkdir -p /home/ubuntu/repland-server/config
cp server/deploy/deploy.env.example /home/ubuntu/repland-server/deploy.env
chmod 600 /home/ubuntu/repland-server/deploy.env
#   必填：REPLAND_SMS_ALLOWED_PHONES（内测名单）、REPLAND_AUTH_ADMIN_TOKEN（随机串）

# 2) 构建 + 上传 + pm2 + nginx
bash server/deploy/deploy.sh          # 可重复执行，走原子替换
```

`deploy.sh` 幂等：`deploy.env` 与 `config/` 会被带进新版本目录再整体替换，pm2 先 `delete` 再 `start`（保证 env 重新注入），nginx 只在片段缺失时才动主配置（并留 `.bak-<时间戳>`）。

## 环境变量

| 变量 | 作用 | 缺省 |
| --- | --- | --- |
| `REPLAND_SERVER_HOST` | 监听网卡；公网部署必须是 `127.0.0.1` | `0.0.0.0` |
| `REPLAND_SERVER_PORT` | 监听端口（nginx 上游） | `8080`，线上 `3120` |
| `REPLAND_SMS_ALLOWED_PHONES` | 内测名单，逗号分隔。非空 → 名单外手机号**连验证码都拿不到** | 空 = 不限制 |
| `REPLAND_AUTH_ADMIN_TOKEN` | 管理端口令（`X-Auth-Admin-Token`）：账号清单、微信 complete | 未设 = 这些端点开放 |
| `REPLAND_PUBLIC_BASE_URL` | 自托管微信授权页对外入口 | 未设 = 微信通道禁用 |
| `REPLAND_SMS_WEBHOOK_URL` | 短信网关；设了就不再日志出码 | 未设 = 开发模式 |
| `REPLAND_SMS_DEV_MODE` | 显式 1/0 控制是否回显 `devCode` | 未配网关时 = 开 |
| `WECHAT_APP_ID` / `WECHAT_APP_SECRET` | 公众号网页授权 | 未设 = 微信通道降级 |
| `REPLAND_WECHAT_QR_APP_ID` / `REPLAND_WECHAT_QR_APP_SECRET` / `REPLAND_WECHAT_QR_CALLBACK_URL` | 微信开放平台网站应用扫码登录 | 三项齐全才启用 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `REPLAND_BITABLE_*` | 飞书投递与多维表格副本 | 未设 = 相应通道不启用 |
| `MEM0_API_KEY` | 云记忆远端层 | 未设 = 记忆端点 disabled |

## 安全含义（重要）

- **没有短信网关时验证码直接回显在 HTTP 响应里**（`devCode`）。这是开发模式的便利，也是公网的漏洞：所以 `REPLAND_SMS_ALLOWED_PHONES` 是**上线必配项**，不是可选项。没配时启动日志会 WARN 一条。
- 加真人进名单：改 `deploy.env` 里的 `REPLAND_SMS_ALLOWED_PHONES`（逗号追加），然后 `pm2 restart repland-server`。名单外的号只会收到「这个手机号还没进内测名单，请联系队长加进来」，不会生成任何可校验的码。
- `config/auth-db.json` 存真实手机号与登录 token，`config/teams/` 存队伍快照 —— 都已 `.gitignore`，**永不提交、永不上传公网仓库**。
- 管理端点（账号清单）返回的是脱敏手机号（`138****5678`），且口令错误与缺失同样 403，不区分。

## 验证

```bash
curl -s https://ilovemxt.xyz/repland/health
# {"status":"ok","feishuConfigured":false,"boundAccounts":0}

cd server && python e2e_check.py   # 默认打本机 18080
REPLAND_E2E_BASE=https://ilovemxt.xyz/repland \
REPLAND_E2E_PHONE1=… REPLAND_E2E_PHONE2=… REPLAND_E2E_ADMIN_TOKEN=… \
python e2e_check.py                # 手机号与口令走环境变量，不落命令行
```

`e2e_check.py` 覆盖：名单外拒绝 → 队长注册首推 → 旧 revision 重推 409 回带服务端版 → 未登录 401 → 队员登录拉取/回推 → 队长拉到队员版本（双向）→ 跨队 403 → 登出后 token 立即 401 → 管理端点 403/脱敏清单。

## 日常运维

```bash
pm2 logs repland-server --lines 50     # 日志
pm2 restart repland-server            # 重启（改 deploy.env 后）
ss -tlnp | grep 3120                  # 应只看到 127.0.0.1
```

## 回滚

```bash
# 摘掉公网入口（服务本体不动）
sudo sed -i '\|    include /etc/nginx/snippets/repland.conf;|d' /etc/nginx/sites-enabled/ilovemxt
sudo nginx -t && sudo systemctl reload nginx
# 或整机退回上一版：pm2 delete repland-server；把 *.prev 目录改回原名
```

## 已知边界

- 冲突策略是**服务端赢**：客户端 `baseRevision` 落后就 409 并带回服务端当前版，本地合并后重推。
- 飞书投递、多维表格台账、mem0 云记忆三条通道**尚未配置凭证**，对应端点返回降级状态；短信同理（走 dev 出码）。
- 微信登录需要公众号凭证 + `REPLAND_PUBLIC_BASE_URL` 三者齐备，现在 `start` 返回「微信登录未启用」。
