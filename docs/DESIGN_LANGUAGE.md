# Repland 前端美术规范（DESIGN_LANGUAGE v1）

更新：2026-11-01。目的：统一 0.1.35 / 1.0.0 / 1.1.0 三条来源线合流后的视觉语言，让新界面（尤其团队域）"填空"而不是"发挥"。原则：**只增不改**——存量界面按分期渐进收编，不一次性重刷。

## 1. 语义色角色（本次落地）

事件与任务的状态色收编为 `ui/theme/VisualTokens.kt` 的 `SemanticStateColors`，经 `LocalSemanticStateColors` 供给，明暗两套：

| 角色 | 用途 | Light 底 / 字 | Dark 底 / 字 |
| --- | --- | --- | --- |
| planned 计划中 | 已排未开始的工作段 | EAF2FF / 3F6094 | 29374B / BBD2F5 |
| inProgress 进行中 | 当前执行会话 | E8F3F5 / 326B75 | 263F43 / A8D9DE |
| done 完成 | 已完成段 / 任务 | DAF2E4 / 39634D | 304E40 / CEEDDA |
| missed 未完成 | 未完成 / 未反馈关闭 | F9E9E3 / 95503A | 4A322B / E8C0B0 |
| unknown 未知 | 进度未知、来源未知 | EEF0F3 / 5A6678 | 2E3742 / C0CAD9 |
| protectedTime 硬保护 | 课程、固定事项、锁定 | F3ECF8 / 6E5392 | 3A3148 / D4C4EC |

规则：**新界面只允许用语义角色、`replandAccent(index)` 与 MaterialTheme colorScheme 取色，禁止新增裸色值。** 时间轴 / 执行记录 / 团队任务状态逐步换用本表。

## 2. 主题色现状（收编前不动）

主色当前是静态 `CalmAccent`（Theme.kt）；`ThemeAccent { BAMBOO, INDIGO, PLUM }` × `ThemeRamp` 的多套主题色已在 `AccentPalette.kt` 备好但未接线（`paletteFor` 暂无调用方）。规范化期间**不得擅自接线或删除**；将来开放"我的 → 主题色"时统一接。

## 3. 渐变的使用权

三色彩虹渐变（新增卡 `5B8DEF → 8F6ADF → DF6A9E` 一族）**只授予"创造新东西"的时刻**：新增卡、新建队伍。禁止用作页面背景、分隔、装饰——渐变蔓延会让体系噪音化。

## 4. 插画语言

山水四景（`widget_bg_sunny / cloudy / rainy / snowy`）是唯一插画语言：用自然场景表达状态。新增状态场景（如团队阶段、归档）沿用同一套山水色调绘制，**不引入第二套扁平插画风格**。

## 5. 动效三原则

基础设施在 `ui/theme/Motion.kt`：`ReplandMotion` 常量（Press=90 / Release=140 / Page=320 / Panel=280 / Settle=520…）、`pressMotion()` 按压反馈、`LocalReducedMotion`（读系统 ANIMATOR_DURATION_SCALE，已接 ContentObserver）。

1. **确认有落点**：盖楼动画的"积累"隐喻是产品招牌——专注计时、学习反馈、台账连续记录共用这一隐喻，确认动作要有可感知的落点。
2. **破坏性操作可逆**：撤销路径必须存在，动画表现"放回去"，不做不可逆的粉碎/消失动效。
3. **尊重系统设置**：一切动效走 `ReplandMotion` 常量与 `pressMotion`，禁止局部自定 spring/easing；系统动画关闭时全部动效退化为直切。

## 6. 收纳语言（二选一）

- 个人域"多件事叠一处" = 书堆卡 `ScheduleStackCard`（横滑翻看）。
- 团队域横向时间 = 晾衣绳 `TeamDeck`。

不新增第三种叠放 / 滚动隐喻；需要新的收纳场景时先回到这两种扩展。

## 7. 时间输入（唯一控件族）

- 时刻 / 时长：一律 `TimeWheel`（`rememberTimeWheelHold` / `TimeWheelField` / `rememberDurationWheelHold` / `DurationWheelField`）。
- 日期：一律 `TaskDatePickerDialog`。
- 团队截止设定沿用同族控件（TEAM_COLLAB_DESIGN §7 已对齐），不再发明新的时间输入。

## 8. 触达与无障碍

- 可点胶囊 / 按钮视觉高度 ≥40dp（Material3 最小触达自动兜底）。
- 大字号（fontScale > 1.3）下胶囊与提示条换行不截断（沿用 `DataReadNotice` 先例），liveRegion 语义不丢。
- 文案预算：回执一句话、标签四字内、异常说明一行。

## 9. 新界面评审清单（过审六问）

1. 色彩只用语义角色 / accent / colorScheme，没有新裸色值？
2. 渐变是否属于"创造新东西"的时刻？
3. 插画是否山水语言？
4. 动效是否走 `ReplandMotion` 且尊重系统动画开关？
5. 叠放 / 时间线是否使用了规定的收纳语言？
6. 时间输入是否用了 TimeWheel / TaskDatePicker？

交互侧配套规范见 [CAPSULE_LAYER_SPEC](CAPSULE_LAYER_SPEC.md)；跨项目通用版在 [liuliskills](https://github.com/Zlpper688/liuliskills)。
