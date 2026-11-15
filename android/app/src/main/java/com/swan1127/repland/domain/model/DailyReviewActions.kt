package com.swan1127.repland.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** 每日回顾里一项待确认的工作段：时段已结束、任务仍活跃；系统永远不替人判定结果。 */
data class DailyReviewPendingItem(
    val taskId: String,
    val taskName: String,
    val segmentId: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
)

enum class DailyReviewDecision { COMPLETE, PARTIAL, POSTPONE }

sealed interface DailyReviewCommand {
    /** 直接确认状态：完成 / 顺延（复用 confirmStatus 落库路径）。 */
    data class ConfirmStatus(val taskId: String, val status: TaskStatus, val feedback: TaskFeedback = TaskFeedback()) : DailyReviewCommand

    /** 部分完成必须录入进度，交给已有的 PartialCompletionDialog 收集后走 recordPartialCompletion。 */
    data class RecordPartial(val taskId: String) : DailyReviewCommand
}

/**
 * 纯编排：把回顾对话框的每个决策映射到哪一次落库。「跳过回顾」不在决策之列——
 * 它只是关闭界面，不产生任何命令，也就是零写入。
 */
object DailyReviewActions {

    fun pendingItems(date: LocalDate, now: LocalDateTime, tasks: List<Task>, segments: List<PlannedSegment>): List<DailyReviewPendingItem> =
        segments.asSequence()
            .filter { segment ->
                segment.date == date && segment.endMinute <= now.toLocalTime().let { it.hour * 60 + it.minute } &&
                    tasks.firstOrNull { it.id == segment.taskId }?.status?.isActive == true
            }
            .map { segment ->
                DailyReviewPendingItem(
                    taskId = segment.taskId,
                    taskName = tasks.first { it.id == segment.taskId }.displayName,
                    segmentId = segment.id,
                    date = segment.date,
                    startMinute = segment.startMinute,
                    endMinute = segment.endMinute,
                )
            }
            .sortedWith(compareBy(DailyReviewPendingItem::startMinute, DailyReviewPendingItem::segmentId))
            .toList()

    fun command(item: DailyReviewPendingItem, decision: DailyReviewDecision): DailyReviewCommand = when (decision) {
        DailyReviewDecision.COMPLETE -> DailyReviewCommand.ConfirmStatus(item.taskId, TaskStatus.COMPLETED)
        DailyReviewDecision.PARTIAL -> DailyReviewCommand.RecordPartial(item.taskId)
        DailyReviewDecision.POSTPONE -> DailyReviewCommand.ConfirmStatus(item.taskId, TaskStatus.POSTPONED)
    }
}
