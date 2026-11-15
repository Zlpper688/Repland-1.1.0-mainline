package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.domain.model.DateOverrideDraft
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.SegmentAuthority
import com.swan1127.repland.domain.model.TaskInputSource
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.planteam.runtime.AcceptanceFinished
import com.swan1127.repland.domain.planteam.runtime.DraftReady
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the disposable QA installation on the dedicated test emulator. */
@RunWith(AndroidJUnit4::class)
class UnifiedAppWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = (rule.activity.application as ReplandApplication).appContainer

    @Before fun resetTheIsolatedQaInstallation() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        runBlocking {
            app.autonomyRepository.setEnabled(false)
            app.dataManagementRepository.clearAllLocalData()
            app.autonomyRepository.setEnabled(false)
            app.aiSettingsRepository.setEnabled(false)
        }
        // Refresh UI caches and restore the actual cold-start route after the repository reset.
        rule.activityRule.scenario.recreate()
        waitTag("navigation-today")
    }

    private fun waitTag(tag: String) = rule.waitUntil(15_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun hideImeIfVisible() {
        if (NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")) {
            NativeSheetTestInput.shell("input keyevent 4")
            rule.waitUntil(10_000) {
                !NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")
            }
        }
        rule.waitForIdle()
    }

    /**
     * Capture only makes its body scrollable when the window is too short to show the whole step
     * (see `compactHeight` in TaskExperienceComponents). Calling `performScrollTo()` unconditionally
     * therefore fails on a regular-height display, where the node is already on screen but has no
     * scrollable ancestor.
     */
    private fun revealTag(tag: String): SemanticsNodeInteraction {
        val node = rule.onNodeWithTag(tag)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        return node
    }

    @Test fun realTaskAndAvailabilityReachTheTeamAndLearningMemoryThroughTheApp() {
        val name = "融合流程英语复习"
        val content = "recall 是主动提取，recognition 是看到后识别"
        rule.onNodeWithTag("navigation-tasks").performClick()
        waitTag("add-task")
        rule.onNodeWithTag("add-task").performClick()
        waitTag("task-capture-sheet")
        waitTag("task-capture-input")
        rule.onNodeWithTag("task-capture-input").performTextReplacement(name)
        hideImeIfVisible()
        revealTag("task-capture-duration").performClick()
        waitTag("task-capture-duration-30")
        rule.onNodeWithTag("task-capture-duration-30").performClick()
        revealTag("task-capture-date").performClick()
        waitTag("task-capture-date-today")
        rule.onNodeWithTag("task-capture-date-today").performClick()
        rule.onNodeWithTag("task-capture-save-inbox").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("task-capture-input").fetchSemanticsNodes().isEmpty() &&
                runBlocking { app.taskRepository.observeTasks().first().size == 1 }
        }
        val task = runBlocking { app.taskRepository.observeTasks().first().single() }
        assertEquals(name, task.displayName)
        assertEquals(30, task.totalDurationMinutes)
        assertEquals(TaskInputSource.USER_INPUT, task.inputSources.duration)
        assertEquals(TaskStatus.NOT_STARTED, task.status)

        val availableDay = LocalDate.now().plusDays(1)
        runBlocking {
            app.timeRepository.saveDateOverride(DateOverrideDraft(
                title = "集成测试可用时间", date = availableDay, type = DateOverrideType.AVAILABLE,
                startMinute = 9 * 60, endMinute = 12 * 60,
            ))
        }
        assertNull(runBlocking { app.planRepository.observeCurrentPlan().first() })
        rule.onNodeWithTag("navigation-today").performClick()
        waitTag("preview-task-plan")
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("preview-task-plan"))
        rule.onNodeWithTag("preview-task-plan").assertIsEnabled().performClick()
        waitTag("accept-plan-draft")
        waitTag("team-log")
        rule.onNodeWithTag("team-log").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("验收通过，仍需你确认应用").assertExists()
        val preview = runBlocking { requireNotNull(app.planRepository.observeDraft().first()) }
        val team = app.planningOperationService.teamPreview.value
        assertEquals(6, team.result!!.selectedAgents.size)
        assertTrue(team.events.last() is DraftReady)
        assertTrue(team.events.filterIsInstance<AcceptanceFinished>().single().passed)
        assertEquals(preview, team.draft)
        assertTrue(preview.segments.any { it.taskId == task.id && it.date == availableDay })
        assertNull(runBlocking { app.planRepository.observeCurrentPlan().first() })
        rule.onNodeWithTag("accept-plan-draft").assertIsEnabled().performClick()
        rule.waitUntil(15_000) {
            runBlocking { app.planRepository.observeCurrentPlan().first() != null &&
                app.planRepository.observeDraft().first() == null }
        }
        val confirmed = runBlocking { app.planRepository.observeCurrentPlan().first()!! }
        // 确认过的草案整份算用户拍板：落库时每段都盖上 USER_PINNED，行 id 换成持久身份，
        // 但 lineage key 沿用草案里那段临时 id —— 自动调整靠它认出「还是那一段」。
        assertEquals(
            preview.segments.map { it.copy(id = "", authority = SegmentAuthority.USER_PINNED, stableKey = it.id) }
                .sortedWith(compareBy({ it.date }, { it.startMinute })),
            confirmed.segments.map { it.copy(id = "") },
        )
        assertTrue(confirmed.segments.all { it.id.isNotBlank() && !it.id.startsWith("draft:") })
        assertTrue(app.auxiliaryStore.reviewPoints().isEmpty())
        assertTrue(app.auxiliaryStore.memoryReferenceEnabled)

        // 记忆不再由用户手写录入：完成内容落库时才沉淀成一条记录。
        app.auxiliaryStore.addReviewPointsFor(task.copy(completionResult = content), today = LocalDate.now())
        val point = app.auxiliaryStore.reviewPoints().single()
        assertEquals(task.id, point.taskId)
        assertEquals(content, point.content)
        assertNotNull(point.memory)
        assertNotNull(app.auxiliaryStore.memoryReference())

        // The profile snapshot in the composition is keyed on memoryRevision, which only the app's
        // own write path bumps; a direct store write needs a fresh read.
        rule.activityRule.scenario.recreate()
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-mine").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodes(
                hasTestTag("memory-stats-line") and hasText("记忆画像：1 条记录", substring = true),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        revealTag("mine-settings").performClick()
        waitTag("mine-settings-page")
        revealTag("learning-memory-reference-switch").assertIsOn().performClick()
        rule.onNodeWithTag("learning-memory-reference-switch").assertIsOff()
        assertNull(app.auxiliaryStore.memoryReference())
        // 关掉规划参考不影响画像：它只是不再让自动排期引用记忆，记录本身仍然可读。
        assertEquals(1, app.auxiliaryStore.memoryProfileSummary(LocalDate.now()).signals.size)
        rule.activityRule.scenario.recreate()
        waitTag("mine-settings-page")
        revealTag("learning-memory-reference-switch").assertIsOff()
        assertEquals(point, app.auxiliaryStore.reviewPoints().single())
        assertEquals(confirmed, runBlocking { app.planRepository.observeCurrentPlan().first() })
        assertEquals(TaskStatus.NOT_STARTED, runBlocking { app.taskRepository.observeTasks().first().single().status })
    }
}
