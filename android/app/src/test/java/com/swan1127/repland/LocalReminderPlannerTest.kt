package com.swan1127.repland

import com.swan1127.repland.domain.model.ConfirmedPlan
import com.swan1127.repland.domain.model.DailyReviewSummarizer
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.LocalReminderPlanner
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.ReminderKind
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskExecutionLog
import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReminderPlannerTest {
    private val date = LocalDate.of(2026, 9, 14)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `only a confirmed current plan produces local reminder candidates`() {
        val reminders = LocalReminderPlanner.plan(
            currentPlan = plan(reminderEnabled = true),
            tasks = listOf(activeTask(), inactiveTask()),
            clock = clock,
        )

        assertEquals(
            listOf(ReminderKind.SEGMENT_START, ReminderKind.DAILY_REVIEW, ReminderKind.DEADLINE),
            reminders.map { it.kind },
        )
        assertEquals("current:future", reminders.first().key)
        assertFalse(reminders.any { it.taskId == "inactive" })
    }

    @Test
    fun `no confirmed plan means no start deadline or review reminder`() {
        val reminders = LocalReminderPlanner.plan(
            currentPlan = null,
            tasks = listOf(activeTask()),
            clock = clock,
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `a segment without its reminder switch on produces no start alarm`() {
        val reminders = LocalReminderPlanner.plan(
            currentPlan = plan(reminderEnabled = false),
            tasks = listOf(activeTask()),
            clock = clock,
        )

        assertFalse(reminders.any { it.kind == ReminderKind.SEGMENT_START })
    }

    @Test
    fun `a weekly block with its reminder on fires at its next occurrence`() {
        // 2026-09-14 is a Monday; 08:00 UTC is before a 09:00 start the same day.
        val reminders = LocalReminderPlanner.plan(
            currentPlan = null,
            tasks = emptyList(),
            weeklyBlocks = listOf(
                WeeklyTimeBlock(
                    id = "math",
                    title = "高等数学",
                    kind = TimeBlockKind.COURSE,
                    dayOfWeek = DayOfWeek.MONDAY,
                    startMinute = 9 * 60,
                    endMinute = 10 * 60,
                    weekPattern = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                    reminderEnabled = true,
                ),
            ),
            clock = clock,
        )

        val weekly = reminders.filter { it.kind == ReminderKind.WEEKLY_BLOCK_START }
        assertEquals(1, weekly.size)
        assertEquals("weekly:math", weekly.single().key)
        assertEquals("高等数学", weekly.single().taskName)
        // 提前 10 分钟响，通知才来得及让人切换状态；开始那一刻另存在 startAt 上供文案倒计时。
        assertEquals(
            date.atStartOfDay().plusMinutes(9 * 60L - LocalReminderPlanner.EventLeadMinutes).toInstant(ZoneOffset.UTC),
            weekly.single().triggerAt,
        )
        assertEquals(date.atStartOfDay().plusMinutes(9 * 60L).toInstant(ZoneOffset.UTC), weekly.single().startAt)
    }

    @Test
    fun `a start already inside the lead window rings at its own minute instead of being lost`() {
        val reminders = LocalReminderPlanner.plan(
            currentPlan = null,
            tasks = emptyList(),
            dateOverrides = listOf(
                DateOverride(
                    id = "soon",
                    title = "导师面谈",
                    type = DateOverrideType.BLOCKED,
                    date = date,
                    startMinute = 8 * 60 + 5,
                    endMinute = 9 * 60,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                    reminderEnabled = true,
                ),
            ),
            clock = clock,
        )

        // 08:00 时提前量已经过去：宁可踩点响，也不能因为「提前」已经不可能就整条丢掉。
        val override = reminders.filter { it.kind == ReminderKind.OVERRIDE_START }.single()
        assertEquals(date.atStartOfDay().plusMinutes(8 * 60L + 5).toInstant(ZoneOffset.UTC), override.triggerAt)
        assertEquals(override.triggerAt, override.startAt)
    }

    @Test
    fun `a one-day override with its reminder on fires at its start`() {
        val reminders = LocalReminderPlanner.plan(
            currentPlan = null,
            tasks = emptyList(),
            dateOverrides = listOf(
                DateOverride(
                    id = "meet",
                    title = "班会",
                    type = DateOverrideType.BLOCKED,
                    date = date,
                    startMinute = 14 * 60,
                    endMinute = 15 * 60,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                    reminderEnabled = true,
                ),
            ),
            clock = clock,
        )

        val overrides = reminders.filter { it.kind == ReminderKind.OVERRIDE_START }
        assertEquals(1, overrides.size)
        assertEquals("override:meet", overrides.single().key)
    }

    @Test
    fun `daily review summarises recorded evidence without changing active task state`() {
        val task = activeTask()
        val original = task.copy()
        val log = TaskExecutionLog(
            id = "log",
            taskId = task.id,
            eventType = com.swan1127.repland.domain.model.ExecutionLogEventType.FEEDBACK,
            confirmedStatus = TaskStatus.IN_PROGRESS,
            feedback = TaskFeedback(actualDurationMinutes = 45, progressPercent = 50, completionResult = "掌握核心概念"),
            correctedLogId = null,
            replacementTaskId = null,
            createdAtEpochMillis = 1,
        )

        val summary = DailyReviewSummarizer.summarize(date, listOf(task), plan(), listOf(log))

        assertEquals(1, summary.plannedSegmentCount)
        assertEquals(1, summary.pendingSegmentCount)
        assertEquals(45, summary.actualDurationMinutes)
        assertEquals(50, summary.progressByTask.single().progressPercent)
        assertEquals(original, task)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
    }

    private fun plan(reminderEnabled: Boolean = false) = ConfirmedPlan(
        id = "current",
        createdAtEpochMillis = 1,
        isCurrent = true,
        segments = listOf(
            PlannedSegment(
                id = "future",
                taskId = "active",
                date = date,
                startMinute = 9 * 60,
                endMinute = 10 * 60,
                reminderEnabled = reminderEnabled,
            ),
        ),
    )

    private fun activeTask() = Task(
        id = "active",
        description = "",
        displayName = "复习数据结构",
        category = TaskCategory.COURSE,
        userPriority = TaskPriority.HIGH,
        estimatedDays = 1,
        totalDurationMinutes = 60,
        dueDate = date.plusDays(2),
        status = TaskStatus.IN_PROGRESS,
        completionSummary = null,
        actualDurationMinutes = null,
        progressPercent = null,
        postponeCount = 0,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun inactiveTask() = activeTask().copy(
        id = "inactive",
        status = TaskStatus.COMPLETED,
        dueDate = date.plusDays(3),
    )
}
