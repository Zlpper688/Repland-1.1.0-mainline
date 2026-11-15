# 规则 vs 线上 AI：边界分析

- 语料：500 条
- 规则已覆盖：471 条（94%），覆盖内正确 470 条（100%）
- AI 已跑：499 条，正确 340 条（68%），危险错误 59 条
- 混合策略（规则能答用规则，拒答才调 AI）：97%
- 混合架构 v3（AI 出语义 + 规则补日历与协议守卫）：97.4%（486/499），危险错误 0
- AI 平均延迟：2843ms，中位 2385ms
- AI 返回来源：ai 447，clarify 51，error 1；另有 0 条被静默降级成本地解析

## 结论

- 规则在这套语料上 99.2%（含正确追问），覆盖内 99.8%，0 条危险动作。
- 同一语料线上 AI 68.1%，59 条危险；失分集中在日历算术（date 85 次、weekdays 66 次）和复盘/顺延语义，不是语言理解。
- 29 条硬边界里：金标为追问的 15 条，规则 15/15 追问正确，AI 10/14；金标要规划的 14 条，规则内部草稿 11/14，AI 6/14。
- AI 唯一独占的场景是多意图拆分（A038/A039：规则把第二句吞进任务名）；其余边界上 AI 反而更差。
- 因此强制 AI 点只设在多意图 / 无安全解析的叙述句；其余句子用规则草稿，AI 在线时做校验不接管。AI 不可用或校验不通过时追问，不用本地猜。
- 局限：语料与规则共同设计，规则数字偏乐观；开放域表现需要下一轮冷启动语料复测。

## 对照分桶

| 分桶 | 条数 | 含义 |
| --- | ---: | --- |
| 两边都对 | 338 | 规则与 AI 都命中 |
| 规则赢 | 157 | 规则对、AI 错 |
| AI 赢 | 2 | 规则拒答或答错、AI 对 |
| 两边都错 | 2 | 需要补语料或改协议 |
| 本地降级 | 0 | 端点额度耗尽或模型报错 |
| 缺 AI 结果 | 1 | 未跑或中断 |

## 分类

| 类别 | 总数 | 规则正确 | 规则覆盖内正确 | AI 正确 | AI 危险错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| create_explicit | 120 | 100% | 100% | 81% | 15 |
| create_date_only | 60 | 100% | 100% | 68% | 13 |
| capture_no_date | 50 | 100% | 100% | 96% | 0 |
| routine | 80 | 100% | 100% | 66% | 16 |
| move | 50 | 100% | 100% | 88% | 6 |
| status | 40 | 100% | 100% | 50% | 6 |
| review | 40 | 100% | 100% | 10% | 0 |
| adversarial | 60 | 93% | 97% | 56% | 3 |

## 触发原因 → 应该谁来做

| 触发原因 | 条数 | 规则拒答 | 规则覆盖内正确 | 规则危险 | AI 正确 | AI 危险 | 建议 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| multi-intent | 9 | 9 | — | 0 | 33% | 0 | ai-required |
| correction | 3 | 3 | — | 0 | 100% | 0 | ai-required |
| ambiguous | 3 | 3 | — | 0 | 100% | 0 | ai-required |
| missing-name | 3 | 3 | — | 0 | 100% | 0 | ai-required |
| unsupported-recurrence | 2 | 2 | — | 0 | 50% | 0 | ai-required |
| conditional | 2 | 2 | — | 0 | 50% | 0 | ai-required |
| fixed-block | 2 | 2 | — | 0 | 100% | 0 | ai-required |
| english | 2 | 2 | — | 0 | 50% | 0 | ai-required |
| cross-midnight | 1 | 1 | — | 0 | 0% | 0 | ai-required |
| relative-anchor | 1 | 1 | — | 0 | 0% | 0 | ai-required |
| pronoun | 1 | 1 | — | 0 | 100% | 0 | ai-required |
| ambiguous-clock | 1 | 1 | — | 0 | 100% | 0 | ai-required |
| english-intent | 1 | 1 | — | 0 | 0% | 0 | ai-required |
| bulk-move | 1 | 1 | — | 0 | 100% | 0 | ai-required |
| people | 1 | 1 | — | 0 | 0% | 0 | ai-required |

## 边界判决

