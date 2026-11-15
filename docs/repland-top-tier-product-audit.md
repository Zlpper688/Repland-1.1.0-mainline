# Repland 顶级化产品审计：产品内核 + 正确性

> 审计日期：2026-11-03（Asia/Shanghai）· 审计对象：`D:\repland-restore`（只读，未改任何代码、未构建、未触远程）
> 主干版本：**1.1.0 / 1110**（`android/gradle.properties`），Room schema 实际为 **25**（非传闻的 22）
> 参照副本：`D:\repland-build`（1.0.0 旧版）、`D:\repland-github`、`D:\swan-repland`、`D:\repland`、仓库内 `upstream-design/`（上游设计源码树）
> 测试规模：JVM `@Test` ≈ 246，仪器 `@Test` ≈ 438

---

## 0. 结论先行

**当前真实水位：** Repland 的**数据层与提醒层已经是一流水准**（事务完整、迁移非破坏、精确闹钟 + 重启恢复 + 跨天兜底一应俱全，超过多数同类独立产品），但**元认知闭环在「回顾」与「校准」两步实际断裂**，且闭环最关键的一步——回顾——在主干上**没有可用的用户界面**。产品目前是一个「计划 + 执行 + 记录」三环优秀的执行器，还不是元认知学习闭环产品。

**离顶级差几层：** 差 **2.5 层**。

| 层级 | 定义 | Repland 现状 |
| --- | --- | --- |
| L1 可靠工具 | 事务、提醒、离线、备份可靠 | ✅ 已达，且是强项 |
| L2 闭环产品 | 计划→执行→记录→回顾→校准五步全部有落库、有交互、有反馈 | ⚠️ 前三步达；回顾步界面缺失（只读版被移除、字符串成孤儿）；校准步没有「估时 vs 实际」反馈回路 |
| L3 习惯化仪式 | Sunsama 式每日计划/收工仪式、Things 式低摩擦重排 | ❌ 无每日仪式动线；通知点了进 app 后无落地页 |
| L4 自我校准系统 | 预测-结果-偏差-调整的数据闭环驱动后续规划 | ❌ NumericProfile 已有可信原估/实际投入数据（H01 契约），但从不反馈给用户，也不影响下次估时 |

五条缺陷线索核实结果（详细证据见 §3）：**两条完全成立**（回顾断裂、时间待定静默落库、双开始语义——实际三条成立），**一条已过时被放宽**（部分完成强制），**一条已部分修复**（EventLibrarySheet 时间敏感性已补、空态仍死）。

---

## 1. 元认知闭环逐环节拆解

产品主张闭环：**计划 → 执行 → 记录 → 回顾 → 校准**。

### 1.1 计划（Plan）—— ✅ 基本闭环，唯一「安排助手确认」处有静默落库漏洞

- 自动安排/重排/草稿：[PlanViewModel.kt](../android/app/src/main/java/com/swan1127/repland/ui/plan/PlanViewModel.kt) L88-155、L172-218，草稿经 `planRepository.saveDraft` 落库，确认走计划确认流程。
- 漏洞：**「安排助手」确认一键把所有提案落库**，包括时间未定的。见 §3.2。
- 任务来源治理（inputSources、ACCEPTED_SUGGESTION 标记）设计良好，是同类产品少见的严谨。

### 1.2 执行（Execute）—— ✅ 闭环，但「开始」语义分裂

- 执行会话持久化于 workspace 表（`active-execution-session`，JSON codec version 1），**进程死亡可恢复**（墙钟计算 elapsed）；暂停/恢复/结束全部带事务。
- 双开始语义问题见 §3.5。
- 20-25 分钟番茄上限：`minOf(25 * 60, segmentLength)`（[RoomExecutionSessionRepository.kt](../android/app/src/main/java/com/swan1127/repland/data/room/RoomExecutionSessionRepository.kt) L35、L54）。

### 1.3 记录（Record）—— ✅ 闭环，质量好

