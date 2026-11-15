package com.swan1127.repland.domain.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class MemorySignal(
    val taskId: String,
    val content: String,
    val stabilityDays: Double,
    val retrievability: Double,
    val dueOn: LocalDate,
    val pressure: Double,
    /** 真正被回忆过的次数。0–2 次的稳定度只是初始先验，不是测量结果。 */
    val observations: Int = 0,
) {
    val isAtRisk: Boolean get() = retrievability <= MemoryReference.AtRiskRetention

    /** 能不能对它下「稳不稳」的判断。 */
    val isMeasured: Boolean get() = observations >= MemoryReference.MinMeasuredObservations
}

data class MemoryEntry(val taskId: String, val content: String, val state: MemoryState)

data class MemoryReference(
    val asOf: LocalDate,
    val signals: List<MemorySignal>,
) {
    val isEmpty: Boolean get() = signals.isEmpty()

    val dueCount: Int get() = signals.count { !it.dueOn.isAfter(asOf) }

    val atRisk: List<MemorySignal> get() = signals.filter { it.isAtRisk }


    fun forTask(taskId: String): MemorySignal? =
        signals.filter { it.taskId == taskId }.minByOrNull { it.retrievability }

    /** 提示词的统一取用口径：快忘掉的排前面，同等情况下重要的在前。 */
    fun ranked(limit: Int): List<MemorySignal> = signals
        .sortedWith(compareBy<MemorySignal> { it.retrievability }.thenByDescending { it.pressure })
        .take(limit)

    /** 给助手读的一行一条的现状；只描述记忆，不要求用户去复习什么。没有百分比——只被回忆过一次的稳定度是先验，不是测量。 */
    fun riskLines(limit: Int): List<String> = ranked(limit).map { signal ->
        val days = ChronoUnit.DAYS.between(asOf, signal.dueOn)
        val tail = when {
            !signal.isMeasured -> "刚记下，还稳不稳看不准"
            days <= 0 -> "最近容易想不起"
            else -> "大约还能放 $days 天"
        }
        "- ${signal.content.trim()}（$tail）"
    }

    fun logNote(): String {
        if (isEmpty) return "记忆画像：还没有可依赖的记录"
        if (atRisk.isEmpty()) return "记忆画像：${signals.size} 条记录，目前都还记得"
        val unmeasured = signals.size - signals.count(MemorySignal::isMeasured)
        val tail = if (unmeasured > 0) "，另有 $unmeasured 条刚记下还看不准" else ""
        return "记忆画像：${signals.size} 条记录，${atRisk.size} 条最近容易想不起$tail"
    }

    companion object {

        const val AtRiskRetention: Double = 0.6

        /** 少于这个次数就只是初始先验，不足以断言「稳」或「不稳」。 */
        const val MinMeasuredObservations: Int = 3

        fun of(entries: List<MemoryEntry>, today: LocalDate): MemoryReference = MemoryReference(
            asOf = today,
            signals = entries.map { entry ->
                MemorySignal(
                    taskId = entry.taskId,
                    content = entry.content,
                    stabilityDays = entry.state.stabilityDays,
                    retrievability = MemoryModel.retrievabilityAt(entry.state, today),
                    dueOn = MemoryModel.dueOn(entry.state, today),
                    pressure = MemoryModel.reviewPressure(entry.state, today),
                    observations = entry.state.reviews,
                )
            }.sortedBy { it.retrievability },
        )


        fun empty(today: LocalDate): MemoryReference = MemoryReference(today, emptyList())
    }
}
