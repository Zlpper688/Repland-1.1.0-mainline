# 竞品内容扫描（REA 证据版 · 2026-11）

> 目的：回答「别人怎么把任务、空闲、截止、会议缝在一起」，只采纳有证据、且能落成本项目基础设施的部分。
> 方法沿用 ilovemxt 那套 REA 链路：浏览器场景捕获 → DOM / a11y / 截图三份证据 → 特征矩阵 → 采纳/拒绝。
> 原始证据：`docs/research/competitor-2026-11/`（6 个首页 + 2 个价格页，全部 `stepStatus=completed`）。

---

## 1. 结论先行

| # | 学到的 | 出处 | 决定 | 落点 |
|---|---|---|---|---|
| 1 | 计划要随每次变更自动重算，并把「风险」提前几天说出来 | Motion | ✅ 已在做，补结构化 | `GatePlanner` + 新增 `TeamDiagnostics` |
| 2 | 排期结果要能「安全预览再批准」，而不是直接改日历 | Reclaim | ✅ 采纳为草案差异摘要 | 下一步：`PlanDiff`（见 §6） |
| 3 | Do date ≠ Due date：排在哪天 ≠ 最晚哪天 | Motion | ✅ 已在做 | `Placement` 与 `hardDeadline` 分离，已是现状 |
| 4 | 「今天到底塞不塞得下」要用负载条说话 | Sunsama / Reclaim | ✅ 采纳 | 下一步：`ScheduleOutput.dayLoad` + 日容量条 |
| 5 | 缓冲是一等公民，不是隐性 +1 天 | Reclaim | ✅ 采纳 | `bufferDays` 改为可见参数，并在诊断里报「缓冲偏薄」 |
| 6 | 模板降低起步成本，但不要做 40 个视图 | Teambition / 飞书项目 | ➖ 部分采纳 | 保留 3 套赛段模板；拒绝多视图 |
| 7 | 赛事聚合 + 截止提醒是入口 | 赛氪 | ➖ 部分采纳 | 已有 `competition_search/deadlines`；不做报名/支付 |
| 8 | 全流程引擎、AI 笔记、AI 文档、BI 看板 | 飞书项目 / Motion | ❌ 拒绝 | 与「队长副驾驶 + 账本」定位冲突，见 §5 |

**一句话**：竞品在往「AI 替我决定」走，我们继续走「AI 只承接基线内调度，决定权和证据都留给人」；这轮真正要补的是让内核**可解释、可修复、可预览**的基础设施。

---

## 2. 采样与证据

| 站点 | 类型 | 抓取 | 证据文件 |
|---|---|---|---|
| reclaim.ai | AI 日历排程（个人/团队） | 首页 + 价格页 | `text-reclaim.txt` / `a11y-reclaim.txt` / `reclaim.png` |
| usemotion.com | AI 任务 + 项目 + 日历 | 首页 + 价格页 | `text-motion.txt` / `a11y-motion.txt` / `motion.png` |
| sunsama.com | 每日计划 + 任务/日历合一 | 首页 | `text-sunsama.txt` / `sunsama.png` |
| teambition.com | 钉钉系团队项目管理 | 首页 | `text-teambition.txt` / `teambition.png` |
| project.feishu.cn | 飞书项目（流程引擎 + AI） | 首页 | `text-feishu-project.txt` / `feishu-project.png` |
| saikr.com | 大学生赛事聚合 + 报名 | 首页 | `text-saikr.txt` / `saikr.png` |

- 场景 JSON、提取脚本、抓取清单：同目录 `scenario-*.json`、`extract.mjs`、`manifest.json`。
- 注意：Teambition 首页是 SPA 壳（仅 2 个标题），正文来自首屏营销文案；飞书项目首页含 AI 演示台，文本量最大。结论只使用文本中明确出现的表述，不做功能臆测。

---

## 3. 特征矩阵（只列与我们的内核有关的）

`●` 明确具备 / `◐` 部分或需付费 / `○` 未提及 / `—` 不适用

