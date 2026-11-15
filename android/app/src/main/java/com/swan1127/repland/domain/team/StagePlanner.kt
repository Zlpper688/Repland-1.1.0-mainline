package com.swan1127.repland.domain.team

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 环节负载体检：把「这个阶段要干多少」和「窗口里最多能干多少」摆在一起。
 * 需求 = 该阶段未完成任务的预计工时；供给 = 窗口内每人每日容量的合计。
 * 只在草案旁提示风险，不替队长做决定。
 */

enum class StageRisk { OK, TIGHT, OVER }

data class StageLoad(
    val phaseId: String,
    val name: String,
    val kind: StageKind,
    val start: LocalDate,
    val end: LocalDate,
    val windowDays: Int,
    val demandHours: Double,
    val capacityHours: Double,
    val risk: StageRisk,
    val isGate: Boolean,
) {
    val marginHours: Double get() = capacityHours - demandHours
}

object StagePlanner {

    private const val TIGHT_RATIO = 1.2
    private const val GATE_TIGHT_RATIO = 1.3

    fun analyze(
        tasks: List<TeamTask>,
        phases: List<Phase>,
        members: List<MemberCard>,
        from: LocalDate,
    ): List<StageLoad> {
        val demandByPhase = tasks
            .filter { it.active && it.rank != TaskRank.WONT }
            .filter { it.phaseId != null }
            .groupBy { it.phaseId!! }
            .mapValues { (_, list) -> list.sumOf { it.estimatedHours } }

        return phases
            .sortedBy { it.start }
            .map { phase ->
                val windowStart = maxOf(from, phase.start)
                val windowDays = if (phase.end.isBefore(windowStart)) 0
                else (ChronoUnit.DAYS.between(windowStart, phase.end) + 1).toInt()
                val capacity = if (windowDays == 0) 0.0 else capacityIn(members, windowStart, phase.end)
                val demand = demandByPhase[phase.phaseId] ?: 0.0
                StageLoad(
                    phaseId = phase.phaseId,
                    name = phase.name,
                    kind = phase.kind,
                    start = phase.start,
                    end = phase.end,
                    windowDays = windowDays,
                    demandHours = demand,
                    capacityHours = capacity,
                    risk = riskOf(demand, capacity, phase.isGate),
                    isGate = phase.isGate,
                )
            }
    }

    /** 只报需要人管的：排不完的环节、余量太薄的关键关卡；正常的环节不占版面。 */
    fun notes(loads: List<StageLoad>): List<String> = loads.mapNotNull { load ->
        when {
            load.demandHours <= 0.0 -> null
            load.windowDays == 0 ->
                "环节「${load.name}」已经过了，还有 ${hours(load.demandHours)} 小时没收尾"
            load.risk == StageRisk.OVER ->
                "环节「${load.name}」差 ${hours(-load.marginHours)} 小时：" +
                    "需 ${hours(load.demandHours)} 小时，${load.windowDays} 天窗口最多 ${hours(load.capacityHours)} 小时——加人或砍范围"
            load.risk == StageRisk.TIGHT && load.isGate ->
                "关卡「${load.name}」只剩 ${hours(load.marginHours)} 小时余量，提交前容易挤，建议留缓冲"
            else -> null
        }
    }

    private fun riskOf(demand: Double, capacity: Double, isGate: Boolean): StageRisk {
        if (demand <= 0.0) return StageRisk.OK
        if (capacity + 1e-9 < demand) return StageRisk.OVER
        val ratio = if (isGate) GATE_TIGHT_RATIO else TIGHT_RATIO
        return if (capacity < demand * ratio) StageRisk.TIGHT else StageRisk.OK
    }

    internal fun capacityIn(members: List<MemberCard>, start: LocalDate, end: LocalDate): Double {
        var total = 0.0
        var day = start
        while (!day.isAfter(end)) {
            members.forEach { total += capacityHours(it, day) }
            day = day.plusDays(1)
        }
        return total
    }

    /** 与排期同口径：该天没有课表数据 → 按每日容量上限保守估；有数据 → 取空闲与上限的较小值。 */
    private fun capacityHours(member: MemberCard, day: LocalDate): Double {
        val windows = member.weeklyAvailability[day.dayOfWeek].orEmpty()
        if (windows.isEmpty()) return member.dailyCapacityHours
        val freeHours = windows.sumOf { it.minutes } / 60.0
        return minOf(freeHours, member.dailyCapacityHours)
    }

    internal fun hours(value: Double): String {
        val rounded = kotlin.math.round(value * 10) / 10
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
