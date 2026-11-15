package com.swan1127.repland.domain.model

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class ReminderPreferences(
    val isEnabled: Boolean = false,
)

enum class ReminderKind {
    SEGMENT_START,
    DEADLINE,
    DAILY_REVIEW,
    WEEKLY_BLOCK_START,
    OVERRIDE_START,
}

/** A local-only alarm candidate. It is produced from a confirmed plan, never a draft. */
data class LocalReminder(
    val kind: ReminderKind,
    val key: String,
    val triggerAt: Instant,
    /** 事件真正开始的一刻。提前响的闹钟要靠它把「还有几分钟」写在通知上。 */
    val startAt: Instant? = null,
    val taskId: String? = null,
    val planId: String? = null,
    val segmentId: String? = null,
    val taskName: String? = null,
    val dueDate: LocalDate? = null,
)

object LocalReminderPlanner {
    const val DAILY_REVIEW_HOUR = 20
    const val DAILY_REVIEW_MINUTE = 30
    private const val DEADLINE_WARNING_HOUR = 20

    /** 踩点响的闹钟等于没有提醒：人要在开始前几分钟才来得及切换状态。 */
    const val EventLeadMinutes: Long = 10

    /** 提前量已经过了就落在开始那一刻——错过一次提醒比晚一点响更要紧。 */
    private fun eventTrigger(
        startAt: LocalDateTime,
        zone: ZoneId,
        now: Instant,
        leadMinutes: Long? = null,
    ): Pair<Instant, Instant>? {
        val start = startAt.atZone(zone).toInstant()
        val lead = startAt.minusMinutes(leadMinutes ?: EventLeadMinutes).atZone(zone).toInstant()
        val trigger = if (lead.isAfter(now)) lead else start
        return trigger.takeIf { it.isAfter(now) }?.let { it to start }
    }

    fun plan(
        currentPlan: ConfirmedPlan?,
        tasks: List<Task>,
        weeklyBlocks: List<WeeklyTimeBlock> = emptyList(),
        dateOverrides: List<DateOverride> = emptyList(),
        semesterFirstWeekMonday: LocalDate? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<LocalReminder> {
        val now = Instant.now(clock)
        val zone = clock.zone
        val tasksById = tasks.associateBy(Task::id)
        // Plan-derived reminders keep the original semantics: they exist only for a confirmed
        // plan. Weekly and one-day time-setting events stand on their own.
        val planDerived = currentPlan?.let { confirmedPlan ->
            val starts = confirmedPlan.segments.mapNotNull { segment ->
                val task = tasksById[segment.taskId] ?: return@mapNotNull null
                if (!task.status.isActive || !segment.reminderEnabled) return@mapNotNull null
                val (triggerAt, startAt) = eventTrigger(
                    segment.date.atStartOfDay().plusMinutes(segment.startMinute.toLong()), zone, now,
                    segment.leadMinutes?.toLong(),
                ) ?: return@mapNotNull null
                LocalReminder(
                    kind = ReminderKind.SEGMENT_START,
                    key = "${confirmedPlan.id}:${segment.id}",
                    triggerAt = triggerAt,
                    startAt = startAt,
                    taskId = task.id,
                    planId = confirmedPlan.id,
                    segmentId = segment.id,
                    taskName = task.displayName,
                )
            }
            val deadlines = tasks.asSequence()
                .filter { task -> task.status.isActive }
                .mapNotNull { task ->
                    val dueDate = task.dueDate ?: return@mapNotNull null
                    val triggerAt = dueDate.minusDays(1).atTime(DEADLINE_WARNING_HOUR, 0)
                        .atZone(zone).toInstant()
                    if (!triggerAt.isAfter(now)) return@mapNotNull null
                    LocalReminder(
                        kind = ReminderKind.DEADLINE,
                        key = task.id,
                        triggerAt = triggerAt,
                        taskId = task.id,
                        taskName = task.displayName,
                        dueDate = dueDate,
                    )
                }
                .toList()
            val dailyReview = LocalReminder(
                kind = ReminderKind.DAILY_REVIEW,
                key = "daily-review",
                triggerAt = nextDailyReview(now, zone),
            )
            starts + deadlines + dailyReview
        }.orEmpty()
        val weeklyStarts = weeklyBlocks.asSequence()
            .filter { it.reminderEnabled }
            .mapNotNull { block ->
                val (triggerAt, startAt) = nextWeeklyOccurrence(
                    block, now, zone, semesterFirstWeekMonday, block.leadMinutes?.toLong(),
                ) ?: return@mapNotNull null
                LocalReminder(
                    kind = ReminderKind.WEEKLY_BLOCK_START,
                    key = "weekly:${block.id}",
                    triggerAt = triggerAt,
                    startAt = startAt,
                    taskName = block.title,
                )
            }
            .toList()
        val overrideStarts = dateOverrides.asSequence()
            .filter { it.reminderEnabled && it.type == DateOverrideType.BLOCKED }
            .mapNotNull { override ->
                val (triggerAt, startAt) = eventTrigger(
                    override.date.atStartOfDay().plusMinutes(override.startMinute.toLong()), zone, now,
                    override.leadMinutes?.toLong(),
                ) ?: return@mapNotNull null
                LocalReminder(
                    kind = ReminderKind.OVERRIDE_START,
                    key = "override:${override.id}",
                    triggerAt = triggerAt,
                    startAt = startAt,
                    taskName = override.title,
                )
            }
            .toList()
        return (planDerived + weeklyStarts + overrideStarts)
            .sortedWith(compareBy(LocalReminder::triggerAt, LocalReminder::kind, LocalReminder::key))
    }

    /** Scans far enough to cover parity patterns (单双周) and skips past starts today. */
    private fun nextWeeklyOccurrence(
        block: WeeklyTimeBlock,
        now: Instant,
        zone: ZoneId,
        semesterFirstWeekMonday: LocalDate?,
        leadMinutes: Long? = null,
    ): Pair<Instant, Instant>? {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        for (offset in 0..368) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek != block.dayOfWeek) continue
            if (!CourseWeekPattern.appliesOn(block.weekPattern, date, semesterFirstWeekMonday)) continue
            eventTrigger(date.atStartOfDay().plusMinutes(block.startMinute.toLong()), zone, now, leadMinutes)
                ?.let { return it }
        }
        return null
    }

    private fun nextDailyReview(now: Instant, zone: ZoneId): Instant {
        val localNow = LocalDateTime.ofInstant(now, zone)
        val todayReview = localNow.toLocalDate().atTime(DAILY_REVIEW_HOUR, DAILY_REVIEW_MINUTE)
        val next = if (todayReview.atZone(zone).toInstant().isAfter(now)) {
            todayReview
        } else {
            todayReview.plusDays(1)
        }
        return next.atZone(zone).toInstant()
    }
}