| 机制 \ 产品 | Motion | Reclaim | Sunsama | Teambition | 飞书项目 | 赛氪 | REPLAND 现状 |
|---|---|---|---|---|---|---|---|
| 排程与截止分离（do vs due） | ● | ● | ● | ○ | ○ | — | ● `Placement` / `hardDeadline` |
| 自动把任务排进日历 | ● | ● | ◐ | ○ | ○ | — | ● `TeamScheduler`（确定性） |
| 依赖 / 关键路径 | ●（dependencies 入参） | ○ | ○ | ◐ | ●（流程引擎） | — | ● `DeadlineEngine` 倒算 |
| 阶段/环节模板 | ●（AI 生成阶段） | ○ | ○ | ●（40+） | ●（最佳实践） | — | ● 3 套赛段模板 |
| 全员会议时段 | ◐ | ●（Smart Meetings） | ○ | ○ | ○ | — | ● 交集候选 + 一周推荐 |
| 缓冲 / 准备时间 | ○ | ●（Buffer Time） | ◐ | ○ | ○ | — | ◐ `bufferDays`（未显性化） |
| 负载/容量可视化 | ● | ●（Workforce Analytics） | ●（realistic workload） | ◐ | ○ | — | ◐ 只有总工时，无每日负载条 |
| 风险预警（提前几天） | ●（at-risk） | ●（burnout/meeting health） | ○ | ○ | ◐ | — | ● `GatePlanner`（只提示） |
| 变更后自动重排 | ●（每次变更） | ● | ◐ | ○ | ○ | — | ● 每次调用重算（草案） |
| 安全预览再批准 | ○ | ●（preview & approval） | ○ | ○ | ◐ | — | ● 草案→确认/一键异议 |
| 课表/个人忙闲作为输入 | ◐ | ●（多日历） | ● | ○ | ○ | — | ● 个人日程唯一真相源 |
| 私有时间只显示「在忙」 | ○ | ◐ | ○ | ○ | ○ | — | ● `PRIVATE_BUSY` |
| 周报/复盘 | ●（auto reports） | ●（time intelligence） | ●（weekly goals） | ◐ | ●（仪表盘） | — | ● 周报（只写进展） |
| 外部聊天/群集成 | ●（Slack/Teams） | ●（Slack 状态） | ●（Slack/Teams 转任务） | ●（钉钉群） | ●（飞书） | — | ● QQ 机器人 |
| AI 写笔记/文档/工作流 | ● | ○ | ○ | ○ | ● | — | ❌ 明确不做 |

---

## 4. 关键证据摘录（原文）

**Motion**（`text-motion.txt`）

- “Motion takes all of your projects and tasks — their priorities, dependencies, deadlines, durations — and prioritizes them… It builds a perfect plan that optimizes itself hundreds of times a day.”
- “Do Date ≠ Due Date. … When Motion thinks a task is at-risk, it proactively warns you days or weeks in advance.”
- “Motion auto-updates the plan with every change — like missed deadlines or new priorities. No 1-hour resync meetings needed.”
- “Describe your project and add relevant docs. Motion instantly builds the entire project — tasks with deadlines and assignees, project stages.”
- 价格页：`$19/seat/mo`（Pro AI）、`$29/seat/mo`，按 credits 计。

**Reclaim**（`text-reclaim.txt` / `text-reclaim-pricing.txt`）

- “AI Focus Time：Set a Focus Time goal, and let AI defend time”；“AI Habits：recurring events that flex and find time”；“AI Tasks：Auto-sync and schedule Tasks to your calendar, by priority.”
- “AI Buffer Time：Let AI schedule time to break, prep, and follow-up.”
- “AI Smart Meetings：Find the best time to meet across all attendees' schedules.”
- FAQ：“Its AI surfaces recommendations, enables safe preview and approval of changes, and automates policy enforcement.”
- 价格：Lite 免费，Starter `$10`、Business `$15`、Enterprise `$22` /seat/月；另有「Initiatives」做公司级策略（Focus Time / No-Meeting Day 等）。

**Sunsama**（`text-sunsama.txt`）

- “Plan your day with intention by aligning your goals, prioritizing tasks, and setting a realistic workload.”
- “Easily adjust your daily plan without losing focus or taking on too much.”
- “Set and track weekly goals to focus on what truly matters.”
- 与任务工具（Asana/Trello/Jira）双向集成，把任务拉进「今天」再时间盒。

**Teambition**（`text-teambition.txt`）

- “Provides 40+ project templates, best practices, one-click reuse.”
- “Various switchable views — clearly see who does what when.”
- “Project associated with DingTalk group, members automatically synchronized.”

**飞书项目**（`text-feishu-project.txt`）

- “一套流程引擎，适配万千行业场景”；“一键应用最佳实践”；IPD 行业专版；AI 研发智能体（CodeM）演示。
- 强调流程驱动 + 角色协同 + 企业安全，面向中大型研发组织。

**赛氪**（`text-saikr.txt`）

- “全国大学生竞赛活动平台…提供专业报名系统和赛事服务”；“2026 赛季已全面开启 · 5219+ 项赛事火热报名中”。
- 分类检索（数学建模 / 美赛 / 算法 / 创新创业…）与资讯（保研、面试）是主要入口。

---

## 5. 采纳 / 拒绝（带理由）

### 采纳（已做或本轮补）

1. **风险提前说（Motion / Reclaim）→ 已有 `GatePlanner`，本轮补结构化输出。**
   `AT_RISK` / `BLOCKED` 之前只拼成句子塞进 notes，UI 和助手都得解析文本。现在统一成 `TeamDiagnostic`（severity / target / advice），助手直接读结构。
2. **修复而不是拒绝（Motion 的「自动更新计划」）→ 本轮落地。**
   成环依赖不再让整份草案为空：忽略环内依赖、先排无环部分，同时发 `ERROR` 诊断说明「改了哪条边」。模板套用遇到重复编号/坏日期也逐条跳过并汇报，不整批回滚。
3. **安全预览再批准（Reclaim）→ 已有草案确认流，下一步补「差异摘要」。**
   现在草案能确认/异议，但用户看不出「这次重排动了哪几段」。下一步在确认面板显示 `新增 / 移动 / 取消` 三类计数与明细（见 §6）。