- 进度反馈、部分完成、更正（CorrectExecutionLogDialog，ReplandApp.kt L1769-1777）都落 execution_log，带 `nextLogCreatedAt` 单调时序保护（RoomTaskRepository.kt L163）。
- 「部分完成强制百分比+文字」已被放宽：内容可选、百分比可留空=未知（见 §3.3）。
- 执行会话结束时根据 outcome 分流写库（COMPLETED/PARTIAL/SKIPPED/CONTINUE），且对「任务已在别页结束」的场景只追加证据不复活任务（RoomExecutionSessionRepository.kt L79-98 注释明确）。

### 1.4 回顾（Reflect）—— ❌ **闭环断裂最重的一步：界面已从主干消失**

证据链：
1. 上游设计树有完整的 `DailyReviewDialog`（[upstream-design/repland/ui/ReplandApp.kt](../upstream-design/repland/ui/ReplandApp.kt) L2711 起）：展示计划工作段数、待确认工作段、已记录反馈、「下一步建议」文本行，底部只有「跳过回顾」和关闭——**纯只读，任何信息都不能操作**，且自带声明「关闭或跳过不会改变任何任务状态」（strings.xml L41）。
2. 主干 `android/app/src/main` 中：`daily_review_*`、`open_daily_review`、`skip_daily_review` 字符串仍在 strings.xml（L39-48），但**全部成为孤儿**——全源码无任何 composable 引用（仅 upstream-design、docs 归档和 patch 里有）。即：回顾对话框在 1.1.0 主干上被移除，从未被替代。
3. AI 每日总结入口 `PlanningAgentViewModel.beginDailySummary`（[PlanningAgentViewModel.kt](../android/app/src/main/java/com/swan1127/repland/ui/ai/PlanningAgentViewModel.kt) L100）**无任何调用点**，是死代码。
4. 每日回顾**提醒通知仍然会发**（`ReminderKind.DAILY_REVIEW`，[LocalReminderReceiver.kt](../android/app/src/main/java/com/swan1127/repland/reminders/LocalReminderReceiver.kt) L113、L179），但点击通知的 PendingIntent 只是裸 `MainActivity`，**没有 deep link、没有回顾页**——用户被提醒「去回顾」，点进去什么都没有。
5. 「待确认工作段」的通知文案（strings.xml L49-50）承诺「打开任务后，由你确认完成、部分完成或延期」——把回顾的职责完全推给了任务详情页逐个手动处理。

**后果：** 元认知闭环的第四步在主干上不存在。每天产生的「待确认工作段」（结束而无反馈的时段）没有任何聚合界面，只能靠用户自己在任务列表里逐个发现。回顾通知每天提醒一个不存在的能力。

### 1.5 校准（Calibrate）—— ❌ 数据在、回路无

- 已有：局部重排（App 前台/回前台/时间边界触发）、计划历史恢复、单步撤销（带保护检查）、NumericProfile 的 90 天可信原估计/实际投入（docs/features/H01_NUMERIC_CONTRACT.md）。
- 缺失：**「预计 X 分钟 vs 实际 Y 分钟」的偏差从不呈现给用户**，也从不反馈到下一次估时。任务完成后没有一处显示「这次估了 60 分钟实际用了 95 分钟，下次同类别估多少」。估算偏差校准（元认知校准理论的核心动作）没有发生。`freezeNumericOriginal`（RoomExecutionSessionRepository.kt L32）把「原估」冻结入库了，但冻结完就沉底。
- 撤销/历史恢复不回退执行事实（REPLAND_IDEA.md §三.6）——规则正确，实现有保护（撤销遇新约束拒绝），测试有覆盖（HistoryRestoreWorkflow 等）。

---

## 2. 分维度体检

### 2.1 数据正确性与一致性 —— 总体优秀，两处隐患

**已检查未见问题的：**
- 无 `fallbackToDestructiveMigration`；[ReplandDatabase.kt](../android/app/src/main/java/com/swan1127/repland/data/room/ReplandDatabase.kt) 全显式迁移：22→23（plan_segments 加 authority/stableKey）、23→24（team 域表）、24→25（tasks 加 origin/originRef/originLabel），20→21 走中间表保字段，21→22 加 reminderEnabled——全部加列型，非破坏。
- 多表写入普遍 `database.withTransaction`：任务保存（RoomTaskRepository.kt L41-95）、会话开始（L22-36）/结束（L69-109）、部分完成（L154-171）。
- 迁移有仪器测试（Migration20To21 / 23To24 / 24To25 / 5To6）。

