# Repland 界面与体验顶级化审计（1.1.0 / 1110）

日期：2026-11-03。范围：`android/app/src/main/java/com/swan1127/repland/ui` 全量 + widget 与 res。性质：只读审计，未改任何代码，未触碰在途工作（首页重组、日视图时间轴化、卡片 morph、拖拽合块动画均按"刻意工作"对待并按现状审计）。

---

## 0. 结论

### 0.1 当前设计水位

**token 层是同类项目里罕见的高水准，执行层正在大面积脱离它。**

- 基础层（`ui/theme/`）已具备顶级应用的骨架：ToneLadder 光照体系（深度来自色调与光向、不用投影，`ToneLadder.kt:10-15`）、WCAG 对比度有 JVM 单测兜底（`AccentPalette.kt:180-200`）、语义状态色六角色成对定义明暗（`VisualTokens.kt:50-81`）、动效常量表 + 系统级 reduced-motion 监听（`Motion.kt:38-89`）、文案预算成文（`DESIGN_LANGUAGE.md §8`）。这些是 Things 3 级别的自觉。
- 但执行层与体系脱节严重：`ReplandApp.kt` 单文件 335KB（约 5800 行）承载几乎全部页面，是绕过 token 的重灾区——圆角字面量出现 **16 种取值**（规范只定义 5 档）、无 spacing token、ToneLadder 的 `replandStage/replandCard/replandWell` 在 ui 层几乎无人调用（卡片全是手写 `Surface + Brush.verticalGradient`）。**体系建好了，没人用。**
- 两个招牌交互（书堆卡翻页、时间轴拖拽合块）恰恰使用了用户最排斥的回弹缓动，同时违反自家 `DESIGN_LANGUAGE.md §5` 第 3 条（"禁止局部自定 spring/easing"）。

一句话：Repland 现在是一栋**图纸优秀、施工队各干各的**的房子。顶级化不需要推倒重来，需要"执法"。

### 0.2 最伤的三处

1. **巨石文件**：`ReplandApp.kt` 335KB。它是重组范围失控、token 被绕过、页面无法独立演化的共同根源。任何视觉顶级化改造都会先撞在这堵墙上。
2. **动效双标**：`ScheduleStackCard.kt:106-109` 用 `DampingRatioMediumBouncy` 翻卡、`TimelineDashboard.kt:565` 用 `spring(dampingRatio = 0.65f)` 做合并——两处都是 bouncy 回弹，且散布 `220/150/700ms` 魔数，与 `Motion.kt` 的常量体系和"确认有落点、无二次弹跳"（`Motion.kt:66-70`）直接冲突。
3. **时间轴触达天花板**：`hourHeight = 48dp`（`TimelineDashboard.kt:521`）下 45 分钟课 = 36dp 高，卡内子行仅 20dp 高、图标 13-14dp；同一张卡上叠了点击、长按拖拽、合并感应三种手势。信息密度与手指尺寸的矛盾没有专门设计，这是日视图体验的硬上限。

---

## 1. 设计系统诊断

### 1.1 色彩：五套体系并存，一处越轨蓝紫

现存五套并行的色彩体系：

| 体系 | 文件 | 状态 |
| --- | --- | --- |
| Palette（纸墨阶） | `theme/Color.kt:10-39` | 在用（经 Theme.kt 接入 Material scheme） |
| Material ColorScheme | `theme/Theme.kt:16-72` | 在用，主入口 |
| ReplandVisuals + SemanticStateColors | `theme/VisualTokens.kt:13-81` | 在用，规范指定 |
| ThemeRamp 三主题（BAMBOO/INDIGO/PLUM） | `theme/AccentPalette.kt:64-163` | **备而未接**（`DESIGN_LANGUAGE.md §2` 自己声明"不得擅自接线"） |
| ToneRamp（ToneLadder 光照） | `theme/ToneLadder.kt:42-60` | 基础设施完备但消费方极少 |

越轨硬编码（违反 `DESIGN_LANGUAGE.md §1`"禁止新增裸色值"）：