4. **每天的负载条（Sunsama「realistic workload」）→ 下一步。**
   现在只报成员总工时；需要 `ScheduleOutput.dayLoad`（每人每天已排分钟）驱动日容量条与「今天已满」提示。
5. **缓冲显性化（Reclaim Buffer Time）→ 下一步。**
   `bufferDays` 已是内核参数，但界面和诊断都没露出来；至少在关卡风险里报「缓冲偏薄」，并提供队长可调入口。
6. **赛段模板保持小而准（Teambition / 飞书）→ 已做。**
   3 套模板覆盖数模/创新/通用；不追 40+。比赛档案卡的「补充区」承担长尾参数，避免模板爆炸。

### 拒绝（并写进差异化）

| 拒绝项 | 理由 |
|---|---|
| 流程引擎 / 审批流（飞书项目） | 竞赛团队只有 3–6 人，流程引擎的配置成本远高于收益；基线 + 阶段 + 依赖已经覆盖 |
| AI 笔记 / AI 文档 / 工作流生成（Motion） | 会让产品变成「又一个全能工作台」；我们的差异是排期与留痕，文档走外链绑定 |
| 组织级 Initiatives / 人力分析（Reclaim Enterprise） | 面向企业管理者，不是队长；且与「留证不留评」冲突 |
| 40+ 模板 + 多视图（Teambition） | 视图切换会稀释「队长唯一界面」；模板只保留赛段维度 |
| 报名 / 支付 / 赛事交易（赛氪） | 赛氪是流量与报名平台，我们是赛后协作层；只做截止聚合与提醒 |
| 用 AI 直接改计划（Motion 的自动执行） | 我们是「草案 → 确认/一键异议」，AI 越权决策等于队长失职自动化 |

---

## 6. 泛化落点（基础设施，不绑死某个竞赛）

### 本轮已落地

- `domain/team/TeamDiagnostics.kt`：`TeamDiagnostic(code, severity, target, targetId, message, advice)` + `digest()`。
  统一了期限、环节、关卡、未排上、冲突、低置信度、模板跳过七类问题；`TeamScheduler.ScheduleOutput.diagnostics` 输出，`plan_team_week` 工具结果带 `diagnostics` 数组。
- `DeadlineEngine`：成环 → 修复环内依赖后继续倒算，返回 `CYCLE` 问题；`TeamScheduler` 不再整单返回空草案。
- `TeamCollabState.applyStagePlan`：返回 `StagePlanApplyResult(addedPhases, addedTasks, skipped)`；重复编号、倒挂日期、悬空阶段逐条跳过并写入一条汇总通知；VM 与助手都会把「跳过 N 项」说出来。
- 测试：新增成环修复、模板跳过、未排上诊断 3 项；`TeamKernelTest` 21 项全绿。

### 下一步（按依赖顺序）

| 优先级 | 模块 | 内容 | 证据依据 |
|---|---|---|---|
| P0 | `PlanDiff` | 草案与现状的差异（新增/移动/取消），UI 确认面板与 QQ 通知共用 | Reclaim「safe preview and approval」 |
| P0 | `dayLoad` 输出 | `ScheduleOutput` 增加每人每日已排分钟；日容量条 + 满载诊断 | Sunsama「realistic workload」 |
| P1 | `StageGraph` | 阶段/依赖/时点/关卡收敛成一张图；模板与用户编辑都走同一套归一化（clamp、丢弃前向依赖、默认工期） | 飞书流程引擎的反面教材：只保留最小图能力 |
| P1 | 快照迁移版本 | `TeamStateCodec` 带 `schemaVersion`，未知字段保留、缺字段回退 | 旧存档兼容已在做，需系统化 |
| P2 | 竞赛截止聚合视图 | 把 `competition_deadlines` 与团队任务截止放在同一时间轴 | 赛氪的入口价值 |
| P2 | 周报加「计划 vs 完成」 | 周报增加排期兑现率，不做个人评分 | Sunsama weekly goals / Motion reports |

---

## 7. 对答辩话术的影响

- **和 Motion 的区别**：Motion 让 AI 直接改计划；我们把 AI 限制在基线内产出草案，确认权与异议权在人。差异不是能力多少，而是**责任边界**。
- **和 Reclaim 的区别**：Reclaim 优化「时间被怎么花」；我们优化「竞赛交付能不能赶上」，输入是阶段、依赖、关卡，不是会议健康度。
- **和飞书/Teambition 的区别**：它们是通用协作平台，我们是「会排期的队长副驾驶 + 贡献账本」，通过 QQ/飞书集成而不是重造工作台。
- **和赛氪的区别**：赛氪解决「去哪报名」，我们解决「报名之后怎么干完」。

---

## 8. 证据边界（避免过度解读）

- 只抓了公开首页/价格页，没有登录后的真实交互；「自动重排」「预览批准」等结论来自官方文案，未做产品内验证。
- 未抓移动端；未验证其算法细节（如 Motion 的优化频率、Reclaim 的打分公式）。
- 价格与套餐随时会变，本文只用于定位，不作为采购依据。
