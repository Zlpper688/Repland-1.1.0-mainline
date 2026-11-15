package com.swan1127.repland.domain.model

import java.time.LocalDateTime

/** Parse user text without changing it into a different fact. */
object UserNumericInput {
    fun integerIn(text: String, range: IntRange): Int? =
        text.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it in range }

    fun clockMinute(hour: String, minute: String): Int? {
        val h = integerIn(hour, 0..23) ?: return null
        val m = integerIn(minute, 0..59) ?: return null
        return h * 60 + m
    }
}

/** Existing manual task placement chunk policy; never rewrites task total duration. */
object TaskPlacementPolicy {
    fun durationForTask(totalMinutes: Int?): Int? = when {
        totalMinutes == null -> 30
        totalMinutes !in 1..1_440 -> null
        else -> totalMinutes.coerceIn(15, 240)
    }

    /** 15 分钟对齐的最早起点。当天用现在时刻，别的日子从 08:00 起算。 */
    fun floorMinuteFor(now: LocalDateTime): Int =
        maxOf(8 * 60, ((now.hour * 60 + now.minute + 14) / 15) * 15)

    /**
     * 第一个与固定安排（课程/休息/固定事项）不冲突、不早于 [floorMinute] 的起点。
     *
     * [reserved] 是本批「安排已有事项」里已经分给上一行的档位。缺了这一层，列表每行都报
     * 同一个建议时间，连着点两行就撞进同一个时段。
     *
     * 搜索到 23:00 就收手，和 [ChatDayPlanner] 给助手挑落点的判据同一条。这儿的候选时间也是
     * 工具替用户挑的，不是用户点名的；夜里那格留给睡眠，否则手动一键排入会比对话助手更不懂作息。
     */
    fun nextFreeMinute(
        occupied: List<TimelineEntry>,
        floorMinute: Int,
        duration: Int,
        reserved: List<Pair<Int, Int>> = emptyList(),
    ): Int? {
        var candidate = floorMinute.coerceIn(0, 1_440)
        val fixed = occupied.filter { it.kind != TimelineKind.TASK }
        while (candidate + duration <= ChatDayPlanner.SLEEP_START_MINUTE) {
            val end = candidate + duration
            val blockedUntil = fixed.filter { candidate < it.endMinute && end > it.startMinute }
                .map { it.endMinute }
                .maxOrNull()
                ?: reserved.filter { candidate < it.second && end > it.first }
                    .maxOfOrNull { it.second }
            if (blockedUntil == null) return candidate
            candidate = blockedUntil
        }
        return null
    }
}