- `TimelineDashboard.kt:112-116`：`CourseAccent 0xFF4F65A6`、`RestAccent 0xFF7C7C9C`、`CommitmentAccent 0xFFA66B42`、`SecondClassroomAccent 0xFF9A7420` 四个私有 accent 游离于 `replandAccent(index)` 之外，不随主题/明暗变化。
- `TimelineDashboard.kt:1245`：TASK 类默认色 `Color(0xFF4E64B5)`——一个蓝紫，正是设计语言要防的颜色，它不在任何 token 表里。
- 其余：`AgentCenterScreen.kt` 5 处、`ExecutionSessionDialog.kt` 3 处、`ScheduleHorizonViews.kt` 5 处（`Color(0x` 计数）。`LandscapeArtwork.kt` 56 处属山水插画，可豁免但应在规范中显式声明豁免范围。
- **widget 与 app 纸色不一致**：`res/values/colors.xml:11` 的 `repland_window_background = #F7F8FC`（冷蓝灰）≠ app 的 WarmPaper `#F8F6F1`（暖纸）；同文件 L3-7 残留 Android Studio 模板色 `purple_200/teal_200`。`values-night/colors.xml` 覆盖齐全，方向正确。

### 1.2 圆角与间距：有档位、无执法

- `ReplandShapes`（`VisualTokens.kt:114-120`）定义 6/12/16/22/28 五档。实际代码出现 **2,4,6,8,9,10,11,12,14,16,18,20,22,24,26,28 共 16 种**。仅 `ReplandApp.kt` 就有约 35 处字面量（9/11/14/18/24/26 等档位外取值遍布，如 `ReplandApp.kt:4432,4438` 的 9dp、`:4278` 的 18dp）。
- 无 spacing token（无 `Dimens`/`Spacing` 对象）。`TodayScreen` 列距 16（`ReplandApp.kt:2298`）、书堆卡内 padding 20+34（`ScheduleStackCard.kt:175`）、GroupBlock padding 12/7（`TimelineDashboard.kt:1158`）、捕获页 24/12（`TaskExperienceComponents.kt:335-337`）——节奏全凭手感，深浅屏混排时容易露怯。

### 1.3 字体：体系完整，两个待决点

- `Type.kt:15-31` 层级完整，`ReplandNumberStyle` 的 `tnum` 表格数字（`Type.kt:34`）是顶级细节（计时抖动被根治）。
- 待决 1：`bodyMedium = 15sp` 非 Material 标准 14sp——若是中文可读性的有意决定，应注释成文；否则是随意值。
- 待决 2：`ScheduleStackCard.kt:190-191` 在 `titleLarge`（已是 SemiBold）上再叠 `fontWeight = SemiBold`，冗余覆盖说明字号/字重的"最后一公里"仍靠手调。
- 字体家族统一 SansSerif、零下载字体（`Type.kt:9`），CJK 场景正确。

### 1.4 阴影/描边/渐变：体系宣称与实际绘制相悖

- `ToneLadder.kt:10-11` 宣称"深度来自色调与光向，never from drop shadows"，但 `ScheduleStackCard.kt:159` 用 `shadowElevation 10f/4f`；书堆卡、时间轴块、合轨块全部手写 `Brush.verticalGradient(accent 0.22f→0.05f)`（`ScheduleStackCard.kt:174`、`TimelineDashboard.kt:1156`）而非 `replandStage/replandCard`。渐变步长未受 `DepthToneTest` 约束——体系最核心的"渐变不可被注意到"规则在最显眼的卡片上失效。
- 描边宽 1dp/1.5dp 混用（`TaskExperienceComponents.kt:140` 用 1.5dp，其余 1dp）。

### 1.5 图标：三套并存

`DesignIcons`（自绘）、`PlannerIcons`（自绘第二套）、Material Icons 三套混用：`AgentCenterScreen.kt:595` 用 `PlannerIcons.Voice`，`ScheduleStackCard.kt:223` 用 `DesignIcons.Plus`，`ReplandApp.kt:1036` 用 Material `ArrowBack`。两套自绘图标的存在理由需要裁决，否则线条风格必然漂移。

### 1.6 动效：基础设施顶级，执行层双标

- 优秀：`ReplandMotion` 常量（Press=90/Page=320/Settle=520…）、`Breath=4000` 唯一循环的自我约束（`Motion.kt:56-57`）、`LocalReducedMotion` 走 ContentObserver 监听系统动画开关（`Motion.kt:76-89`）、`snapSpec/settleSpec` 无回弹承诺（`Motion.kt:67-70`）。这些达到了顶级应用的工程标准。
- 违例：
  - `ScheduleStackCard.kt:106-109`：翻卡用 `DampingRatioMediumBouncy`（dampingRatio=0.5，肉眼可见回弹）。
  - `TimelineDashboard.kt:565`：合并张口用 `spring(dampingRatio = 0.65f, stiffness = 360f)`（欠阻尼回弹）。
  - `TimelineDashboard.kt:518,920`：`animDuration = 220` 局部魔数复制了两次；`L575-578` shake 用 50/50/40ms 裸 tween。
  - `TeamDeck.kt:124`：自定 spring 参数（需对照收编）。
  - `DESIGN_LANGUAGE.md §5` 第 3 条明文禁止以上行为——**规范在，未执行**。

