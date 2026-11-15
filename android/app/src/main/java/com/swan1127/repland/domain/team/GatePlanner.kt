package com.swan1127.repland.domain.team

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 关卡就绪度（§4/§17）：报名截止、最终提交、答辩这些「过了才能进下一段」的环节，
 * 不能只看当天有没有空——要看「到关卡为止还有多少活、窗口里还剩多少容量」。
 * 判定口径与环节体检一致：只提示风险，不替队长砍范围。
 */

enum class GateState(val label: String) {
    READY("已收尾"),
    ON_TRACK("来得及"),
    AT_RISK("有风险"),
    BLOCKED("未就绪"),
}

data class GateReadiness(
    val phaseId: String,
    val name: String,
    val due: LocalDate,
    val daysLeft: Int,
    val pendingTasks: List<String>,
    val pendingHours: Double,
    val capacityHours: Double,
    val state: GateState,
    val advice: String,
)

object GatePlanner {

    private const val SAFE_RATIO = 1.3

    /** 按时间顺序体检每个关卡；关卡前的所有环节都算进「必须交付」的范围。 */
    fun evaluate(
        tasks: List<TeamTask>,
        phases: List<Phase>,
        members: List<MemberCard>,
        from: LocalDate,
        bufferDays: Int = 1,
    ): List<GateReadiness> {
        val gates = phases.filter { it.isGate }.sortedBy { it.start }
        if (gates.isEmpty()) return emptyList()
        val phaseById = phases.associateBy { it.phaseId }

        return gates.map { gate ->
            val scopeTasks = tasks.filter { task ->
                val phase = task.phaseId?.let(phaseById::get) ?: return@filter false
                !phase.start.isAfter(gate.end)
            }
            val pending = scopeTasks.filter { it.active && it.rank != TaskRank.WONT }
            val pendingHours = pending.sumOf { it.estimatedHours }
            val daysLeft = ChronoUnit.DAYS.between(from, gate.end).toInt()
            val capacity = if (gate.end.isBefore(from)) 0.0 else StagePlanner.capacityIn(members, from, gate.end)

            val state = when {
                pending.isEmpty() -> GateState.READY
                gate.end.isBefore(from) -> GateState.BLOCKED
                capacity + 1e-9 < pendingHours -> GateState.BLOCKED
                capacity < pendingHours * SAFE_RATIO || daysLeft <= bufferDays -> GateState.AT_RISK
                else -> GateState.ON_TRACK
            }
            GateReadiness(
                phaseId = gate.phaseId,
                name = gate.name,
                due = gate.end,
                daysLeft = daysLeft,
                pendingTasks = pending.map { it.title },
                pendingHours = pendingHours,
                capacityHours = capacity,
                state = state,
                advice = when (state) {
                    GateState.READY -> "关卡已收尾，可以推进到下一段"
                    GateState.ON_TRACK -> "按当前容量来得及"
                    GateState.AT_RISK ->
                        "还差 ${pending.size} 项、${StagePlanner.hours(pendingHours)} 小时，" +
                            "只剩 ${daysLeft.coerceAtLeast(0)} 天，缓冲偏薄"
                    GateState.BLOCKED ->
                        if (gate.end.isBefore(from)) {
                            "已过 $gate.end，仍有 ${pending.size} 项没收尾"
                        } else {
                            "剩 ${StagePlanner.hours(pendingHours)} 小时，窗口里最多 ${StagePlanner.hours(capacity)} 小时"
                        }
                },
            )
        }
    }

    /** 只报需要人管的关卡；「来得及」「已收尾」不占版面。 */
    fun notes(readiness: List<GateReadiness>): List<String> = readiness.mapNotNull { gate ->
        when (gate.state) {
            GateState.READY, GateState.ON_TRACK -> null
            GateState.AT_RISK -> "关卡「${gate.name}」${gate.advice}"
            GateState.BLOCKED -> "关卡「${gate.name}」${gate.advice}——先处理 ${gate.pendingTasks.take(2).joinToString("、")}"
        }
    }
}