data class DailyReviewSummary(
    val plannedSegmentCount: Int,
    val pendingSegmentCount: Int,
    val feedbackCount: Int,
    val actualDurationMinutes: Int,
    val progressByTask: List<DailyTaskProgress>,
    val nextSuggestedTaskId: String?,
)

data class DailyTaskProgress(
    val taskId: String,
    val taskName: String,
    val progressPercent: Int?,
    val completionResult: String?,
)

/** Summarises only recorded evidence. It never infers or updates a task status. */
object DailyReviewSummarizer {
    fun summarize(
        date: LocalDate,
        tasks: List<Task>,
        plan: ConfirmedPlan?,
        logs: List<TaskExecutionLog>,
    ): DailyReviewSummary {
        val taskById = tasks.associateBy(Task::id)
        val scheduled = plan?.segments.orEmpty().filter { it.date == date }
        val scheduledTaskIds = scheduled.map(PlannedSegment::taskId).toSet()
        val progress = logs.mapNotNull { log ->
            taskById[log.taskId]?.let { task ->
                DailyTaskProgress(
                    taskId = task.id,
                    taskName = task.displayName,
                    progressPercent = log.feedback.progressPercent,
                    completionResult = log.feedback.completionResult,
                )
            }
        }
        val pending = scheduled.count { segment ->
            taskById[segment.taskId]?.status?.isActive == true
        }
        val nextTask = tasks.firstOrNull { task ->
            task.status.isActive && task.id !in scheduledTaskIds
        } ?: tasks.firstOrNull { task -> task.status.isActive }
        return DailyReviewSummary(
            plannedSegmentCount = scheduled.size,
            pendingSegmentCount = pending,
            feedbackCount = logs.size,
            actualDurationMinutes = logs.sumOf { it.feedback.actualDurationMinutes ?: 0 },
            progressByTask = progress,
            nextSuggestedTaskId = nextTask?.id,
        )
    }
}
