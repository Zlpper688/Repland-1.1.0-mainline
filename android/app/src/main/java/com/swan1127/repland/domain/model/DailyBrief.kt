package com.swan1127.repland.domain.model

import java.time.LocalDate

/**
 * 每日简报：按本地日程确定性整理出"今天哪些时段被占住、还有哪些整块空档"。
 * 不调云端、不猜进度——同一份日程永远生成同一句话（借鉴 Today AI #8：简报只展示最近一份）。
 *
 * 空档口径与规划师实况（AgentToolRuntime.DayFacts）一字不差：课程/固定/休息是硬占用，
 * 空档是其余补集，夹在默认作息窗口（8:00–22:00）之间、至少 30 分钟才算一段。
 * 已排任务不算硬占用——它们是"可以挪"的事，空档说的是"没有被课程/固定/休息锁死"的时段。
 */
data class DailyBrief(
    val date: LocalDate,
    val headline: String,
    val evidence: List<String>,
    val freeWindows: List<String>,
    val footer: String = "按本地日程整理 · 未调用云端",
) {
    companion object {
        private const val MIN_WINDOW_MINUTES = 30
        private const val MINUTES_PER_DAY = 24 * 60
        private val HARD_BUSY = setOf(TimelineKind.COURSE, TimelineKind.REST, TimelineKind.COMMITMENT)

        /** 永不返回 null：空日子也给一句实话，让"今天还没安排"本身成为可行动的提示。 */
        fun of(date: LocalDate, occupied: List<TimelineEntry>): DailyBrief {
            val today = occupied.filter { it.date == date }
            val hardBusy = today.filter { it.kind in HARD_BUSY }.sortedBy(TimelineEntry::startMinute)
            val tasks = today.filter { it.kind == TimelineKind.TASK }.sortedBy(TimelineEntry::startMinute)
            val free = freeWindows(hardBusy)
            val headline = when {
                today.isEmpty() -> "今天还没有安排，说一句话我帮你排。"
                free.isEmpty() -> "今天排得比较满，没有 30 分钟以上的整块空档。"
                else -> "今天有 ${free.size} 段可用空档，${hardBusy.size} 项固定占用、${tasks.size} 件已排任务。"
            }
            val evidence = (hardBusy + tasks)
                .sortedBy(TimelineEntry::startMinute)
                .take(8)
                .map { "${fmt(it.startMinute)}-${fmt(it.endMinute)} ${it.title}（${kindLabel(it.kind)}）" }
            return DailyBrief(
                date = date,
                headline = headline,
                evidence = evidence,
                freeWindows = free.map { "${fmt(it.first)}-${fmt(it.last + 1)}" },
            )
        }

        private fun freeWindows(hardBusy: List<TimelineEntry>): List<IntRange> {
            val windows = mutableListOf<IntRange>()
            var cursor = 0
            hardBusy.forEach { entry ->
                if (entry.startMinute > cursor) windows += cursor until entry.startMinute
                if (entry.endMinute > cursor) cursor = entry.endMinute
            }
            if (cursor < MINUTES_PER_DAY) windows += cursor until MINUTES_PER_DAY
            val dayStart = ChatDayPlanner.DEFAULT_DAY_START_MINUTE
            val dayEnd = ChatDayPlanner.DEFAULT_DAY_END_MINUTE
            return windows.mapNotNull { window ->
                val from = maxOf(window.first, dayStart)
                val to = minOf(window.last, dayEnd - 1)
                if (to - from + 1 >= MIN_WINDOW_MINUTES) from..to else null
            }
        }

        private fun kindLabel(kind: TimelineKind): String = when (kind) {
            TimelineKind.COURSE -> "课程"
            TimelineKind.REST -> "休息"
            TimelineKind.COMMITMENT -> "固定"
            TimelineKind.TASK -> "任务"
        }

        private fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}