**隐患 1（中）：执行会话时长用墙钟。** `elapsedMillis(System.currentTimeMillis())`（RoomExecutionSessionRepository.kt L74-75）——用户改系统时间/NTP 跳变会虚增或吞掉专注时长，且该时长会作为「实际投入」写入执行日志，污染 NumericProfile 与未来的校准数据。顶级做法是 `SystemClock.elapsedRealtime()` 锚定 + 墙钟仅用于展示。

**隐患 2（低）：workspace 表做执行会话存储。** `active-execution-session` 存 JSON payload 在通用 workspace 表（非类型化列），schema 演进靠 codec version 字段自检（decode 里 `require(version==1)`）。可行但绕开了 Room 的 schema 校验体系，codec 坏了不会在迁移测试里暴露。

### 2.2 业务规则漏洞

| # | 问题 | 证据 | 严重度 |
| --- | --- | --- | --- |
| V1 | **时间待定提案确认时静默存成任务**（scheduledForDate=null，inputSource 标记为 ACCEPTED_SUGGESTION，无任何提示） | AgentCenterScreen.kt L480-498：`taskDrafts` 过滤条件只看 `existingTaskId == null`，不看 `timeHint`；L499 的 segments 才筛 explicitStartMinute | **高** |
| V2 | **回顾界面缺失 + 回顾通知无落地页** | §1.4 证据链 1-4 | **高** |
| V3 | **未排期任务无法开始专注**：`startForTask` 要求任务已排到今天，否则抛「任务还没有安排到今天」——「先干活后补排」被技术实现堵死 | RoomExecutionSessionRepository.kt L44-48 | **中** |
| V4 | **双开始语义**：日程页「进入专注」（segment 维度）vs 任务页开始（task 维度），两条路径行为不同但用户视角是同一个动作「开始做」 | `start(segmentId)` vs `startForTask(taskId)`（L22-55）；TimelineEntryDetailSheet「进入专注」（TimelineDashboard.kt L1368-1373） | **中** |
| V5 | EventLibrarySheet 空态死胡同：「没有待安排的事项。」无任何 CTA | TimelineDashboard.kt L314-315 | 低 |
| V6 | EventLibrarySheet 已排过滤只看「今天」：昨天排过一半的事显示成全新事项（代码注释自证，红字提示是补丁不是修复） | TimelineDashboard.kt L366-374 注释「这一列只按今天有没有排过过滤，昨天排过一半的事在这儿看着像全新事项」 | 低 |
| V7 | 孤儿资源/死代码累积：daily_review_* 字符串、beginDailySummary、ArrangementIntent.REVIEW 仅剩 UI 标签（「识别为：先整理成可回看的事项」AgentCenterScreen.kt L930）无后续动作 | strings.xml L39-48；全源码 grep | 低（腐烂信号） |

**已排除的线索（核实不成立/已修复）：**
- ~~部分完成强制百分比+文字~~：已放宽。`requireContent=false`、`requirePartialProgress=true` 但百分比可留空=未知（ReplandApp.kt L6162-6163、L6250-6251、L6323、L6360-6364）；仓库层允许 `progressPercent==null`（TaskExecution.kt L93-95）。剩余小问题：一旦填了就必须 1-99，没有「0%（完全没做）」的表达。
- ~~EventLibrarySheet 对时间不敏感~~：已补空闲档建议、睡眠窗口守卫、无时长引导（TimelineDashboard.kt L314-374）。

### 2.3 可靠性与稳定性 —— 强项

