package com.swan1127.repland.domain.team

import java.time.LocalDate

/**
 * 期限引擎（§4）：期限 = f(类型, 阶段截止, 依赖位置)。
 * 下游依赖 → 上游自动提早：上游截止 = 下游最晚开工 − 缓冲，系统自动算并联动。
 * 纯函数、确定性：同样的任务图永远算出同样的期限。
 */
object DeadlineEngine {

    data class DeadlineIssue(
        val taskId: String,
        val kind: IssueKind,
        val message: String,
    )

    enum class IssueKind { OVER_COMPETITION_DEADLINE, CYCLE, MISSING_ANCHOR, OVER_PHASE_END }

    data class DeadlineResult(
        val tasks: List<TeamTask>,
        val issues: List<DeadlineIssue>,
    ) {
        val hasBlockingIssue: Boolean get() = issues.any { it.kind == IssueKind.CYCLE }
    }

    private const val HOURS_PER_DAY = 3.0

    /** 预计工时 → 天数（卡片宽度 = 预计工时，这里换算成倒算步长）。 */
    fun daysNeeded(estimatedHours: Double, dailyCapacityHours: Double = HOURS_PER_DAY): Int =
        if (estimatedHours <= 0) 1 else kotlin.math.ceil(estimatedHours / dailyCapacityHours).toInt().coerceAtLeast(1)

    /**
     * 递归倒算全部 computedDeadline：
     * - 长期任务锚定比赛截止 / 硬截止；
     * - 中期任务锚定所属阶段截止；
     * - 上游截止 = min(下游最晚开工) − 缓冲天。
     */
    fun recompute(
        tasks: List<TeamTask>,
        phases: List<Phase>,
        competitionDeadline: LocalDate?,
        bufferDays: Int = 1,
        dailyCapacityHours: Double = HOURS_PER_DAY,
    ): DeadlineResult {
        val issues = mutableListOf<DeadlineIssue>()

        // 成环不拦排期：忽略环内依赖，先按无环部分算，把问题交给诊断层提示。
        var working = tasks
        val cycles = mutableListOf<List<String>>()
        while (true) {
            val cycle = detectCycle(working)
            if (cycle.isEmpty()) break
            cycles += cycle
            val inCycle = cycle.toSet()
            working = working.map { task ->
                if (task.taskId in inCycle) {
                    task.copy(dependsOn = task.dependsOn.filterNot { it in inCycle })
                } else {
                    task
                }
            }
        }
        cycles.forEach { cycle ->
            issues += DeadlineIssue(
                cycle.first(), IssueKind.CYCLE,
                "依赖成环：${cycle.joinToString("→")}（已忽略环内依赖，先排其余部分）",
            )
        }

        val byId = working.associateBy { it.taskId }
        val computed = HashMap<String, LocalDate>()

        // 拓扑序（上游在前），倒序遍历让下游先算。
        val order = topologicalOrder(working)
        for (taskId in order.reversed()) {
            val task = byId[taskId] ?: continue
            if (!task.active || task.rank == TaskRank.WONT) continue

            val ownAnchor = anchor(task, phases, competitionDeadline)
            if (ownAnchor == null) {
                issues += DeadlineIssue(taskId, IssueKind.MISSING_ANCHOR, "「${task.title}」没有阶段截止或硬截止，无法倒算")
                continue
            }
            if (competitionDeadline != null && ownAnchor > competitionDeadline) {
                issues += DeadlineIssue(
                    taskId, IssueKind.OVER_COMPETITION_DEADLINE,
                    "「${task.title}」截止 $ownAnchor 晚于比赛截止 $competitionDeadline",
                )
            }

            val downstream = working.filter { it.active && taskId in it.dependsOn }
            val upstreamDeadline: LocalDate = downstream.mapNotNull { downstreamTask ->
                // 上游截止 = 下游最晚开工 − 缓冲；下游最晚开工 = 下游截止 − 下游工期
                val downstreamDeadline = computed[downstreamTask.taskId]
                    ?: anchor(downstreamTask, phases, competitionDeadline) ?: return@mapNotNull null
                downstreamDeadline
                    .minusDays(daysNeeded(downstreamTask.estimatedHours, dailyCapacityHours).toLong())
                    .minusDays(bufferDays.toLong())
            }.minOrNull() ?: ownAnchor

            val deadline = minOf(ownAnchor, upstreamDeadline)
            computed[taskId] = deadline

            val phaseEnd = task.phaseId?.let { id -> phases.firstOrNull { it.phaseId == id }?.end }
            if (phaseEnd != null && deadline > phaseEnd) {
                issues += DeadlineIssue(
                    taskId, IssueKind.OVER_PHASE_END,
                    "「${task.title}」倒算截止 $deadline 晚于阶段截止 $phaseEnd",
                )
            }
        }

        val updated = working.map { task -> computed[task.taskId]?.let { task.copy(computedDeadline = it) } ?: task }
        return DeadlineResult(updated, issues)
    }

    /** 设定截止（§7 时间轮盘）：超过比赛截止标红拦截。 */
    fun validateDeadlineSetting(
        taskId: String,
        title: String,
        date: LocalDate,
        competitionDeadline: LocalDate?,
    ): DeadlineIssue? =
        if (competitionDeadline != null && date > competitionDeadline) {
            DeadlineIssue(taskId, IssueKind.OVER_COMPETITION_DEADLINE, "「$title」设定截止 $date 超过比赛截止 $competitionDeadline")
        } else {
            null
        }

    private fun anchor(task: TeamTask, phases: List<Phase>, competitionDeadline: LocalDate?): LocalDate? = when (task.tier) {
        TaskTier.LONG_TERM -> task.hardDeadline ?: competitionDeadline
        TaskTier.MID_TERM -> task.hardDeadline ?: task.phaseId?.let { id -> phases.firstOrNull { it.phaseId == id }?.end }
        TaskTier.SHORT_TERM -> task.hardDeadline
    }

    private fun topologicalOrder(tasks: List<TeamTask>): List<String> {
        val byId = tasks.associateBy { it.taskId }
        val visited = LinkedHashSet<String>()
        fun visit(id: String) {
            if (id in visited) return
            val task = byId[id] ?: return
            task.dependsOn.forEach { visit(it) }
            visited += id
        }
        tasks.forEach { visit(it.taskId) }
        return visited.toList()
    }

    private fun detectCycle(tasks: List<TeamTask>): List<String> {
        val byId = tasks.associateBy { it.taskId }
        val state = HashMap<String, Int>() // 0=未访问 1=在栈 2=完成

        fun dfs(id: String, path: MutableList<String>): List<String>? {
            when (state[id]) {
                1 -> return path.dropWhile { it != id } + id
                2 -> return null
            }
            val task = byId[id] ?: return null
            state[id] = 1
            path += id
            for (dep in task.dependsOn) {
                dfs(dep, path)?.let { return it }
            }
            path.removeAt(path.size - 1)
            state[id] = 2
            return null
        }

        for (task in tasks) {
            dfs(task.taskId, mutableListOf())?.let { return it }
        }
        return emptyList()
    }
}
