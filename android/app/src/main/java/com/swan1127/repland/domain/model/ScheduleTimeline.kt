package com.swan1127.repland.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class TimelineKind { COURSE, REST, COMMITMENT, TASK }
enum class TimelinePhase { UPCOMING, ACTIVE, OVERRUN, ENDED }

data class TimelineEntry(
    val id: String,
    val title: String,
    val kind: TimelineKind,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val taskId: String? = null,
    val taskClosed: Boolean = false,
    val trackId: String = "auto",
    val lane: Int = 0,
    /** Context carried into the detail sheet; it never changes task completion state. */
    val note: String? = null,
    /** Per-event alarm switch shown as a bell in the detail sheet and widget. */
    val reminder: Boolean = false,
    /** 写入硬规矩：锁定的安排所在时段不接受任何新事项，连当父事件也不行。 */
    val locked: Boolean = false,
    /** 不可避免（REQUIRED）安排所在时段同样拒绝新事项重叠。 */
    val unavoidable: Boolean = false,
    /** 来源是比赛队伍任务（第二课堂）：日程里用独立配色，和个人的第一课堂事项区分。 */
    val secondClassroom: Boolean = false,
) {
    init {
        require(startMinute in 0 until 1440 && endMinute in 1..1440 && startMinute < endMinute)
    }
    val start: LocalDateTime get() = date.atStartOfDay().plusMinutes(startMinute.toLong())
    val end: LocalDateTime get() = date.atStartOfDay().plusMinutes(endMinute.toLong())
}

/**
 * 下属事件：一个安排完整落在另一个安排的时段之内，表示"做这件事的同时也做那件事"。
 * 嵌套关系从时间范围推导，不落库——拖进父事件框架即成为下属，拖出即回到顺序队列。
 */
fun TimelineEntry.isNestedIn(other: TimelineEntry): Boolean {
    if (id == other.id) return false
    if (other.startMinute > startMinute || endMinute > other.endMinute) return false
    // 完全相同的时段是"同时进行"的并列事件，不算嵌套——否则其中一个会被当成父级，另一个凭空消失。
    return startMinute != other.startMinute || endMinute != other.endMinute
}

/** The smallest entry whose time frame fully contains [entry]; null when [entry] stands on its own. */
fun smallestContainerOf(entry: TimelineEntry, candidates: List<TimelineEntry>): TimelineEntry? =
    candidates.filter { entry.isNestedIn(it) }.minByOrNull { it.endMinute - it.startMinute }

/** 下属只挂一层：父事件永远取根级块，孙级折叠为根的下属，保证每个事件都有地方显示。 */
fun childrenByContainer(entries: List<TimelineEntry>): Map<String, List<TimelineEntry>> {
    val roots = rootEntries(entries).toSet()
    return entries.mapNotNull { entry ->
        if (entry in roots) null else smallestContainerOf(entry, roots.toList())?.let { entry to it.id }
    }.groupBy({ it.second }, { it.first })
        .mapValues { (_, children) -> children.sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::id)) }
}

fun rootEntries(entries: List<TimelineEntry>): List<TimelineEntry> =
    entries.filter { entry -> entries.none { entry.isNestedIn(it) } }

/**
 * 合轨大块：互相重叠的根级事件连成一个块——大块按"合计的最长时间"整体展示与移动，
 * 块内成员各显示各的时间；顺序排列的事件各自成块。
 */
fun parallelUnits(entries: List<TimelineEntry>): List<List<TimelineEntry>> {
    val roots = rootEntries(entries).sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::endMinute, TimelineEntry::id))
    val units = mutableListOf<MutableList<TimelineEntry>>()
    for (entry in roots) {
        val last = units.lastOrNull()
        if (last != null && entry.startMinute < last.maxOf { it.endMinute }) last += entry
        else units += mutableListOf(entry)
    }
    return units
}

data class TimelineClock(
    val phase: TimelinePhase,
    val untilStartMinutes: Long = 0,
    val elapsedMinutes: Long = 0,
    val remainingMinutes: Long = 0,
    val overtimeMinutes: Long = 0,
)

