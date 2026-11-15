# Repland

Repland 1.1.0 主线源码仓库，包含 Android 整合工程、Ktor 服务端与 Web 体验版。基线来自 2026-11-02 的组员 1.1.0 QA 交付，随后合入本地规则层、协议守卫与最新 Web 功能。[1.1.0 QA 交付说明](docs/RELEASE_1.1.0_QA.md)记录了登录、Windows 启动与测试范围。[Android CI](.github/workflows/android.yml)针对 `android/` 整合工程运行。

Android 本地优先的个人任务规划与执行辅助工具：用一句话记录事项，在已设置的时间约束中安排，依据明确进度维护剩余计划，并保留解释与撤销。

源码版本 **1.1.0（1110）**。2026-10-28 以本地 0.1.35 QA 线为主干，嫁接 GitHub `main` 的团队协作、数值画像与自主助手模块，Room schema 逐版本迁移至第 30 版；具体改动与验证见 [工程边界](docs/EXTENSION_READINESS.md) 与 [团队协作设计](docs/TEAM_COLLAB_DESIGN.md)。产品规则统一见 [主思路](REPLAND_IDEA.md)。

## 仓库内容

- **Android**：1.1.0 QA 基线（手机号验证码 + 微信扫码登录）叠加规则层 `RuleIntentParser` / `AgentRulePlanner`，离线也能把一句话落到既有工具调用；在线时仍由模型负责理解与检验。
- **Ktor 服务端**：账号、团队快照、微信二维码授权回调；配置见 [server/DEPLOY.md](server/DEPLOY.md)。
- **Web**：体验版前端、Edge Functions（含规则层 `functions/rule-parser.mjs`）、QQ 机器人与 `dev/matter-lab` 评测脚本。
- **不包含**：旧仓库的 handover 压缩包、APK、`.env`、QQ 机器人运行数据、第三方竞品页面抓取素材。密钥只放在部署环境的 Secret 中；仓库内没有可用的团队码或访问码默认值。


## 当前体验

- 底部为日程、任务、团队、我的，保留柔和多色视觉。
- 日程右下角打开“一句话处理”，支持新增、安排、明确进度、查询及受保护的最近整次操作撤销。
- 自动安排可开启或暂停，只在 App 打开及返回前台等受支持时机维护，不承诺关闭 App 后持续实时重排。
- “我的”展示头像、用户名和自动的近期使用总结；其余项目集中到设置。
- 无模型也可使用本地任务、时间约束、基础排程、执行、提醒与导出。远程 AI 依实际构建、授权和配置启用。
- 日程中的“预览任务安排”调用本地六角色规划团队，查看工具日志后确认应用；复习录入与手动打分链路已移除，记忆证据只来自专注时长，可选记忆参考默认关闭，只影响放置时段。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [整合交接](docs/INTEGRATION_2026-10-22.md) | 三份来源、统一工程、构建与验证结果。 |
| [项目介绍](PROJECT_INTRODUCTION.md) | 背景、意义、技术方案与多层 Agent 协同创新点。 |
| [REPLAND_IDEA.md](REPLAND_IDEA.md) | 产品主思路、当前能力、生效规则与后续路线。 |
| [CONTEXT.md](CONTEXT.md) | 任务、计划、执行、画像与撤销等领域术语。 |
| [当前工程说明](docs/EXTENSION_READINESS.md) | 源码定位、数据保护、AI 构建边界与扩展要求。 |
| [隐私](docs/PRIVACY.md) | 本地数据、AI 发送范围、密钥、导出与清空。 |
| [测试](docs/TESTING.md) · [发布](docs/RELEASE.md) | 验证方法、历史证据边界与构建身份。 |
| [文档维护与备份](docs/DOCUMENTATION.md) | 本轮清理范围、完整备份、恢复方法及后续维护规则。 |

## 开发

用 Android Studio 打开 `android/`。当前构建使用 JDK 17、SDK / target 36，最低 API 24。在仓库根目录执行：

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest
.\android\gradlew.bat -p android :app:assembleDebug :app:assembleInternal :app:verifyOfflineMvpBoundary
.\android\gradlew.bat -p server test
cd web
npm ci
npm run build
npm run test:routes
```

Web 函数与 QQ 机器人需要 `REPLAND_ACCESS_CODE`、`REPLAND_TEAM_CODE`，可选 `AGNES_API_KEY` / `AGNES_BASE_URL` / `AGNES_MODEL`；示例见 `web/.env.example` 与 `web/qq-bot/.env.example`。未配置团队码时团队接口返回 `team_code_not_configured`。

仪器测试仅使用专用测试设备及隔离包。包名、远程能力和发布前检查见 [发布规则](docs/RELEASE.md)。设计副本（`.design` 包）使用标准 Gradle 构建：`.\android\gradlew.bat -p android :app:assembleDesignPreview`。

Windows 可运行 `Build-App.ps1` 构建主应用，连接 Android 设备后双击 `Open-App.cmd` 打开“Repland 当前版”。新脚本自动定位本机 JDK / Gradle，不依赖旧的 `D:\Environment`。多设备时用 `Start-App.ps1 -Serial <设备号>`。

当前整合版验证见 [整合交接](docs/INTEGRATION_2026-10-22.md)。历史验证保存在 [2026-10-18 使用总结验证](docs/verification/automatic-usage-summary-2026-10-18/README.md)和[更早的 0.1.35 技术证据](docs/verification/final-0.1.35/README.md)，各自仅适用于其源码快照。

不要提交密钥、签名材料、`local.properties`、个人数据库或设备备份；已有数据迁移不能使用破坏性重置。
