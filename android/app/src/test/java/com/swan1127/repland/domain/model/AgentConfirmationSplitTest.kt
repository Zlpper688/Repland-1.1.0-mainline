package com.swan1127.repland.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConfirmationSplitTest {
    private val activeDate = LocalDate.of(2026, 11, 3)

    private fun proposal(
        id: String = "p1",
        duration: Int? = 60,
        start: Int? = 9 * 60,
        existingTaskId: String? = null,
    ) = AssistantTaskProposal(
        id = id,
        text = "复习高数",
        category = TaskCategory.COURSE,
        durationMinutes = duration,
        timeHint = ArrangementTimeHint(explicitStartMinute = start),
        needsClarification = emptySet(),
        placementSource = if (start != null) ArrangementPlacementSource.USER_EXPLICIT else ArrangementPlacementSource.UNSCHEDULED,
        existingTaskId = existingTaskId,
    )

    @Test fun scheduledProposalKeepsAcceptedSuggestionAndTodayDate() {
        val draft = AgentConfirmationSplit.taskDraft(proposal(), ArrangementIntent.ARRANGE_TODAY, activeDate)
        assertEquals(TaskInputSource.ACCEPTED_SUGGESTION, draft.inputSources.scheduledDate)
        assertEquals(activeDate, draft.scheduledForDate)
    }

    @Test fun undecidedTimeProposalGoesToCaptureOnlyWithUserInputAndNoDate() {
        // 时间待定的提案默认落在「只记入」组：不排日程，来源是用户录入，不是「已接受建议」。
        val draft = AgentConfirmationSplit.taskDraft(proposal(start = null), ArrangementIntent.ARRANGE_TODAY, activeDate)
        assertEquals(TaskInputSource.USER_INPUT, draft.inputSources.duration)
        assertEquals(TaskInputSource.UNKNOWN, draft.inputSources.scheduledDate)
        assertNull(draft.scheduledForDate)
    }

    @Test fun missingDurationAlsoCountsAsCaptureOnly() {
        val draft = AgentConfirmationSplit.taskDraft(proposal(duration = null), ArrangementIntent.ARRANGE_TODAY, activeDate)
        assertEquals(TaskInputSource.UNKNOWN, draft.inputSources.scheduledDate)
        assertNull(draft.scheduledForDate)
    }

    @Test fun captureIntentNeverSchedulesEvenWithExplicitTime() {
        val draft = AgentConfirmationSplit.taskDraft(proposal(), ArrangementIntent.CAPTURE_TASKS, activeDate)
        assertEquals(TaskInputSource.UNKNOWN, draft.inputSources.scheduledDate)
        assertNull(draft.scheduledForDate)
    }

    @Test fun existingTaskAdjustmentsAreNotNewDrafts() {
        val drafts = AgentConfirmationSplit.taskDrafts(
            listOf(proposal(id = "new"), proposal(id = "edit", existingTaskId = "task-1")),
            ArrangementIntent.ARRANGE_TODAY, activeDate)
        assertEquals(listOf("new"), drafts.map { it.id })
    }

    @Test fun draftsCoverBothGroupsInOneConfirm() {
        val drafts = AgentConfirmationSplit.taskDrafts(
            listOf(proposal(id = "scheduled"), proposal(id = "capture", start = null)),
            ArrangementIntent.ARRANGE_TODAY, activeDate)
        assertEquals(listOf("scheduled", "capture"), drafts.map { it.id })
        assertEquals(activeDate, drafts.first().scheduledForDate)
        assertNull(drafts.last().scheduledForDate)
    }
}
