# 崩溃/异常上报可选方案对比（隐私优先）

更新：2026-11-03（Asia/Shanghai）。

> **交付性质说明**：本文是方案对比与接入清单，**未实际引入任何依赖**，未修改主代码与构建文件。

## 1. 硬约束

- 项目定位是本地优先（offline-first）MVP：**不得把用户数据外传给第三方云**（见
  docs/PRIVACY.md、docs/EXTENSION_READINESS.md）。
- 当前 `app/build.gradle.kts` 的 `verifyOfflineMvpBoundary` 门禁**禁止**主源码出现
  `android.permission.INTERNET`、`HttpURLConnection`、`OkHttpClient`、`Retrofit.Builder`。
  任何崩溃上报方案都会与它冲突——落地前必须先完成 EXTENSION_READINESS.md 的后端、隐私
  与授权门槛，然后**显式调整该门禁**（例如把上报模块隔离到独立 source set / 单独 allowlist），
  而不是绕过它。这是接入崩溃上报的第一道前置条件。

## 2. 方案对比

| 维度 | ACRA（自建端点/邮件） | Sentry self-hosted | 第三方 SaaS（Sentry Cloud / CrashClip 类） |
| ---- | ---- | ---- | ---- |
| 数据去向 | 自己的服务器/邮箱，完全可控 | 自己运维的服务器（AGPLv3 服务端） | 第三方云——**违反本项目硬约束，排除** |
| SDK 体积/侵入 | 小，纯 Android，无传递云依赖 | 中（sentry-android SDK + native） | 中 |
| 运维成本 | 低（一个 HTTP 接口即可起步） | 高（Docker Compose 全家桶 + 升级） | 无 |
| 聚合/看板 | 自带无 UI，需配 Acralyzer 或自建查询 | 功能完整（issue 聚合、release 追踪） | 功能完整 |
| 许可证 | Apache-2.0 | SDK 宽松；服务端 AGPLv3 | 商业条款，注意数据条款 |
| 适合阶段 | 现在的规模（QA + 封闭内测） | 用户量起来、需要完整 issue 工作流时 | 不可用（隐私约束） |

说明：CrashClip 一类工具归类为「第三方 SaaS/云中转」，与「用户数据不出第三方云」冲突，
仅当其支持完全自托管/本地模式时才可再评估；当前一律排除。

## 3. 推荐

- **第一阶段：ACRA + 自建 HTTP 端点**。改动最小、隐私完全可控；端点可以只做
  「接收 → 落库 → 去重计数」，不依赖重基础设施。
- **第二阶段（可选）**：需要 issue 聚合、release 对比时，评估迁移到 **Sentry self-hosted**
  （数据仍不出自己的服务器）。ACRA 也可以转发为 Sentry 兼容格式，迁移路径平滑。

## 4. 接入清单（将来执行；现在不做）

1. **前置**：按 §1 处理 `verifyOfflineMvpBoundary`（先过 EXTENSION_READINESS.md 门槛）。
2. 依赖（版本以最新稳定为准，仅文档说明）：
   `implementation("ch.acra:acra-http:5.12.0")`（ACRA 方案）。
3. 初始化：`ReplandApplication.onCreate()` 中配置 `ACRA.init()`，`reportUri` 指向自建端点；
   仅在 `release`/`internal` 构建启用，`debug` 不上报。
4. 权限：需要 `INTERNET`（Manifest 声明 + 门禁调整同上）。
5. **数据最小化**（隐私红线）：只采集堆栈、异常类名、设备型号/OS 版本/App 版本与
   `BUILD_SOURCE`（已内建的 12 位源码修订）；**不采集**用户任务内容、计划、画像、任何
   本地数据文件。自定义报告字段只加白名单字段。
6. 用户控制：设置页提供崩溃上报开关，默认关闭；首次开启前给出明示告知（对齐 docs/PRIVACY.md 的表述）。
7. 端点侧：无鉴权简单限流（按 IP 去重），磁盘留存策略与 PRIVACY.md 一致。
8. 验证：手动触发一次测试异常，确认端点收到、字段符合白名单、debug 构建无任何外发请求。