- **闹钟链路完整且是同类标杆级**：`setExactAndAllowWhileIdle` + `canScheduleExactAlarms()` 降级非精确（LocalReminderScheduler.kt L78-102）；`BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED` 全恢复（ReminderRescheduleReceiver.kt L11-34）；午夜 0:05 day-rollover 兜底重排（L155-156、LocalReminderReceiver.kt L62-79）；触发时重读数据库校验 eligibility 防陈旧闹钟（L27-60）；POST_NOTIFICATIONS 运行时检查 + SecurityException 捕获（L130-223）；双渠道、事件渠道 IMPORTANCE_HIGH+振动。
- 权限声明干净：POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED（AndroidManifest.xml L5-8）。
- 导出为版本化 JSON `repland-local-export/v1`（LocalDataJsonExporter.kt L16-24）；密钥不进导出。
- 隐患：墙钟计时（§2.1 隐患 1）；数据库损坏无显式处理策略（Room 默认抛异常，无降级恢复路径文档）。

### 2.4 AI 能力 —— 工程规范好，架构债在双体系

- **两套 AI 系统并存且都在役**：
  - `domain/team` + `ui/team`（TeamScreen/TeamViewModel/TeamSessionStore/**BackendSyncChannels**——群聊 UI，比赛团队域，带服务端依赖 `server/`）；
  - `domain/planteam`（PlanningTeamRuntime/AgentRegistry/AgentTools/AgentMemory/AgentWorkspace + PlanningTeamCoordinator，被 PlanViewModel 与 PlanningTeamLogPanel 使用）。
  - 双维护成本与行为漂移风险真实存在（例如「待安排/待定」任务计数逻辑各自实现，PlanningTeamRuntime.kt L289-315）。
- **密钥安全规范**：Keystore AES + IV，密钥不进 Room/导出/日志/UI 状态（SecureAiProviderConfigRepository.kt L22-51 类注释+实现）；换服务地址强制重输密钥防侧漏（L36）；剔除不可见字符时记日志但不泄内容（L44）。
- **输出本地校验**：AiAdvisorContract 白名单类型 + 文本校验 + plan advice 引用白名单（AiAdvisorContract.kt L225-245），非法即 Invalid 丢弃。
- **降级路径完善**：AI 不可用均有本地 fallback 文案（PlanningAgent.kt L258-266），失败原因分类到用户可读文案。
- BYOK 按变体门控：debug/designPreview/internal/release（build.gradle.kts L61-118）；文档声明 release 未接远程 Advisor——本轮未构建验证，列为待验证项。

### 2.5 小组件 / 通知 / 离线

- 小组件读 `ScheduleTimeline.entries` 渲染今日日程，含 Empty/Done 状态（HomeWidgetProvider.kt L90-215）；**只读展示**，点击只能进 app——顶级对标（TickTick/Structured）小组件可勾完成、可打卡，这是可补的增量。
- 天气 Open-Meteo 有独立 codec 测试（OpenMeteoCodecTest，13 用例），离线边界遵循「本地优先，远程可选」的产品承诺。
- 语音输入在 AgentComposer（AgentCenterScreen.kt L567 起）有入口。

### 2.6 测试与发布

- 规模健康：JVM ≈ 246、仪器 ≈ 438，含迁移测试、HistoryRestoreWorkflow、Reminder 仪器测试。
- **盲区**（按风险排序）：
  1. 安排助手确认落库无 E2E：V1 静默落库这类缺陷正是「每个部件都有单测、合起来行为错了」的典型。
  2. `startForTask` 未排期拒绝路径无测试（V3 的产品合理性也从未被测试质询）。
  3. 孤儿字符串无人发现——未启用 lint UnusedResources/RequiredTrimmedChecks 门禁。
  4. 通知→deep link 行为无测试（V2 的通知无落地页正是此类）。
- 发布链路：Build-App.ps1 执行 assembleDebug/assembleInternal/testDebugUnitTest/verifyOfflineMvpBoundary 并记录产物 SHA-256（Build-App.ps1 L53-78）；release 有签名校验（build.gradle.kts L154-183）；版本手工管理于 gradle.properties。
- 文档漂移：REPLAND_IDEA.md 仍写「0.1.35（1035）/基线 08a686b/2026-10-22」，与主干 1.1.0/1110 差了一个大版本——对新协作者是误导。

### 2.7 跨副本风险

`repland-github`、`repland-build`（1.0.0）、`swan-repland`、`repland` 四个副本无版本锚点（多数没有 android/gradle.properties），上游设计树 `upstream-design/` 与主干的 DailyReview 等功能已经分叉。**跨副本同步带进来的 bug 类型**在本仓库已现形：功能在副本里存在（DailyReviewDialog、schedule range guard 测试）、主干里没有或字符串孤儿化。任何从副本向主干的搬运都必须按文件 diff 而不是按记忆。

---

## 3. 对标顶级产品：差在哪一层、差多少

| 对标 | 他们的关键机制 | Repland 差距 | 可移植的具体做法 |
| --- | --- | --- | --- |
| **Sunsama** | 每日规划仪式（planning ritual）与收工仪式（shutdown ritual）：每天强制一次「昨日未完成→carry over/放弃」的显式决策流，带引导性字段但可跳过 | Repland 的 daily review 是只读快照且已从主干消失；「待确认工作段」没有聚合决策流 | 把待确认工作段做成一次性的「收工清单」：每项一键 完成/部分完成/顺延/不适用，全部落库（现有 `confirmStatus`/`recordPartialCompletion`/`recordFeedback` 三个仓库方法已支持，只缺 UI 编排） |
| **Things 3** | Upcoming/Anytime/Someday 三态 + 拖拽重排零摩擦；「今晚」轻量规划 | 未排期任务被 `startForTask` 拒绝，Anytime 语义残缺 | `startForTask` 未排期时给出「安排并开始」内联动作（借 EventLibrarySheet 的 `TaskPlacementPolicy.nextFreeMinute`），而不是报错 |
| **TickTick / Structured** | 小组件可勾完成、小组件即输入 | 小组件纯只读 | 小组件加「完成」app action（Room 单表更新，风险低）；TimelineEntryDetailSheet 已有操作先例 |
| **Akiflow** | Command bar + daily shutdown：每天一次「清空收件箱」的收尾动作 | 一句话处理只做录入，不参与收尾 | 把「一句话处理」复用为回顾对话框的快捷入口（「明天重点做什么」） |
| **元认知校准理论**（Dunlosky 等：预测→结果→偏差反馈→策略调整） | 校准要求预测与结果被并排比较，偏差反馈影响下一次预测 | `freezeNumericOriginal` 已冻结原估、执行日志已有实际投入，但从不并排呈现、不影响下次估时 | 任务完成回执中展示「预计 60 分钟 / 实际 95 分钟（偏差 +58%）」，并提供「下次同类任务估时建议」= 同类别近 3 次实际/预计比的中位数（数据已在 NumericProfile 90 天契约内） |

**一句话定位：** Repland 目前是「记录严谨的执行器」，顶级竞品是「决策流水线」。差距不在数据层（数据层反超多数竞品），而在**把已有数据编排成用户必须面对的决策时刻**。

---

## 4. 优先级路线图（每项：问题→根因→改法→验收标准）

### 第一阶段（P0：高价值、可立即落地、全部不动 schema）

**P0-1 重建每日回顾并把它变成决策面**
- 问题：回顾界面缺失（V2），元认知闭环断在第四步。
- 根因：上游设计的只读 DailyReviewDialog 未移植/被移除，字符串孤儿化；提醒通知却仍在发。
- 改法：从 `upstream-design/repland/ui/ReplandApp.kt` L2711+ 移植 DailyReviewDialog 并升级：(a) 「待确认工作段」每项内联 完成/部分完成/顺延 按钮，直连现有 `taskViewModel.confirmStatus/recordPartialCompletion`；(b) 「下一步建议」行加「收进事项库」按钮；(c) DAILY_REVIEW 通知 PendingIntent 带 deep link extra，MainActivity 解析后直接开该对话框。
- 验收：① 点 DAILY_REVIEW 通知 10 秒内直达回顾对话框（仪器测试：NotificationDeepLinkTest）；② 对任一待确认工作段操作后，任务状态与执行日志落库正确（JVM 测试覆盖编排逻辑）；③ 「跳过回顾」不写任何表（现有语义保留）；④ 删除 strings.xml 中孤儿字符串或全部接线。

**P0-2 修复时间待定提案静默落库**
- 问题：确认时未定时间提案无提示地存成任务（V1）。
- 根因：AgentCenterScreen.kt L480-498 的 taskDrafts 构造不筛 timeHint，与 L499 segments 的过滤不一致；确认弹层无分组。
- 改法：确认面板分两组「排入今天（N）」/「只记入事项库（M）」，第二组明示 scheduledForDate 为空；`ACCEPTED_SUGGESTION` 标记只给排入组，只记入组标 `USER_INPUT`（来源语义与 REPLAND_IDEA.md §三.2 对齐）。
- 验收：① 含未定时间提案的确认出现分组 UI（Compose 测试 testTag：`agent-confirm-undecided-group`）；② 只记入组的任务 `scheduledForDate==null` 且 inputSource==USER_INPUT（JVM 测试）；③ 保存回执文案区分两组。

**P0-3 双开始语义统一 + 未排期可开始**
- 问题：「进入专注」两条路径行为不一致；未排期任务被拒绝（V3/V4）。
- 根因：`startForTask` 强制要求今日 segment 存在。
- 改法：未排期时走「安排并开始」：用 `TaskPlacementPolicy.nextFreeMinute` 取档 → 创建 segment → 开会话，一个事务完成；两处 UI 文案统一为「开始专注」。
- 验收：① 任务页对未排期任务点开始，日程出现新 segment 且会话激活（仪器测试）；② 冲突时沿用现有保护文案；③ 两条路径对同一任务产生相同状态的会话记录。

### 第二阶段（P1：补校准回路）

**P1-1 估时校准反馈**
- 问题：预测-结果-偏差回路缺失（§1.5）。
- 改法：完成/部分完成回执（复用 feedbackSnackbar）追加「预计 X / 实际 Y」对比；新增 `EstimateCalibrationService`（纯 JVM）：同类别近 3 次 actual/estimated 中位数→下次估时建议，写入任务「建议估时」字段，用户采纳才生效（延续「用户明确值优先」原则）。
- 验收：① 完成带估时任务后回执可见偏差（Compose 测试）；② 建议计算有 JVM 测试（空历史/不足 3 次/除零边界）；③ 未采纳不影响任何数据。

**P1-2 EventLibrarySheet 收尾**
- 改法：空态加「新建事项 / 去任务库」CTA；已排显示改为按「今天+过去 N 天」并标日期（修 V5/V6）。
- 验收：空态两 CTA 可用；昨天排过的项显示「已排在昨天」并仍可再加段。

**P1-3 计时改单调时钟**
- 改法：ExecutionSession 以 `SystemClock.elapsedRealtime()` 记 runningSince，墙钟仅展示；codec version 升 2 并兼容 decode 1。
- 验收：JVM 测试模拟时钟跳变不改变累计时长；旧版本会话可读。

### 第三阶段（P2：架构与发布成熟度）

**P2-1 双 AI 体系整合决策**：明确 domain/team（群聊/比赛域）与 domain/planteam（规划团队）的边界文档化，提取共享的「待定/待安排」语义与提示词基础设施；或合并 runtime。验收：两体系无重复实现的「待定提案」处理路径，或边界文档 + 各自测试。
**P2-2 lint 门禁**：启用 UnusedResources、Compose 测试 tag 清单检查进 Build-App.ps1。验收：孤儿字符串/孤儿 testTag 构建即红。
**P2-3 文档对齐**：REPLAND_IDEA.md 版本号与「当前能力」表更新到 1.1.0 实况（回顾栏从「只读」改为「决策面」后同步改写）。
**P2-4 真机验收矩阵**：按 docs/RELEASE.md 补 Doze/厂商后台杀进程下闹钟与执行会话恢复的真机用例。

---

## 5. 本轮未验证项（如实声明）

- 未执行 Gradle 构建、未运行任何测试；release BYOK 门控、`verifyOfflineMvpBoundary` 的实际效果未验证。
- `server/` 群聊后端与 BackendSyncChannels 的网络行为未深审（本轮范围限 Android 端）。
- 跨副本（repland-github/swan-repland/repland）仅比对到版本锚点缺失，未做全量功能 diff。
- 未发现需要立即上报的安全问题；密钥管理实现规范。若后续引入群聊多人协作，BackendSyncChannels 的鉴权应做专项安全审计。
