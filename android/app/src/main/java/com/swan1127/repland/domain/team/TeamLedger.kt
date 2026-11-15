package com.swan1127.repland.domain.team

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 贡献台账（§9）：基线任务 + 增量任务双层；只客观留证（时间/产出/外链），不打分不排名。
 * 轻记录不是功能，是项目自觉：条目由各模块自然沉淀（执行块、状态流转、外链、财务流水）。
 */
enum class LedgerKind(val label: String) {
    EXECUTION_BLOCK("执行块"),
    STATUS_CHANGE("状态流转"),
    EXTERNAL_LINK("作品外链"),
    FINANCE("财务流水"),
    TRANSFER("转派留痕"),
    OBJECTION("异议留痕"),
    AUTO_SAYING("口语登记"),
}

data class LedgerEntry(
    val entryId: String,
    val kind: LedgerKind,
    val taskId: String?,
    val memberId: String,
    val at: LocalDateTime,
    val evidence: String,
    val externalUrl: String? = null,
    val amountCents: Long? = null,
    val isBaseline: Boolean = true,
)

/**
 * 财务记账（§9）：流水全队可查；录入限「财务记账」权限授权成员。
 */
data class FinanceRecord(
    val recordId: String,
    val memberId: String,
    val at: LocalDateTime,
    val amountCents: Long,
    val reason: String,
)

/**
 * 周报（§11）：开关制；不对个人作评价，只写项目进展。
 * 表现数据 = 执行块完成情况 + 任务状态流转 + 台账条目 + 会议出席（只用团队维度数据 + 本人授权的个人日程块）。
 */
data class WeeklyReportDraft(
    val weekStart: LocalDate,
    val progressSummary: List<String>,
    val riskRadar: List<String>,
    val nextWeekFocus: List<String>,
    val evidence: List<LedgerEntry>,
    val stats: Map<String, Int>,
)

object TeamLedger {

    fun recordStatusChange(entryIdFactory: () -> String, task: TeamTask, memberId: String, from: TeamTaskStatus, to: TeamTaskStatus, at: LocalDateTime): LedgerEntry =
        LedgerEntry(
            entryId = entryIdFactory(),
            kind = LedgerKind.STATUS_CHANGE,
            taskId = task.taskId,
            memberId = memberId,
            at = at,
            evidence = "「${task.title}」${from.label} → ${to.label}",
            isBaseline = task.isBaseline,
        )

    fun recordExecution(entryIdFactory: () -> String, task: TeamTask, memberId: String, minutes: Int, at: LocalDateTime): LedgerEntry =
        LedgerEntry(
            entryId = entryIdFactory(),
            kind = LedgerKind.EXECUTION_BLOCK,
            taskId = task.taskId,
            memberId = memberId,
            at = at,
            evidence = "「${task.title}」投入 ${minutes} 分钟",
            isBaseline = task.isBaseline,
        )

    /** 口语登记（§9）：对 AI 说「这周调研做完了」→ 解析成台账条目，自动带时间戳。 */
    fun recordSaying(entryIdFactory: () -> String, task: TeamTask, memberId: String, rawText: String, at: LocalDateTime): LedgerEntry =
        LedgerEntry(
            entryId = entryIdFactory(),
            kind = LedgerKind.AUTO_SAYING,
            taskId = task.taskId,
            memberId = memberId,
            at = at,
            evidence = "队员原话：${rawText.trim()}",
            isBaseline = task.isBaseline,
        )

    // ---------- 周报数据聚合（确定性，无 LLM 也可用） ----------

    fun weeklyReport(
        tasks: List<TeamTask>,
        entries: List<LedgerEntry>,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        openObjections: List<Objection> = emptyList(),
        overdueTaskIds: List<String> = emptyList(),
    ): WeeklyReportDraft {
        val weekEntries = entries.filter { !it.at.toLocalDate().isBefore(weekStart) && !it.at.toLocalDate().isAfter(weekEnd) }
        val doneThisWeek = tasks.filter { it.status == TeamTaskStatus.DONE }
        val inProgress = tasks.filter { it.status == TeamTaskStatus.IN_PROGRESS }
        val wont = tasks.filter { it.status == TeamTaskStatus.WONT }

        val progress = buildList {
            add("本周完成 ${doneThisWeek.size} 项、进行中 ${inProgress.size} 项" + if (wont.isNotEmpty()) "、取消 ${wont.size} 项" else "")
            doneThisWeek.take(3).forEach { add("已完成：${it.title}") }
            inProgress.take(3).forEach { add("进行中：${it.title}（负责 ${it.assignedTo ?: "未分配"}）") }
        }
        val risks = buildList {
            if (overdueTaskIds.isNotEmpty()) add("有 ${overdueTaskIds.size} 项任务已过倒算期限：${overdueTaskIds.size} 项待重排")
            if (openObjections.isNotEmpty()) add("存在 ${openObjections.size} 条未裁决异议")
            if (inProgress.any { it.assignedTo == null }) add("存在未分配的进行中任务")
        }
        val focus = inProgress.take(5).map { "推进「${it.title}」" } + if (wont.isNotEmpty()) listOf("复核 ${wont.size} 项已取消任务是否需要回补") else emptyList()
        val stats = mapOf(
            "ledgerEntries" to weekEntries.size,
            "executionBlocks" to weekEntries.count { it.kind == LedgerKind.EXECUTION_BLOCK },
            "statusChanges" to weekEntries.count { it.kind == LedgerKind.STATUS_CHANGE },
            "externalLinks" to weekEntries.count { it.kind == LedgerKind.EXTERNAL_LINK },
            "financeRecords" to weekEntries.count { it.kind == LedgerKind.FINANCE },
            "tasksDone" to doneThisWeek.size,
            "tasksActive" to inProgress.size,
        )
        return WeeklyReportDraft(
            weekStart = weekStart,
            progressSummary = progress,
            riskRadar = risks.ifEmpty { listOf("本周暂无风险信号") },
            nextWeekFocus = focus.ifEmpty { listOf("本周暂无进行中任务，建议规划下周重点") },
            evidence = weekEntries.sortedBy { it.at },
            stats = stats,
        )
    }
}