### 1.7 深浅色一致性

明暗成对 token 全覆盖，判dark用 `luminance() < 0.2f` 在 `VisualTokens.kt:110` 与 `Depth.kt:37-38` 两处重复实现（应合一）。`isReplandDark` 是 @Composable 判定，非 CompositionLocal，每次取色重算 luminance——正确但应集中。widget 深色有 `values-night` 兜底。整体一致性良好，风险主要在 §1.1 的四个私有 accent（不随主题变，夜间色感脱离体系）。

---

## 2. 信息架构与交互诊断（任务链）

### 2.1 导航骨架：看得见 4 个 tab，枚举里有 6 个

`AppTab` 六目的地（TODAY/TASKS/AGENT/TEAM/TIME/MINE，`ReplandApp.kt:282-292`），底部栏过滤掉 TIME/AGENT 只显示 4 个（`ReplandApp.kt:1045`）。TIME 靠 `MINE → onOpenTimeSettings → selectedTab = TIME` 二级进入（`ReplandApp.kt:1326`），AGENT 的入口链路在底部栏渲染分支里是 `AppTab.AGENT -> Unit` 死分支（`ReplandApp.kt:1071,1289`）。问题：**导航模型与展示模型不一致**，"看不见的页面"会持续积累死代码，也说明 AGENT/TIME 的入口归属尚未想清楚。

### 2.2 FAB 双语义

同一位置同一形态的 FAB：TASKS 页 = 捕获任务（`ReplandApp.kt:1059-1065`），TODAY 页 = 打开群聊助手（`ReplandApp.kt:1067-1069`）。同位不同义是全局最高频按钮上的隐式切换，新用户必然误触。顶级产品的惯例：主 FAB 语义全局恒定（TickTick 的 + 永远是加任务），第二动作换位置（常驻条/头部的独立入口）。

### 2.3 捕获 → 规划：分步合理，强制感偏强

`TaskCaptureSheet`（`TaskExperienceComponents.kt:255-380`）分阶段渐进收集（CAPTURE→INTENT→DETAILS→DURATION），草稿持久化、关停保留（"关闭可保留填写内容，尚未创建任务"，L342）、`rememberSaveable` 自定义 Saver、保存中禁止关闭（L282-289）——捕获链整体是同类最佳水准。两个摩擦点：

- 保存时长无效时强制跳回 DURATION 步（L293-297）：分步流的"退回"打断心流，顶级做法是行内标红 + 保留当前步。
- 自然语言解析（"15:00 复习 90 分钟"）只存在于 Agent 链（`AgentCenterScreen.kt:582` 的 placeholder 示例），捕获链不享受——同一能力两处不同权。

### 2.4 首页结构：状态横幅挤占黄金区

TODAY 页 LazyColumn 顺序（`ReplandApp.kt:2293-2400`）：执行回执 → 计划变更提示 → 预览按钮 → 书堆卡 → 作用域切换（日/周/月）→ 日时间轴。最多可同时堆 3 条全宽状态条，全部以常驻 item 形态占据首屏（回执文案如 L2306 整句 30+ 字）。回执是"读一次就完"的信息，却以列表项常驻——顶级做法是单条可收起状态条或 snackbar，把首屏还给时间轴。contentPadding 已为 FAB 预留 88dp（L2297），说明作者已感知到底部拥挤。

### 2.5 书堆卡：同一对象两种点击语义

`ScheduleStackCard.kt:163-165`：多卡时点击正面卡 = 翻页，仅一卡时点击 = 打开详情。语义靠数量隐式切换、无任何视觉提示，是典型的双语义入口。且翻页没有"还有几张"的指示器，用户无法知道叠了什么。

### 2.6 执行 → 记录 → 回顾：闭环在，但要点三步

