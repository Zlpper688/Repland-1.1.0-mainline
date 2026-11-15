package com.swan1127.repland.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReviewActionsTest {
    private val day = LocalDate.of(2026, 11, 2)

    private fun task(id: String, status: TaskStatus) = Task(
        id, "任务$id", "任务$id", TaskCategory.COURSE, TaskPriority.MEDIUM,
        1, 60, null, status, null, null, null, 0, 0, 0)

    private fun segment(id: String, taskId: String, start: Int, end: Int) =
        PlannedSegment(id, taskId, day, start, end)

    @Test fun pendingItemsKeepOnlyEndedSegmentsOfActiveTasks() {
        val tasks = listOf(
            task("a", TaskStatus.NOT_STARTED),
            task("b", TaskStatus.IN_PROGRESS),
            task("done", TaskStatus.COMPLETED),
        )
        val segments = listOf(
            segment("ended-a", "a", 8 * 60, 9 * 60),
            segment("running-b", "b", 13 * 60, 15 * 60),
            segment("ended-done", "done", 8 * 60, 8 * 60 + 30),
        )
        val items = DailyReviewActions.pendingItems(day, day.atTime(14, 0), tasks, segments)
        assertEquals(listOf("ended-a"), items.map { it.segmentId })
        assertEquals("任务a", items.single().taskName)
        assertEquals("a", items.single().taskId)
    }

    @Test fun pendingItemsIgnoreSegmentsFromOtherDaysAndSortByStart() {
        val tasks = listOf(task("a", TaskStatus.NOT_STARTED))
        val segments = listOf(
            segment("later", "a", 15 * 60, 16 * 60),
            segment("earlier", "a", 9 * 60, 10 * 60),
            segment("yesterday", "a", 8 * 60, 9 * 60).copy(date = day.minusDays(1)),
        )
        val items = DailyReviewActions.pendingItems(day, day.atTime(18, 0), tasks, segments)
        assertEquals(listOf("earlier", "later"), items.map { it.segmentId })
    }

    @Test fun completeDecisionConfirmsCompletedStatus() {
        val item = DailyReviewPendingItem("a", "任务a", "s1", day, 480, 540)
        val command = DailyReviewActions.command(item, DailyReviewDecision.COMPLETE)
        assertTrue(command is DailyReviewCommand.ConfirmStatus)
        command as DailyReviewCommand.ConfirmStatus
        assertEquals("a", command.taskId)
        assertEquals(TaskStatus.COMPLETED, command.status)
    }

    @Test fun postponeDecisionConfirmsPostponedStatusWithoutFabricatedReason() {
        val item = DailyReviewPendingItem("a", "任务a", "s1", day, 480, 540)
        val command = DailyReviewActions.command(item, DailyReviewDecision.POSTPONE)
        assertTrue(command is DailyReviewCommand.ConfirmStatus)
        command as DailyReviewCommand.ConfirmStatus
        assertEquals(TaskStatus.POSTPONED, command.status)
    }

    @Test fun partialDecisionRoutesIntoPartialCompletionDialog() {
        val item = DailyReviewPendingItem("a", "任务a", "s1", day, 480, 540)
        assertEquals(DailyReviewCommand.RecordPartial("a"),
            DailyReviewActions.command(item, DailyReviewDecision.PARTIAL))
    }

    @Test fun skipIsNotAWritingDecision() {
        // 「跳过回顾」只是关掉界面：决策枚举里只有会落库的三种，skip 不在其中。
        assertEquals(setOf(DailyReviewDecision.COMPLETE, DailyReviewDecision.PARTIAL, DailyReviewDecision.POSTPONE),
            DailyReviewDecision.entries.toSet())
        // 同一条边界在数据侧：已结束但任务已不活跃（含刚被完成）的段不会再出现。
        val tasks = listOf(task("a", TaskStatus.COMPLETED))
        assertTrue(DailyReviewActions.pendingItems(day, day.atTime(18, 0), tasks,
            listOf(segment("s", "a", 8 * 60, 9 * 60))).isEmpty())
    }
}
