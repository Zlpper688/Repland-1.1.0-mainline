package com.swan1127.repland.domain.model

import java.time.LocalDate

/**
 * 助手确认时的两组语义：
 * - 「排入今天」= 意图是安排、且有明确时段与时长；来源是用户接受了某条时间建议
 *   （ACCEPTED_SUGGESTION），scheduledForDate 落当天。
 * - 「只记入事项库」= 其余一切（含时间待定的提案）。它是用户逐字确认过的录入
 *   （USER_INPUT），不排日程（scheduledForDate 为空）——时间待定的提案此前被静默
 *   存成任务并误标为「已接受建议」，这里把两条语义拆开。
 */
object AgentConfirmationSplit {

    fun isScheduled(proposal: AssistantTaskProposal, intent: ArrangementIntent?): Boolean =
        intent != ArrangementIntent.CAPTURE_TASKS &&
            proposal.timeHint.explicitStartMinute != null &&
            proposal.durationMinutes != null

    fun taskDraft(proposal: AssistantTaskProposal, intent: ArrangementIntent?, activeDate: LocalDate): TaskDraft {
        val scheduled = isScheduled(proposal, intent)
        val draft = TaskDraft(
            id = proposal.id,
            displayName = TaskName.fromDescription(proposal.text),
            description = proposal.text,
            category = proposal.category,
            userPriority = TaskPriority.UNSPECIFIED,
            estimatedDays = null,
            totalDurationMinutes = proposal.durationMinutes,
            dueDate = null,
            scheduledForDate = activeDate.takeIf { scheduled },
        )
        val source = if (scheduled) TaskInputSource.ACCEPTED_SUGGESTION else TaskInputSource.USER_INPUT
        return draft.copy(inputSources = TaskInputSources.forInput(
            draft.category, draft.userPriority, draft.estimatedDays, draft.totalDurationMinutes,
            draft.dueDate, draft.scheduledForDate, source))
    }

    /** 新建任务的草稿只有这两个去向；已有任务的调整不在这里（沿用 segments 路径）。 */
    fun taskDrafts(proposals: List<AssistantTaskProposal>, intent: ArrangementIntent?, activeDate: LocalDate): List<TaskDraft> =
        proposals.filter { it.existingTaskId == null }.map { taskDraft(it, intent, activeDate) }
}