`ExecutionSessionDialog`（专注/暂停/结束，`ExecutionSessionDialog.kt:54-199`）→ 回执落在首页 item（`ReplandApp.kt:2303-2312`）→ 手动点"预览剩余任务的新安排" → 预览 → 确认。记录后重排需要 2-3 次显式操作。自动模式下有豁免（L2306-2310 分支），但手动模式每轮重复同样路径——回顾链是重复操作的重灾区。

### 2.7 空态与文案

- 空态走 `EmptyScene` 山水插画 + 功能胶囊（`ScheduleStackCard.kt:88-100`"今天还没有具体安排"+"记一件事"），完全符合设计语言 §4，质量高。
- 文案整体功能化程度高（"重试读取"“保留原计划”“知道了”），无硬叙事无 emoji。一处轻度拟人待裁决：`GroupChatSheet.kt:174` 助手开场白"大家好，我是X。说一句话，我调工具帮你完成"——第一人称自我介绍属拟人化包装边缘，建议改为功能描述（"多智能体协作面板：写入操作需确认后生效"类）。

### 2.8 状态可见性缺口

- 时间轴 hint 提示 3.5s 自动消失（`TimelineDashboard.kt:550-552`），投放失败原因（"放不下""排满了"）一闪即逝，无历史可查。
- 拖拽预览位移（previewShifts）与合并模式（armed）两套视觉语言并存是对的，但"当前处于合并模式"在拖拽初的 24px 死区内无任何提示（`TimelineDashboard.kt:786`）。

---

## 3. 可行性与体感诊断

### 3.1 重组热点（按风险排序）

1. **拖拽路径全量重组**（`TimelineDashboard.kt:536-876`）：`drag` 状态每帧变化 → `DayTimelineGrid` 整个 body 重组。每帧重算：`ScheduleArrangement.previewShifts`（L622-631，`roots.map` 每帧分配 PlannedSegment 列表）、`stackedTops` 让位扫描（L650-664，未 remember）、`units.forEachIndexed` 内为每个 `TimelineBlock` 新建 3 个 lambda（L774-792）——20 参数的 TimelineBlock 因 lambda 不稳定**永不 skip**，30+ 块时中端机有掉帧风险。方向：拖拽 delta 走 `Animatable`/`mutableFloatState` + `graphicsLayer` 直写；previewShifts/stackedTops 按帧降频（`derivedStateOf` 或移动到 LaunchedEffect）；循环内 lambda 提升 + `rememberUpdatedState`（TimelineBlock 内部已有此模式，L935-937，说明作者知道正确做法，只是外层没接上）。
2. **`animateDpAsState` 让位动画**（`TimelineDashboard.kt:949-950, 1136-1140`）：Dp 动画每帧触发布局。y 位移应改 `graphicsLayer translationY`（书堆卡 L153-160 已是正确示范）。
3. **首页每帧重算派生数据**（`ReplandApp.kt:2291`）：`allPlanSegments.filter{...}.groupBy(...)` 未 remember，每次重组重算；L2382-2391 的 `eventObjects` 在 item 内容里 filter+map，同病。
4. **MotionPage 双页同帧组合**（`Motion.kt:91-93` 注释自认）：页面切换时新旧两页各组合一次，页面越重切换越顿。切页卡顿与页重成正比——巨石文件加剧此问题。
5. **启动依赖图**：`ReplandApp` 主构造 17 个 ViewModel 参数（`ReplandApp.kt:317-338`），冷启动即装配全部依赖，未按 tab 懒加载。

### 3.2 列表与时间轴规模

- 首页 LazyColumn item 有稳定 key（"schedule-day" 等，L2293-2400），做法正确。
- 日时间轴是固定 24h 网格 + `verticalScroll`（非 Lazy，`TimelineDashboard.kt:522-534`）——24 小时全量组合。60 块/日时所有块常驻组合树，叠加 §3.1 第 1 条风险放大。窗口裁剪（最多露 6 小时，L533）只裁可视高度不裁组合数量。方向：按 windowStart/windowEnd 裁剪参与组合的 units。
- `hourHeight=48dp`：1 分钟 ≈ 0.8dp，45 分钟课 36dp——36dp 上叠 20dp 子行 + 13dp 图标（L1189,1219），触达与可读性双重不足。

### 3.3 可测指标（建议基线）

