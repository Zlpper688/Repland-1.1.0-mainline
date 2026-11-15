package com.swan1127.repland.domain.team

/**
 * 统一诊断层（基础设施）：内核只描述「哪里不对、影响谁、建议怎么办」，
 * 不直接抛异常，也不替队长做决定。
 *
 * 三个消费者共用同一份结构：助手工具（digest 成一句话）、UI（按 severity 上色）、
 * 周报/风险雷达（按 objectKind 聚合）。新增内核规则时只追加 code，不改签名。
 */

enum class DiagnosticSeverity { INFO, WARN, ERROR }

enum class DiagnosticObject { TASK, PHASE, MEMBER, BLOCK, TEAM, TEMPLATE }

data class TeamDiagnostic(
    val code: String,
    val severity: DiagnosticSeverity,
    val target: DiagnosticObject,
    val targetId: String?,
    val message: String,
    val advice: String? = null,
)

object TeamDiagnostics {

    // 依赖与期限
    const val DEPENDENCY_CYCLE = "dependency_cycle"
    const val DEADLINE_NO_ANCHOR = "deadline_no_anchor"
    const val DEADLINE_PAST_COMPETITION = "deadline_past_competition"
    const val DEADLINE_PAST_PHASE = "deadline_past_phase"

    // 环节与关卡
    const val STAGE_OVERLOADED = "stage_overloaded"
    const val STAGE_GATE_TIGHT = "stage_gate_tight"
    const val GATE_BLOCKED = "gate_blocked"
    const val GATE_AT_RISK = "gate_at_risk"

    // 排期结果
    const val TASK_UNASSIGNED = "task_unassigned"
    const val TASK_WAITING_UPSTREAM = "task_waiting_upstream"
    const val TASK_NO_MEMBER = "task_no_member"
    const val TASK_PAST_DEADLINE = "task_past_deadline"
    const val SLOT_CONFLICT = "slot_conflict"
    const val LOW_CONFIDENCE = "low_confidence"

    // 模板与批量操作
    const val TEMPLATE_SKIPPED = "template_skipped"

    fun fromDeadline(issues: List<DeadlineEngine.DeadlineIssue>): List<TeamDiagnostic> = issues.map { issue ->
        when (issue.kind) {
            DeadlineEngine.IssueKind.CYCLE ->
                TeamDiagnostic(
                    DEPENDENCY_CYCLE, DiagnosticSeverity.ERROR, DiagnosticObject.TASK, issue.taskId,
                    issue.message, "已忽略环内依赖、先按无环部分排期；建议修正任务依赖后重排",
                )
            DeadlineEngine.IssueKind.MISSING_ANCHOR ->
                TeamDiagnostic(
                    DEADLINE_NO_ANCHOR, DiagnosticSeverity.WARN, DiagnosticObject.TASK, issue.taskId,
                    issue.message, "补一个阶段截止或硬截止，期限才能倒算",
                )
            DeadlineEngine.IssueKind.OVER_COMPETITION_DEADLINE ->
                TeamDiagnostic(
                    DEADLINE_PAST_COMPETITION, DiagnosticSeverity.WARN, DiagnosticObject.TASK, issue.taskId,
                    issue.message, "把任务前移，或降级为 Won't 再排",
                )
            DeadlineEngine.IssueKind.OVER_PHASE_END ->
                TeamDiagnostic(
                    DEADLINE_PAST_PHASE, DiagnosticSeverity.WARN, DiagnosticObject.TASK, issue.taskId,
                    issue.message, "缩小范围或调整阶段窗口，别把倒算截止顶到阶段外",
                )
        }
    }

