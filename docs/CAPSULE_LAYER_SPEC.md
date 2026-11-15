# 胶囊层落地方案（Repland × capsule-interface-design）

更新：2026-11-01。规范来源：[liuliskills/capsule-interface-design](https://github.com/Zlpper688/liuliskills)（`skills/capsule-interface-design/SKILL.md`）。本文只做一件事：把该规范映射到 Repland 的真实界面与代码落点，作为实施与验收依据。视觉规范见 [DESIGN_LANGUAGE](DESIGN_LANGUAGE.md)。

## 一、规范摘要

回执 = **一句话 + 一排胶囊**。胶囊两种形态：

| 形态 | 行为 | 用途 |
| --- | --- | --- |
| 动词胶囊（实底） | 点即生效，最多两个并排 | 状态反馈（完成 / 未完成）、二元出口（搁置 / 排进下一个空闲）、撤销 |
| 变量胶囊（描边） | 打开原生选择器，不预填值 | 排期、定时、时长、提醒、类别、对象选择 |

四条规则：**① 不内置内容**（AI 推荐只体现在安排结果与选择器排序里，不上胶囊表面）；**② 负担最小，允许"一点点"**（状态反馈与二元出口可以直接给）；**③ 少写字**（回执一句、标签 ≤4 字、异常说明一行）；**④ 保证自主性**（胶囊背后是完整编辑器，不点 = 保持现状，永不追问）。

## 二、组件层（P0，已落地）

- `ui/components/CapsuleRow.kt`：`CapsuleSpec(key, label, kind, enabled)` + `CapsuleKind.VERB / VARIABLE` + `CapsuleRow(capsules, onCapsuleClick)`；动词实底取 `ReplandVisuals.mainAction`，变量描边取 `outlineVariant`；按压反馈统一 `pressMotion`，FlowRow 支持大字号换行，触达高度 ≥40dp。
- `ui/theme/VisualTokens.kt`：新增 `SemanticStateColors`（计划中 / 进行中 / 完成 / 未完成 / 未知 / 硬保护 六状态，Light + Dark），`LocalSemanticStateColors` 由 `ReplandTheme` 统一供给。

## 三、落点映射与分期

> 状态：P0–P3 已全部落地（2026-11-01，编译 + 全量单测通过）。P2 = 群聊回执撤销行与 AI 草案卡改用胶囊行；P3 = 团队草案确认与待确认写入面板改用胶囊行（testTag 经 `CapsuleSpec.tag` 保留）。

### P1 执行反馈：[完成] [未完成]

- 现状：`PartialCompletionDialog`（`ReplandApp.kt:6095`，入口 `1695-1706` `taskMutationEditorKind == PARTIAL`）强制输入 1–99% 进度（`6245` supportingText），正文与理由为必填——发生在用户最疲惫的时刻，摩擦直接杀死记录。
- 目标：动词胶囊 [完成] [未完成]。**未完成 = 结束工作段 + 净时长自动落账 + 进度记为未知**，不问百分比、不问原因；用户主动多说（"一半"、"20 分钟"）才解析为进度。净时长喂给内部估时校准（来源标"系统推断"，见 REPLAND_IDEA 规则二的来源体系）。
- 改动点：`FeedbackDialog` 族（`ReplandApp.kt:6161-6245`）；需确认任务侧部分完成提交路径允许"进度未知"。

### P1 冲突出口：[搁置] [排进下一个空闲]

- 现状：「安排未保存」`AlertDialog`（`ReplandApp.kt:1895-1902`，`planUiState.errorMessage`，错误源 `RoomPlanRepository.kt:314-321`）；拖拽即时提示 `TimelineDashboard.kt:681`；待排队列文案 `350-365`。
- 目标：一行问题说明 + 两个动词胶囊。**搁置** = 任务保留、进未排入清单并说明原因（`PlanDraftDialog` 的 `unscheduledTasks` 块，`ReplandApp.kt:4094-4131`）；**排进下一个空闲** = 走 `ScheduleArrangement.insertAndDelay` 的两阶段插入 + 自动后延语义。想精细控制的人自己去日程拖拽，不设第三个出口。

### P2 一句话处理回执：回执一句话 + 胶囊行

- 现状：`PlanningAgentOutcomeCard`（`ReplandApp.kt:3216`，渲染点 `2990-2997`）；群聊 `GroupChatSheet` 消息流 + `BatchUndoRow`（`836`，`writeUndo.restore` 在 `437`）。
- 目标：回执一句话 + `CapsuleRow`。变量胶囊：[排期] → `TaskDatePickerDialog`；[定时] → `LabeledTimeWheel`；[时长] → `DurationWheelField`（分钟为单位自定义，无默认值）；[提醒] → 提醒选择；[类别] → 类别列表。动词胶囊：行尾 [撤销]（复用 `AgentWriteUndo` / UndoStore）。
- 底线：胶囊不预填 AI 建议值；明确指令直接生效的规则一不变；歧义场景给选项而非打回重写。

### P3 团队确认面板

对接流确认面板（TEAM_COLLAB_DESIGN §7）复用 `CapsuleRow`：截止设定 = 时间轮盘变量胶囊；接手人 = 队友列表（**适配度体现为列表排序，不做推荐标签**）；会议 = 全员空闲候选列表。与个人域共用同一胶囊语言。

## 四、交互底线（全场景通用）

1. 不点任何胶囊 = 保持现状，一律合法，永不追问。
2. 歧义场景绝不自动提交；明确指令直接生效。
3. 动词胶囊最多两个并排；异常说明只写一行（不知道问题是什么就没法选出口）。
4. 一切胶囊操作可撤销（对应 HAI 指南 G8/G9）。

## 五、验收口径

- 编译 + `testDebugUnitTest` 全绿；`verifyOfflineMvpBoundary` 通过（胶囊层为纯 UI 组件，不引入网络面）。
- 行为验收：说"未完成"三个字闭环成功；冲突弹窗只有两个出口；胶囊表面无预填内容与解释；大字号下胶囊换行不截断；系统动画关闭时无动效。
