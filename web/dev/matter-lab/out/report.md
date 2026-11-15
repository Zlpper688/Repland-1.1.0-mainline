# Matter lab report

- corpus: 500
- rule coverage: 94.2%
- rule covered accuracy: 99.8%

## Mandatory AI triggers

| reason | cases | rule coverage | rule covered accuracy | unsafe |
| --- | ---: | ---: | ---: | ---: |
| multi-intent | 9 | 0% | — | 0 |
| correction | 3 | 0% | — | 0 |
| ambiguous | 3 | 0% | — | 0 |
| missing-name | 3 | 0% | — | 0 |
| unsupported-recurrence | 2 | 0% | — | 0 |
| conditional | 2 | 0% | — | 0 |
| fixed-block | 2 | 0% | — | 0 |
| english | 2 | 0% | — | 0 |
| cross-midnight | 1 | 0% | — | 0 |
| relative-anchor | 1 | 0% | — | 0 |
| pronoun | 1 | 0% | — | 0 |
| ambiguous-clock | 1 | 0% | — | 0 |
| english-intent | 1 | 0% | — | 0 |
| bulk-move | 1 | 0% | — | 0 |
| people | 1 | 0% | — | 0 |

## Tag boundary

| tag | cases | rule coverage | rule covered accuracy | AI accuracy |
| --- | ---: | ---: | ---: | ---: |
| correction | 2 | 0% | — | — |
| cross-midnight | 1 | 0% | — | — |
| anchor | 1 | 0% | — | — |
| unsupported-recurrence | 2 | 0% | — | — |
| ambiguous | 2 | 0% | — | — |
| missing-task | 2 | 0% | — | — |
| pronoun | 1 | 0% | — | — |
| needs-draft | 1 | 0% | — | — |
| conditional | 2 | 0% | — | — |
| multi-intent | 2 | 0% | — | — |
| overlap | 1 | 0% | — | — |
| fixed-block | 2 | 0% | — | — |
| date-format | 1 | 0% | — | — |
| english-unit | 1 | 0% | — | — |
| english | 1 | 0% | — | — |
| self-correction | 1 | 0% | — | — |
| bulk-move | 1 | 0% | — | — |
| overdue | 1 | 0% | — | — |
| constraint | 2 | 50% | 100% | — |
| ai | 84 | 67% | 98% | — |
| due-date | 3 | 67% | 100% | — |
| review | 42 | 86% | 100% | — |
| self-report | 42 | 86% | 100% | — |
| routine | 92 | 99% | 99% | — |
| rules | 333 | 100% | 100% | — |
| explicit | 120 | 100% | 100% | — |
| assignment | 80 | 100% | 100% | — |
| exam | 50 | 100% | 100% | — |
| meeting | 51 | 100% | 100% | — |
| competition | 66 | 100% | 100% | — |
| errand | 67 | 100% | 100% | — |
| habit | 45 | 100% | 100% | — |
| generic | 45 | 100% | 100% | — |
| date-only | 61 | 100% | 100% | — |
| capture | 52 | 100% | 100% | — |
| daily | 22 | 100% | 100% | — |
| auto-time | 43 | 100% | 100% | — |
| workdays | 20 | 100% | 100% | — |
| explicit-time | 40 | 100% | 100% | — |
| weekend | 20 | 100% | 100% | — |

## Rule failures

- A038 [adversarial] 明天下午三点写报告两个小时，然后四点开组会一小时 → [{"action":"create","name":"写报告 然后四点开组会一小时","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":120}] reasons=multi-intent
- A039 [adversarial] 明天下午三点开会，顺便把周报写了 → [{"action":"create","name":"开会 顺便 周报写","date":"2026-11-16","startMinute":900,"endMinute":null,"durationMinutes":60}] reasons=multi-intent
- A051 [adversarial] 下周一到周三每天上午复习高数 → [{"action":"routine","name":"每天上午复习高数","date":null,"startMinute":null,"endMinute":null,"durationMinutes":90,"weekdays":[1,2,3]}] reasons=
- A060 [adversarial] 这周数学作业没交，老师说明天要补交 → [{"action":"progress","name":"数学作业 交 老师说 要补交","date":null,"startMinute":null,"durationMinutes":null,"progress":null},{"action":"create","name":"数学作业 交 老师说 要补交","date":"2026-11-16","startMinute":null,"durationMinutes":null}] reasons=people
