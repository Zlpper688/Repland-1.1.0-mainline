package com.swan1127.repland

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.PlanningAgentState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-tests the user-controlled path through the actual Compose activity. It uses
 * a unique task instead of clearing application storage, so the test never erases a
 * developer's local data when it is run manually on a dedicated test device.
 */
@RunWith(AndroidJUnit4::class)
class CoreWorkflowUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(composeRule.activity)
    }

    @Test
    fun single_page_capture_keeps_draft_unsaved_until_save() {
        val taskName = "一页创建 ${System.currentTimeMillis()}"
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-duration")
        waitForTag("task-capture-duration-30")
        composeRule.onNodeWithTag("task-capture-duration-30").performClick()
        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        assertEquals(false, runBlocking { repository.observeTasks().first().any { it.displayName == taskName } })
        clickCapture("task-capture-date")
        waitForTag("task-capture-date-today")
        composeRule.onNodeWithTag("task-capture-date-today").performClick()
        clickCapture("task-capture-save-inbox")
        val saved = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName } }
                    .single { it.displayName == taskName }
            }
        }
        assertEquals(30, saved.totalDurationMinutes)
        assertEquals(null, saved.dueDate)
        assertEquals(java.time.LocalDate.now(), saved.scheduledForDate)
        assertEquals(TaskStatus.NOT_STARTED, saved.status)
    }

    @Test
    fun capture_exposes_a_free_deadline_picker_beyond_shortcuts() {
        val taskName = "自由截止日 ${System.currentTimeMillis()}"
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-deadline")
        waitForTag("task-capture-deadline-custom")
        clickCapture("task-capture-deadline-custom")
        waitForTag("task-date-picker-confirm")
        // The date picker is modal. Close the exploratory branch, then pick a shortcut
        // and save explicitly, as a person would leave the form.
        composeRule.onNodeWithTag("task-date-picker-dismiss").performClick()
        clickCapture("task-capture-deadline")
        waitForTag("task-capture-deadline-today")
        clickCapture("task-capture-deadline-today")
        clickCapture("task-capture-save-inbox")
        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        val saved = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName } }
                    .single { it.displayName == taskName }
            }
        }
        assertEquals(java.time.LocalDate.now(), saved.dueDate)
        // Selecting a deadline does not assert a separate intended planning date.
        assertEquals(null, saved.scheduledForDate)
        waitForTag("task-filter-TODAY")
        composeRule.onNodeWithTag("task-filter-TODAY").performClick()
        waitForTag("task-list-scroll")
        composeRule.onNodeWithTag("task-list-scroll").performScrollToNode(hasTestTag("task-card-$taskName"))
        waitForTag("task-card-$taskName")
    }

    @Test
    fun capture_accepts_a_custom_duration_without_forcing_a_preset() {
        val taskName = "自定义时长 ${System.currentTimeMillis()}"
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-duration")
        waitForTag("task-capture-duration-custom")
        clickCapture("task-capture-duration-custom")
        waitForTag("task-capture-duration-custom-input")
        composeRule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().performClick().performTextInput("45")
        composeRule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains("45")
        clickCapture("task-capture-save-inbox")

        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        val saved = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName } }
                    .single { it.displayName == taskName }
            }
        }
        assertEquals(45, saved.totalDurationMinutes)
        assertEquals(null, saved.scheduledForDate)
        assertEquals(null, saved.dueDate)
    }

    @Test
    fun status_start_then_complete_without_timer_keeps_actual_duration_unknown() {
        val taskName = "直接完成 ${System.currentTimeMillis()}"
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-duration")
        waitForTag("task-capture-duration-15")
        composeRule.onNodeWithTag("task-capture-duration-15").performClick()
        clickCapture("task-capture-date")
        waitForTag("task-capture-date-today")
        composeRule.onNodeWithTag("task-capture-date-today").performClick()
        clickCapture("task-capture-save-inbox")
        showSavedTask(taskName, "TODAY")
        waitForTag("task-card-$taskName")
        composeRule.onNodeWithTag("task-card-$taskName").performClick()
        waitForTag("task-detail-scroll")
        composeRule.onNodeWithTag("task-detail-scroll").performScrollToNode(hasTestTag("start-task"))
        // 开始专注会在用的当下重新校验通知权限；没授权时系统弹窗会吃掉这一下点击。
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(composeRule.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        composeRule.onNodeWithTag("start-task").assertIsDisplayed().assertIsEnabled().performClick()
        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName && it.status == TaskStatus.IN_PROGRESS } }
            }
        }
        composeRule.onNodeWithTag("task-detail-scroll").performScrollToNode(hasTestTag("complete-task"))
        composeRule.onNodeWithTag("complete-task").assertIsDisplayed().assertIsEnabled().performClick()
        val completed = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName && it.status == TaskStatus.COMPLETED } }
                    .single { it.displayName == taskName }
            }
        }
        assertEquals(null, completed.completionSummary)
        assertEquals(100, completed.progressPercent)
        assertEquals(null, completed.actualDurationMinutes)
        val logs = runBlocking { repository.observeExecutionLogs(completed.id).first() }
        assertEquals(true, logs.isNotEmpty())
        assertEquals(true, logs.all { it.feedback.actualDurationMinutes == null })
    }

    @Test
    fun editable_voice_capture_can_become_a_task_draft_without_auto_saving() {
        openTaskCapture()
        waitForTag("voice-capture")
        composeRule.onNodeWithTag("voice-capture").performClick()
        waitForTag("voice-transcript")
        composeRule.onNodeWithTag("voice-transcript").performTextReplacement("准备英语听力")
        composeRule.onNodeWithTag("voice-to-task").performClick()
        waitForTag("task-capture-input")
        composeRule.onNodeWithTag("task-capture-input").assertTextContains("准备英语听力")
    }

    @Test
    fun task_entry_feedback_and_local_controls_are_user_driven() {
        val taskName = "UI 回归 ${System.currentTimeMillis()}"
        val feedbackContent = "已完成 UI 回归反馈"

        // Creation belongs to the task inbox; the day canvas is now for arranging objects.
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-save-inbox")

        // Verify the transient commit receipt before scrolling a retained inbox.
        waitForTag("page-save-snackbar")
        composeRule.onNodeWithText("已保存“$taskName”到待安排。", useUnmergedTree = true).assertExists()
        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        val taskId = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName } }
                    .single { it.displayName == taskName }
            }
        }.id
        showSavedTask(taskName, "INBOX")
        waitForTag("task-card-$taskName")
        composeRule.onNodeWithTag("task-card-$taskName").performClick()
        waitForTag("task-detail-scroll")
        // 记录执行反馈只在已结束的事项上提供：先把这件事按用户路径收尾（取消），
        // 验证反馈仍然只是证据，不会把状态悄悄改成完成。
        runBlocking { repository.confirmStatus(taskId, TaskStatus.CANCELLED) }
        waitForTag("record-feedback")
        composeRule.onNodeWithTag("task-detail-scroll").performScrollToNode(hasTestTag("record-feedback"))
        composeRule.onNodeWithTag("record-feedback").performScrollTo().assertIsDisplayed().performClick()
        waitForTag("feedback-content")
        composeRule.onNodeWithTag("feedback-content").performTextInput(feedbackContent)
        composeRule.onNodeWithTag("feedback-confirm").performClick()

        val task = runBlocking {
            withTimeout(10_000) {
                repository.observeTasks()
                    .first { tasks ->
                        tasks.any { it.displayName == taskName && it.completionSummary == feedbackContent }
                    }
                    .single { it.displayName == taskName }
            }
        }
        // A feedback record is evidence only; it must not silently change the task status.
        assertEquals(TaskStatus.CANCELLED, task.status)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("feedback-content").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("task-detail-back").performClick()
        composeRule.openMineSettings()
        waitForTag("mine-time-settings")
        composeRule.onNodeWithTag("mine-time-settings").performScrollTo().performClick()
        // The document picker is exposed, but no PDF can write constraints before its
        // preview is explicitly confirmed (covered by PdfTimetableImporterTest).
        waitForTag("import-timetable-pdf")
        composeRule.onNodeWithTag("import-timetable-pdf").performScrollTo()

        composeRule.openMineSettings()
        waitForTag("local-reminders-switch")
    }

    @Test
    fun bounded_agent_requires_preview_then_falls_back_without_changing_the_task_or_plan() {
        val taskName = "Agent UI ${System.currentTimeMillis()}"
        composeRule.openMineSettings()
        waitForTag("ai-advisor-switch")
        val switchNode = composeRule.onNodeWithTag("ai-advisor-switch").fetchSemanticsNode()
        if (switchNode.config[SemanticsProperties.ToggleableState] != ToggleableState.On) {
            composeRule.onNodeWithTag("ai-advisor-switch").performScrollTo().performClick()
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithTag("ai-consent-confirm", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                composeRule.onNodeWithTag("ai-consent-confirm").performClick()
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onNodeWithTag("ai-advisor-switch").fetchSemanticsNode()
                    .config[SemanticsProperties.ToggleableState] == ToggleableState.On
            }
        }

        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-save-inbox")
        showSavedTask(taskName, "INBOX")
        waitForTag("task-card-$taskName")
        composeRule.onNodeWithTag("task-card-$taskName").performClick()
        waitForTag("task-detail-scroll")
        // AI 协助入口收在详情页的溢出菜单里，不再平铺在正文。
        composeRule.onNodeWithTag("task-detail-more").performClick()
        waitForTag("menu-ai-assist")
        composeRule.onNodeWithTag("menu-ai-assist").performClick()
        waitForTag("assistant-prompt")
        // This test verifies the bounded request/confirmation contract. Use the
        // actual suggestion chip so a retained IME cannot intercept its Send tap.
        // Raw keyboard input is exercised by the capture and AgentCenter tests.
        composeRule.onNodeWithText("帮我理解这项任务").performClick()
        composeRule.onNodeWithTag("assistant-prompt").assertTextContains("帮我理解这项任务")
        val container = (composeRule.activity.application as ReplandApplication).appContainer
        val planBefore = runBlocking { container.planRepository.observeCurrentPlan().first() }
        composeRule.onNodeWithTag("assistant-submit").assertIsEnabled().performClick()
        waitForTag("ai-request-confirm")
        composeRule.onNodeWithTag("ai-request-confirm").performClick()
        val workflow = (composeRule.activity.application as ReplandApplication)
            .appContainer.planningAgentWorkflow
        composeRule.waitUntil(timeoutMillis = 15_000) {
            workflow.state.value is PlanningAgentState.FailedFallback
        }
        waitForTag("planning-agent-outcome")

        val task = runBlocking {
            withTimeout(10_000) {
                (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
                    .observeTasks()
                    .first { tasks -> tasks.any { it.displayName == taskName } }
                    .single { it.displayName == taskName }
            }
        }
        assertEquals(TaskStatus.NOT_STARTED, task.status)
        assertEquals(planBefore, runBlocking { container.planRepository.observeCurrentPlan().first() })
    }

    private fun showSavedTask(taskName: String, filter: String) {
        val repository = (composeRule.activity.application as ReplandApplication).appContainer.taskRepository
        runBlocking {
            withTimeout(10_000) {
                repository.observeTasks().first { tasks -> tasks.any { it.displayName == taskName } }
            }
        }
        waitForTag("task-filter-$filter")
        composeRule.onNodeWithTag("task-filter-$filter").performClick()
        waitForTag("task-list-scroll")
        composeRule.onNodeWithTag("task-list-scroll").performScrollToNode(hasTestTag("task-card-$taskName"))
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun capture_recreation_keeps_editing_and_exit_discards_draft() {
        val taskName = "草稿恢复 ${System.currentTimeMillis()}"
        openTaskCapture()
        composeRule.onNodeWithTag("task-capture-input").performTextReplacement(taskName)
        clickCapture("task-capture-duration")
        waitForTag("task-capture-duration-custom")
        clickCapture("task-capture-duration-custom")
        composeRule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().performClick().performTextReplacement("45")
        composeRule.activityRule.scenario.recreate()
        waitForTag("task-capture-duration-custom-input")
        composeRule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains("45")
        val container = (composeRule.activity.application as ReplandApplication).appContainer
        assertFalse(runBlocking { container.taskRepository.observeTasks().first().any { it.displayName == taskName } })
        // 退出即意图：确认一次后草稿整体放弃，重开是白纸。
        clickCapture("task-capture-close")
        waitForTag("task-capture-confirm-exit")
        composeRule.onNodeWithTag("task-capture-confirm-exit").performClick()
        waitForTag("add-task")
        composeRule.onNodeWithTag("add-task").performClick()
        waitForTag("task-capture-input")
        composeRule.waitUntil(10_000) {
            composeRule.onNodeWithTag("task-capture-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty()
        }
        assertFalse(runBlocking { container.taskRepository.observeTasks().first().any { it.displayName == taskName } })
        clickCapture("task-capture-close")
    }

    private fun clickCapture(tag: String) {
        // Footer actions are outside the scrolling body. Hide the real IME first,
        // then scroll only controls that actually have a scrollable ancestor.
        if (NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")) {
            NativeSheetTestInput.back(composeRule, "新任务")
            composeRule.waitUntil(10_000) {
                !NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")
            }
            composeRule.waitForIdle()
        }
        val node = composeRule.onNodeWithTag(tag)
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun openTaskCapture() {
        check(composeRule.activity.packageName == "com.swan1127.repland.qa")
        waitForTag("navigation-tasks")
        composeRule.onNodeWithTag("navigation-tasks").performClick()
        waitForTag("add-task")
        composeRule.onNodeWithTag("add-task").performClick()
        waitForTag("task-capture-input")
        // 每个工作流从白纸开始；若有残留文字，用清空按钮而不是替用户保存。
        if (composeRule.onAllNodesWithTag("task-capture-clear").fetchSemanticsNodes().isNotEmpty()) {
            clickCapture("task-capture-clear")
        }
    }
}