    fun fromStageLoads(loads: List<StageLoad>): List<TeamDiagnostic> = loads.mapNotNull { load ->
        when {
            load.demandHours <= 0.0 -> null
            load.risk == StageRisk.OVER -> TeamDiagnostic(
                STAGE_OVERLOADED, DiagnosticSeverity.WARN, DiagnosticObject.PHASE, load.phaseId,
                "环节「${load.name}」差 ${StagePlanner.hours(-load.marginHours)} 小时：" +
                    "需 ${StagePlanner.hours(load.demandHours)} 小时，${load.windowDays} 天窗口最多 ${StagePlanner.hours(load.capacityHours)} 小时",
                "加人、延窗口或砍范围——先动范围，别硬塞",
            )
            load.risk == StageRisk.TIGHT && load.isGate -> TeamDiagnostic(
                STAGE_GATE_TIGHT, DiagnosticSeverity.INFO, DiagnosticObject.PHASE, load.phaseId,
                "关卡「${load.name}」只剩 ${StagePlanner.hours(load.marginHours)} 小时余量，提交前容易挤",
                "预留缓冲，别把提交压到最后一天",
            )
            else -> null
        }
    }

    fun fromGates(gates: List<GateReadiness>): List<TeamDiagnostic> = gates.mapNotNull { gate ->
        when (gate.state) {
            GateState.READY, GateState.ON_TRACK -> null
            GateState.AT_RISK -> TeamDiagnostic(
                GATE_AT_RISK, DiagnosticSeverity.WARN, DiagnosticObject.PHASE, gate.phaseId,
                "关卡「${gate.name}」${gate.advice}", "先处理 ${gate.pendingTasks.take(2).joinToString("、")}",
            )
            GateState.BLOCKED -> TeamDiagnostic(
                GATE_BLOCKED, DiagnosticSeverity.ERROR, DiagnosticObject.PHASE, gate.phaseId,
                "关卡「${gate.name}」${gate.advice}", "先处理 ${gate.pendingTasks.take(2).joinToString("、")}，再谈下一段",
            )
        }
    }

    /** 排期过程中记下的「这项为什么没排上」，一项一条，不合并。 */
    fun unassigned(
        taskId: String,
        title: String,
        code: String,
        detail: String,
        advice: String,
    ): TeamDiagnostic = TeamDiagnostic(
        code, DiagnosticSeverity.WARN, DiagnosticObject.TASK, taskId, "「$title」$detail", advice,
    )

    fun conflicts(count: Int, memberId: String?): TeamDiagnostic? {
        if (count <= 0) return null
        return TeamDiagnostic(
            SLOT_CONFLICT, DiagnosticSeverity.WARN, DiagnosticObject.BLOCK, memberId,
            "$count 段安排与跨队/既有安排重叠，已标红待本人裁决",
            "本人裁决：挪时段或与对方队协调",
        )
    }

    fun lowConfidence(names: List<String>): TeamDiagnostic? {
        if (names.isEmpty()) return null
        return TeamDiagnostic(
            LOW_CONFIDENCE, DiagnosticSeverity.INFO, DiagnosticObject.MEMBER, null,
            "${names.joinToString("、")} 还没导入课表，按 08:00–22:00 保守排",
            "补完课表后自动重排该成员任务",
        )
    }

    fun templateSkipped(reasons: List<String>): TeamDiagnostic? {
        if (reasons.isEmpty()) return null
        return TeamDiagnostic(
            TEMPLATE_SKIPPED, DiagnosticSeverity.WARN, DiagnosticObject.TEMPLATE, null,
            "模板里有 ${reasons.size} 项没有套上：${reasons.take(3).joinToString("；")}",
            "改掉重复编号/日期后再补一次，已套上的不受影响",
        )
    }

    /** 助手/摘要用：按严重度排序，压缩成一行；条数超限只报前几条。 */
    fun digest(all: List<TeamDiagnostic>, maxItems: Int = 4): String {
        if (all.isEmpty()) return ""
        val ordered = all.sortedWith(
            compareByDescending<TeamDiagnostic> { it.severity.ordinal }
                .thenBy { it.code },
        )
        val head = ordered.take(maxItems).joinToString("；") { item ->
            val tag = when (item.severity) {
                DiagnosticSeverity.ERROR -> "错误"
                DiagnosticSeverity.WARN -> "提醒"
                DiagnosticSeverity.INFO -> "信息"
            }
            "[$tag] ${item.message}"
        }
        val rest = ordered.size - maxItems
        return if (rest > 0) "$head；另有 $rest 条" else head
    }
}