| 指标 | 工具 | 目标 |
| --- | --- | --- |
| 拖拽帧率（60 块日） | JankStats / Macrobenchmark | P95 frame ≤ 8ms，0 次 > 32ms |
| 页面切换（TODAY↔TASKS） | Macrobenchmark TTID | P50 ≤ 400ms，无 dropped frame |
| 冷启动 TTID | `adb shell am start -W` + Macrobenchmark | ≤ 800ms |
| 捕获表单首帧 | Macrobenchmark | ≤ 250ms |
| 长列表滚动（500 任务） | JankStats | P95 ≤ 8ms |

---

## 4. 对标差距

| 对标 | 他们的关键决策 | Repland 差距所在层 |
| --- | --- | --- |
| Things 3 | 层级靠字号/留白/一档强调色，色块面几乎为零；空态即引导 | 色彩层：Repland 每卡渐变+描边+语义色三重着色，视觉噪音高一档 |
| Structured | 日时间轴捏合缩放（hourHeight 连续可变）、当前时间指示线常驻、拖拽全程 ghost + 边缘自动滚动 | 交互层：Repland 的 hourHeight 固定 48dp、无捏合、now 指示仅在窗口定位用一次 |
| Sunsama | 每日规划仪式："未排任务 → 拖入时间轴"是显式主流程，回执即下一步引导 | 流程层：Repland 有 eventObjects 放置与 previewShifts（能力都在），但未包装成一条主路径，回顾要手动 2-3 步 |
| TickTick | 全局恒定捕获（+ 键无处不在、自然语言解析统一走一条管道） | 入口层：Repland 捕获有分步无 NL 解析，NL 能力锁在 Agent 链；FAB 双语义 |
| Akiflow | 时间块与任务列表是同一对象的两面（拖走即消失于列表） | 模型层：Repland 的任务/排段双轨清晰，但首页"未排任务"与时间轴的关系视觉上未打通 |

结论：差距不在单点能力（previewShifts、合轨、合并感应、草稿持久化都是对标应用没有的深度），而在**一致性与主路径的收束**——能力散落各处，没有串成一条无歧义的主干道。

---

## 5. 分阶段路线图

> 原则：只增不改（与 DESIGN_LANGUAGE.md 同纲）；每阶段可独立验收、可回滚；P0 全部是"执法"（把已有体系接上），P1 才动结构。

### P0 执法期（不动结构，纯收编）

| # | 问题 | 根因 | 改法 | 验收标准 |
| --- | --- | --- | --- | --- |
| P0-1 | 16 种圆角、无间距标尺 | `ReplandShapes` 有档位但调用处手写字面量 | 逐文件收编到 6/12/16/22/28 五档（9→8 或 10、11→12、14→16、18→16 或 20、24→22、26→28、2/4→按元素归入 6）；新增 `Spacing` token（4/8/12/16/20/24/32） | 全 ui 层 `RoundedCornerShape(` 字面量仅存在于 theme 文件与豁免清单（GroupBlock 进度条 2-6dp 微件类）；`grep RoundedCornerShape ui/ | wc -l` 降幅 ≥ 80% |
| P0-2 | bouncy 回弹 + 动效魔数 | 翻卡/合并动画写在组件里，绕过 `ReplandMotion` | 书堆卡改 `settleSpec()`（0.85 阻尼，保留"落下"感无二次弹）；合并 spring 改 `snapSpec()` 或 settleSpec；220/150/50ms 收编进 `ReplandMotion`（新增 `Drag=150`/`Snap=220`）；shake 保留（功能性反馈非装饰） | `grep -r "Bouncy\|dampingRatio = 0\.[0-6]" ui/` 零命中；`grep "tween(\d" ui/` 仅 Motion.kt 与豁免清单命中；书堆卡翻动 60fps 录屏无过冲帧 |
| P0-3 | 裸色值越轨（含蓝紫 0xFF4E64B5） | 时间轴四 accent 早于语义色体系建立 | 四 accent 收编为 `replandAccent` 第 5-8 槽或并入 SemanticStateColors；widget colors.xml 清模板色、`repland_window_background` 对齐 WarmPaper | ui 层（theme/LandscapeArtwork 豁免外）`Color(0x` 计数 = 0；`AccentPaletteTest` 同等对比度单测覆盖新 accent；widget 与 app 首屏纸色肉眼一致 |
| P0-4 | 体系组件无人用 | 手写 Surface+Brush 习惯先行 | 首页焦点卡（书堆卡底）改 `replandStage`，普通卡改 `replandCard`，输入区改 `replandWell`；书堆卡 shadowElevation 移除，用 ToneLadder 光向替代 | 书堆卡渐变步长进 `DepthToneTest` 约束范围；ui 层 `Brush.verticalGradient(` 手写调用（theme 外）≤ 3 处豁免 |