object ScheduleTimeline {
    /** Schedule state is based on wall time; it never implies that work was actually performed. */
    fun clock(entry: TimelineEntry, now: LocalDateTime, userFinished: Boolean = false): TimelineClock {
        if (userFinished) return TimelineClock(TimelinePhase.ENDED)
        if (now.isBefore(entry.start)) return TimelineClock(
            TimelinePhase.UPCOMING,
            untilStartMinutes = ChronoUnit.MINUTES.between(now, entry.start).coerceAtLeast(0),
        )
        if (now.isBefore(entry.end)) return TimelineClock(
            TimelinePhase.ACTIVE,
            elapsedMinutes = ChronoUnit.MINUTES.between(entry.start, now).coerceAtLeast(0),
            remainingMinutes = ChronoUnit.MINUTES.between(now, entry.end).coerceAtLeast(0),
        )
        return TimelineClock(
            TimelinePhase.OVERRUN,
            elapsedMinutes = ChronoUnit.MINUTES.between(entry.start, now).coerceAtLeast(0),
            overtimeMinutes = ChronoUnit.MINUTES.between(entry.end, now).coerceAtLeast(0),
        )
    }

    fun entries(
        date: LocalDate,
        weeklyBlocks: List<WeeklyTimeBlock>,
        dateOverrides: List<DateOverride>,
        segments: List<PlannedSegment>,
        tasks: List<Task>,
        semesterFirstWeekMonday: LocalDate?,
    ): List<TimelineEntry> {
        val taskById = tasks.associateBy(Task::id)
        val recurring = weeklyBlocks.asSequence()
            .filter { it.kind != TimeBlockKind.AVAILABLE && it.dayOfWeek == date.dayOfWeek }
            .filter { CourseWeekPattern.appliesOn(it.weekPattern, date, semesterFirstWeekMonday) }
            .map { block ->
                TimelineEntry(
                    id = "weekly:${block.id}", title = block.title,
                    kind = when (block.kind) {
                        TimeBlockKind.COURSE -> TimelineKind.COURSE
                        TimeBlockKind.REST -> TimelineKind.REST
                        else -> TimelineKind.COMMITMENT
                    },
                    date = date, startMinute = block.startMinute, endMinute = block.endMinute,
                    trackId = when (block.kind) {
                        TimeBlockKind.COURSE -> block.trackId
                        TimeBlockKind.REST -> "rest"
                        else -> "fixed"
                    },
                    note = block.note,
                    reminder = block.reminderEnabled,
                )
            }
        val overrides = dateOverrides.asSequence()
            .filter { it.date == date && it.type == DateOverrideType.BLOCKED }
            .map { override ->
                TimelineEntry(
                    id = "override:${override.id}", title = override.title,
                    kind = TimelineKind.COMMITMENT, date = date,
                    startMinute = override.startMinute, endMinute = override.endMinute,
                    trackId = "fixed",
                    note = override.note,
                    reminder = override.reminderEnabled,
                )
            }
        val work = segments.asSequence().filter { it.date == date }.mapNotNull { segment ->
            val task = taskById[segment.taskId] ?: return@mapNotNull null
            TimelineEntry(
                id = "segment:${segment.id}", title = task.displayName,
                kind = TimelineKind.TASK, date = date,
                startMinute = segment.startMinute, endMinute = segment.endMinute,
                taskId = task.id,
                taskClosed = !task.status.isActive,
                trackId = segment.trackId,
                note = task.description.takeIf { it.isNotBlank() && it != task.displayName },
                reminder = segment.reminderEnabled,
                locked = segment.isLocked,
                unavoidable = task.userPriority == TaskPriority.REQUIRED,
                secondClassroom = task.isTeamMirror,
            )
        }
        val trackOrder = mutableListOf<String>()
        val laneEnds = mutableListOf<Int>()
        return (recurring + overrides + work)
            .sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::endMinute, TimelineEntry::id))
            .map { entry ->
                val preferredLane = trackOrder.indexOf(entry.trackId).takeIf { it >= 0 }
                    ?: trackOrder.size.also { trackOrder += entry.trackId }
                while (laneEnds.size <= preferredLane) laneEnds += -1
                val lane = if (laneEnds[preferredLane] <= entry.startMinute) {
                    preferredLane
                } else {
                    laneEnds.indexOfFirst { end -> end <= entry.startMinute }.takeIf { it >= 0 }
                        ?: laneEnds.size.also { laneEnds += -1 }
                }
                laneEnds[lane] = entry.endMinute
                entry.copy(lane = lane)
            }
            .toList()
    }
}