| 触发原因 | 条数 | 规则内部解析对 | AI 对 | AI 危险 | 判决 |
| --- | ---: | ---: | ---: | ---: | --- |
| <covered> | 471 | 100% | 69% | 59 | 规则自足 |
| multi-intent | 9 | 78% | 33% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| correction | 3 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| ambiguous | 3 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| missing-name | 3 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| unsupported-recurrence | 2 | 100% | 50% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| conditional | 2 | 100% | 50% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| fixed-block | 2 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| english | 2 | 100% | 50% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| cross-midnight | 1 | 100% | 0% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| relative-anchor | 1 | 100% | 0% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| pronoun | 1 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| ambiguous-clock | 1 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| english-intent | 1 | 100% | 0% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| bulk-move | 1 | 100% | 100% | 0 | 规则内部草稿不弱于 AI：AI 只做校验 |
| people | 1 | 0% | 0% | 0 | 双方都答不对：只能追问 |

## 混合架构 v3

分层：AI 只产出语义（名称、动作、意图、多意图拆分），日期/时刻/时长/周几/重复由确定性层计算；复盘与顺延走协议改写；AI 空结果时用规则兜底。

| 来源 | 次数 | 含义 |
| --- | ---: | --- |
| rewritten | 15 | 复盘句改写为进度更新 |
| postponed | 5 | 顺延句改写为推迟 |
| slots | 427 | 日历槽位由规则覆盖 |
| floor | 71 | AI 空结果，规则兜底 |
| recurrence | 14 | 重复事项防塌缩守卫 |

- 准确率：486/499 = 97.4%，危险错误 0
- 分类：create_explicit 120/120，create_date_only 60/60，capture_no_date 50/50，routine 80/80，move 50/50，status 40/40，review 38/40，adversarial 48/59