### P1 结构期（动结构，按在途工作节奏合流）

| # | 问题 | 根因 | 改法 | 验收标准 |
| --- | --- | --- | --- | --- |
| P1-1 | ReplandApp.kt 335KB 巨石 | 页面容器/回执/导航/杂项全在一文件 | 与首页重组在途工作合流：TodayScreen/TasksScreen/MineScreen/回执横幅族逐个拆出（每拆一个跑全量 UI 测试）；17 个 ViewModel 改按 tab 的 CompositionLocal/局部获取 | 单文件 ≤ 1000 行；`ReplandApp` 构造参数 ≤ 6；androidTest 全绿 |
| P1-2 | 日时间轴触达天花板 | hourHeight 固定 + 手势叠在 36dp 卡上 | 捏合两档缩放（48/96dp，缩放中记忆 focal）；点击热区外扩整行宽；子行高 20→28dp；窗口裁剪参与组合的 units | 45min 块单指长按拖动成功率 ≥ 98%（埋点）；96dp 档下子行文字 ≥ 12sp 可读；60 块日 P95 ≤ 8ms |
| P1-3 | 首页状态横幅挤占首屏 | 回执以常驻 item 呈现 | 回执/提示合并为单条可收起状态条（功能文案不变，遵守文案预算 §8），详情点开见 | 首屏可见：书堆卡 + 时间轴 ≥ 4 小时窗；回执消失后 24h 内可从"系统"回看 |
| P1-4 | 拖拽帧率风险 | 每帧全量重组 | §3.1 第 1 条三改：graphicsLayer 直写 delta、previewShifts/stackedTops 降频、循环 lambda remember | JankStats 拖拽 60 块 P95 ≤ 8ms；Compose Compiler 指标显示 TimelineBlock skippable |
| P1-5 | 记录→回顾重复操作 | 回执后重排需 2-3 步 | 手动模式回执底部直出"新安排预览"内联卡（预览能力已存在，只改呈现位置） | 记录→确认新安排 ≤ 2 次点击；回执内联预览与独立预览行为一致（同一 ViewModel 状态源） |

### P2 战略期（模型与入口）

| # | 问题 | 改法 | 验收标准 |
| --- | --- | --- | --- |
| P2-1 | FAB 双语义 | FAB 全局恒定 = 捕获；TODAY 助手入口改为书堆卡状态行旁常驻胶囊（"安排"） | 新手任务测试：首次使用 0 误触；两入口埋点转化率不降 |
| P2-2 | AppTab 与底部栏不一致 | TIME/AGENT 正式收编（AGENT 并入 TODAY 助手面板或恢复 tab；TIME 恢复或从枚举移除走独立页面栈） | AppTab 枚举成员 = 底部栏可见项；`AppTab.AGENT -> Unit` 死分支清零 |
| P2-3 | AccentPalette 备而未接 | 按规划接线"我的 → 主题色"（BAMBOO 默认），三 ramp 均过对比度单测 | 三主题 × 明暗六组合截图走查；AccentPaletteTest 扩展为门禁 |
| P2-4 | 三套图标体系 | 裁决：DesignIcons 为唯一自绘系，PlannerIcons 并入或声明存在理由 | ui 层 Material Icons 仅剩系统语义（返回/关闭） |
| P2-5 | NL 解析两处不同权 | 捕获链接入同一 NL 解析管道（"15:00 复习 90 分钟"直接结构化） | 捕获含时间/时长的文本 1 步出结构化草稿，正确率 ≥ Agent 链 |

---

## 6. 审计方法与限制

- 全部结论基于源码静态阅读（theme 全量 + 时间轴/书堆卡/捕获链/首页/Agent/widget 关键区段精读 + ui 层全量 grep 量化）。
- 未做模拟器截图与 Gradle 构建（本轮限制）。若需补充视觉走查，需要：一台 API 34+ 模拟器 + `assembleDebug` 通过 + 三个 ThemeAccent × 明暗的组合截图脚本。
- 在途未提交工作（首页重组、日视图时间轴化、卡片 morph、拖拽合块）按刻意工作审计，P1-1/P1-2 的实施节奏应与它们合流而非并行覆盖。