剩余失败：
- V008 报名蓝桥杯没做完，明天再给我两小时 → 金标 [] / 混合 [{"action":"update","name":"报名蓝桥杯","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":false}]
- V040 昨天没去教务处盖章，今天补上 → 金标 [] / 混合 [{"action":"schedule","name":"去教务处盖章","date":"2026-11-15","startMinute":760,"endMinute":null,"durationMinutes":45,"weekdays":null,"move":true,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"37a3763b-fdc9-40d3-b706-6a68331bbe77","date":"2026-11-15","startMinute":760,"endMinute":805,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A004 晚上十一点到凌晨一点打游戏 → 金标 [] / 混合 [{"action":"create","name":"打游戏","date":null,"startMinute":null,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":false}]
- A010 每月5号交房租 → 金标 [] / 混合 [{"action":"create","name":"每月5号交房租","date":null,"startMinute":null,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":false}]
- A014 这周三的组会改到周四下午四点 → 金标 [{"action":"schedule","name":"组会","date":"2026-11-19","startMinute":960,"durationMinutes":null,"move":true}] / 混合 [{"action":"create","name":"组会","date":"2026-11-19","startMinute":960,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"a0db8326-9c53-4b3d-b521-d416d5e940a6","date":"2026-11-19","startMinute":960,"endMinute":1020,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A017 数学只写了一半，明天再给我一个小时 → 金标 [{"action":"progress","name":"数学","progress":50,"date":null,"startMinute":null,"durationMinutes":null}] / 混合 [{"action":"create","name":"数学","date":"2026-11-16","startMinute":480,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"550eed9e-517e-4a9f-a224-3b72ef7eb197","date":"2026-11-16","startMinute":480,"endMinute":540,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A031 如果周五下雨就不跑步了 → 金标 [] / 混合 []
- A041 别把任务排得太满，留半小时缓冲 → 金标 [] / 混合 []
- A049 tomorrow 3pm meeting 1h → 金标 [] / 混合 [{"action":"create","name":"meeting","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"cb4d975e-274b-40b0-bdc6-b6a2fd90b776","date":"2026-11-16","startMinute":900,"endMinute":960,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A051 下周一到周三每天上午复习高数 → 金标 [{"action":"routine","name":"复习高数","date":null,"startMinute":600,"durationMinutes":null,"weekdays":[1,2,3]}] / 混合 [{"action":"routine","name":"复习高数","date":null,"startMinute":480,"endMinute":570,"durationMinutes":90,"weekdays":[1,2,3],"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":true}]
- A053 今天晚上不排事，我想休息 → 金标 [] / 混合 []
- A059 我昨天没写报告，今天补上 → 金标 [{"action":"progress","name":"写报告","date":null,"startMinute":null,"durationMinutes":null}] / 混合 [{"action":"create","name":"补写报告","date":"2026-11-15","startMinute":null,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"cba15f21-4937-4b78-aca8-3b04ba29aac2","date":"2026-11-15","startMinute":480,"endMinute":600,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- A060 这周数学作业没交，老师说明天要补交 → 金标 [{"action":"create","name":"交数学作业","dueDate":"2026-11-16","date":null,"startMinute":null,"durationMinutes":null}] / 混合 [{"action":"progress","name":"这周数学作业没交 老师说 要补交","date":null,"startMinute":null,"durationMinutes":null,"move":false,"progress":null},{"action":"create","name":"这周数学作业没交 老师说 要补交","date":"2026-11-16","startMinute":null,"durationMinutes":null,"move":false}]

## 规则赢的例子
- E011 麻烦大后天中午十二点安排一下买教材，两小时吧 → 规则 [{"action":"create","name":"买教材","date":"2026-11-18","startMinute":720,"endMinute":null,"durationMinutes":120}] / AI [{"action":"create","name":"买教材","date":"2026-11-17","startMinute":720,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"5e0aa5d3-7d84-4215-b37e-8b1bcad989a5","date":"2026-11-17","startMinute":720,"endMinute":840,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- E012 下下周一20:30跑步谢谢，大概三个钟头 → 规则 [{"action":"create","name":"跑步","date":"2026-11-23","startMinute":1230,"endMinute":null,"durationMinutes":180}] / AI [{"action":"create","name":"跑步","date":"2026-12-07","startMinute":1230,"endMinute":null,"durationMinutes":180,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"69e742bf-d501-4f01-9705-48888ee0933c","date":"2026-12-07","startMinute":1230,"endMinute":1410,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- E018 大后天下午四点写周报谢谢，大概三个钟头 → 规则 [{"action":"create","name":"写周报","date":"2026-11-18","startMinute":960,"endMinute":null,"durationMinutes":180}] / AI [{"action":"create","name":"写周报","date":"2026-11-19","startMinute":960,"endMinute":null,"durationMinutes":180,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"85cbcda4-f735-46df-b33a-2df0ef365a4d","date":"2026-11-19","startMinute":960,"endMinute":1140,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- E022 交数学作业，明天上午十点，一个半小时谢谢 → 规则 [{"action":"create","name":"交数学作业","date":"2026-11-16","startMinute":600,"endMinute":null,"durationMinutes":90}] / AI [{"action":"create","name":"交数学作业","date":"2026-11-16","startMinute":630,"endMinute":null,"durationMinutes":90,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"f4f86c5c-7b3f-4e87-919c-0b9ca0e04a24","date":"2026-11-16","startMinute":630,"endMinute":720,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- E026 麻烦讨论项目，下下周一下午四点，四十分钟谢谢 → 规则 [{"action":"create","name":"讨论项目","date":"2026-11-23","startMinute":960,"endMinute":null,"durationMinutes":40}] / AI [{"action":"create","name":"讨论项目","date":"2026-11-17","startMinute":960,"endMinute":null,"durationMinutes":40,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"21f293b5-785a-4dd6-b357-e57b40b9d713","date":"2026-11-17","startMinute":960,"endMinute":1000,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- E030 下周三上午十点去教务处盖章谢谢，大概三个钟头 → 规则 [{"action":"create","name":"去教务处盖章","date":"2026-11-18","startMinute":600,"endMinute":null,"durationMinutes":180}] / AI [{"action":"create","name":"教务处盖章","date":"2026-11-18","startMinute":600,"endMinute":null,"durationMinutes":90,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"350f702d-4ef0-4a93-8d70-96dfe01a209e","date":"2026-11-18","startMinute":600,"endMinute":690,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]

## AI 赢的例子
- A038 明天下午三点写报告两个小时，然后四点开组会一小时 → 规则 拒答(multi-intent) / AI [{"action":"create","name":"写报告","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"c298bfdd-393e-4c16-a93b-fe3b56bff9fe","date":"2026-11-16","startMinute":900,"endMinute":1020,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false},{"action":"create","name":"组会","date":"2026-11-16","startMinute":960,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"ef958ef4-b390-4fe2-96d2-f501b80d73ae","date":"2026-11-16","startMinute":960,"endMinute":1020,"trackId":"parallel-2","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}] (ai)
- A039 明天下午三点开会，顺便把周报写了 → 规则 拒答(multi-intent) / AI [{"action":"create","name":"开会","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"82123453-bdd6-4ee5-bb62-0ede9194570a","date":"2026-11-16","startMinute":900,"endMinute":960,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false},{"action":"create","name":"写周报","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":90,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"9a26b888-91f6-47b9-87c2-fa6419701b42","date":"2026-11-16","startMinute":975,"endMinute":1065,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}] (ai)

## 两边都错的例子
- A051 下周一到周三每天上午复习高数 → 规则 [{"action":"routine","name":"每天上午复习高数","date":null,"startMinute":null,"endMinute":null,"durationMinutes":90,"weekdays":[1,2,3]}] / AI [{"action":"routine","name":"复习高数","date":null,"startMinute":480,"endMinute":570,"durationMinutes":90,"weekdays":[1,2,3],"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":true}] (ai)
- A060 这周数学作业没交，老师说明天要补交 → 规则 拒答(people) / AI [] (clarify)

## AI 错在哪个槽位

- 有效 AI 样本：499 条，整句错 159 条
- 槽位错误：date 85，action 70，weekdays 66，duration 51，start 50，name 48，dueMinute 2，progress 1；整条缺失 48；意图错 6

| 类别 | 样本 | 整句错 | date | start | duration | name | action | weekdays | 缺失 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| create_explicit | 120 | 23 | 21 | 7 | 7 | 6 | 6 | 6 | 6 |
| create_date_only | 60 | 19 | 19 | 6 | 6 | 6 | 6 | 6 | 6 |
| capture_no_date | 50 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| routine | 80 | 27 | 11 | 11 | 11 | 11 | 25 | 27 | 11 |
| move | 50 | 6 | 6 | 0 | 0 | 0 | 0 | 0 | 0 |
| status | 40 | 20 | 16 | 14 | 14 | 14 | 19 | 14 | 14 |
| review | 40 | 36 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| adversarial | 59 | 26 | 10 | 10 | 11 | 9 | 12 | 11 | 9 |

## 日期算术错误样例
- E011 麻烦大后天中午十二点安排一下买教材，两小时吧 → 金标 2026-11-18 / AI 2026-11-17
- E012 下下周一20:30跑步谢谢，大概三个钟头 → 金标 2026-11-23 / AI 2026-12-07
- E018 大后天下午四点写周报谢谢，大概三个钟头 → 金标 2026-11-18 / AI 2026-11-19
- E026 麻烦讨论项目，下下周一下午四点，四十分钟谢谢 → 金标 2026-11-23 / AI 2026-11-17
- E033 给我把背单词放到下下周一早上七点，一小时吧 → 金标 2026-11-23 / AI 2026-12-06
- E039 给我把做PPT放到大后天晚上八点，一小时吧 → 金标 2026-11-18 / AI 2026-11-17
- E042 下周五下午四点交数学作业谢谢，大概三个钟头 → 金标 2026-11-20 / AI undefined
- E043 明天中午十二点复习高数，半小时吧 → 金标 2026-11-16 / AI undefined
- E053 麻烦大后天下午三点半安排一下背单词，两小时吧 → 金标 2026-11-18 / AI 2026-11-17
- E054 下下周一上午十点练吉他谢谢，大概三个钟头 → 金标 2026-11-23 / AI 2026-11-24
- E060 大后天20:30准备答辩谢谢，大概三个钟头 → 金标 2026-11-18 / AI 2026-11-19
- E061 下下周一下午三点半写实验报告，半小时吧 → 金标 2026-11-23 / AI 2026-12-07

## 规则拒答的句子，AI 是否接住
- V021 写实验报告没搞完，后天再排四十分钟 [multi-intent] → AI 错：[{"action":"schedule","name":"写实验报告","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":40,"weekdays":null,"move":true,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"b6948651-e6b0-46c9-9bf5-371d61381213","date":"2026-11-17","startMinute":480,"endMinute":520,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- V023 复习高数没搞完，后天再排四十分钟 [multi-intent] → AI 对：[{"action":"schedule","name":"复习高数","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":40,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"caa88e1e-bac8-4fb9-8241-05066461e09a","date":"2026-11-17","startMinute":480,"endMinute":520,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- V024 准备英语六级没搞完，后天再排两小时 [multi-intent] → AI 错：[{"action":"schedule","name":"准备英语六级","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"d68aa7c2-ae4e-4945-8589-1ff2a1d8c50a","date":"2026-11-17","startMinute":480,"endMinute":600,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- V025 开组会没搞完，后天再排四十分钟 [multi-intent] → AI 错：[]
- V027 准备数模比赛没搞完，后天再排四十分钟 [multi-intent] → AI 错：[{"action":"schedule","name":"准备数模比赛","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":40,"weekdays":null,"move":true,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"235e54fa-e6fb-4c93-a010-9d3b51dbba2d","date":"2026-11-17","startMinute":480,"endMinute":520,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- V029 取快递没搞完，后天再排四十分钟 [multi-intent] → AI 错：[{"action":"schedule","name":"取快递","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":40,"weekdays":null,"move":true,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"1bbc68a7-eb0b-497d-a67f-36deb5cb0b87","date":"2026-11-17","startMinute":480,"endMinute":520,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- A001 不是周三，是周四下午三点开组会 [correction] → AI 对：[{"action":"create","name":"组会","date":"2026-11-19","startMinute":900,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"023813db-9605-405f-bbb1-333881012a38","date":"2026-11-19","startMinute":900,"endMinute":960,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A002 别排上午，下午再写报告 [multi-intent] → AI 错：[]
- A004 晚上十一点到凌晨一点打游戏 [cross-midnight] → AI 错：[{"action":"create","name":"打游戏","date":null,"startMinute":null,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":false}]
- A005 每天晚饭后跑半小时 [relative-anchor] → AI 错：[]
- A009 隔天跑一次步 [unsupported-recurrence] → AI 对：[]
- A010 每月5号交房租 [unsupported-recurrence] → AI 错：[{"action":"create","name":"每月5号交房租","date":null,"startMinute":null,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":null,"autoPicked":false}]
- A021 帮我排一下下周 [ambiguous] → AI 对：[]
- A022 最近有点忙，你看着办 [ambiguous] → AI 对：[]
- A023 明天下午三点 [missing-name] → AI 对：[]
- A024 把那个会挪到四点 [pronoun,ambiguous-clock] → AI 对：[]
- A025 不是明天，是后天 [missing-name,correction] → 未跑
- A027 只记着，别排 [missing-name] → AI 对：[]
- A031 如果周五下雨就不跑步了 [conditional] → AI 错：[]
- A032 除非作业写完，否则不玩游戏 [conditional] → AI 对：[]
- A038 明天下午三点写报告两个小时，然后四点开组会一小时 [multi-intent] → AI 对：[{"action":"create","name":"写报告","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"c298bfdd-393e-4c16-a93b-fe3b56bff9fe","date":"2026-11-16","startMinute":900,"endMinute":1020,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false},{"action":"create","name":"组会","date":"2026-11-16","startMinute":960,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"ef958ef4-b390-4fe2-96d2-f501b80d73ae","date":"2026-11-16","startMinute":960,"endMinute":1020,"trackId":"parallel-2","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A039 明天下午三点开会，顺便把周报写了 [multi-intent] → AI 对：[{"action":"create","name":"开会","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"82123453-bdd6-4ee5-bb62-0ede9194570a","date":"2026-11-16","startMinute":900,"endMinute":960,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false},{"action":"create","name":"写周报","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":90,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"9a26b888-91f6-47b9-87c2-fa6419701b42","date":"2026-11-16","startMinute":975,"endMinute":1065,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":false},"autoPicked":false}]
- A042 把周三的课取消 [fixed-block] → AI 对：[]
- A043 周三下午的课改到周四 [fixed-block] → AI 对：[]
- A048 11/20 15:00 写报告 120min [english] → AI 对：[{"action":"create","name":"写报告","date":"2026-11-20","startMinute":900,"endMinute":null,"durationMinutes":120,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"44b47db4-15fb-415d-86bb-739b78d83d1c","date":"2026-11-20","startMinute":900,"endMinute":1020,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A049 tomorrow 3pm meeting 1h [english-intent,english] → AI 错：[{"action":"create","name":"meeting","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"cb4d975e-274b-40b0-bdc6-b6a2fd90b776","date":"2026-11-16","startMinute":900,"endMinute":960,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A050 下周三下午三点开会，哦不，是四点 [correction] → AI 对：[{"action":"create","name":"开会","date":"2026-11-18","startMinute":960,"endMinute":null,"durationMinutes":60,"weekdays":null,"move":false,"progress":null,"dueDate":null,"dueMinute":null,"actualMinutes":null,"placement":{"id":"6d7bfb3f-100c-4219-9506-48796f016991","date":"2026-11-18","startMinute":960,"endMinute":1020,"trackId":"focus","nestedUnderId":null,"nestedUnderTitle":null,"fromUser":true},"autoPicked":false}]
- A054 帮我把这周的安排都往后挪一天 [bulk-move,ambiguous] → AI 对：[]
- A060 这周数学作业没交，老师说明天要补交 [people] → AI 错：[]

