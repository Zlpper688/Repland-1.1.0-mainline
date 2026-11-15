package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.domain.model.TaskDraft
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composer above the bottom bar is the single assistant entry on every page; manual capture stays available. */
@RunWith(AndroidJUnit4::class)
class OriginalUiAutonomyNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun use_isolated_qa_app_with_automatic_maintenance_paused() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        resetAssistantSessionForTest()
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun closeAssistant() {
        rule.onNodeWithTag("assistant-close").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("assistant-sheet").fetchSemanticsNodes().isEmpty()
        }
        waitTag("today-page-scroll")
    }

    @Test fun assistant_has_one_composer_entry_and_no_bottom_tab() {
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-agent").assertDoesNotExist()
        rule.onNodeWithTag("navigation-today").performClick()
        waitTag("composer-bar")
        rule.onNodeWithTag("composer-bar").assertIsDisplayed()
        rule.onNodeWithTag("add-task").assertDoesNotExist()

        rule.openScheduleAssistant()
        rule.onNodeWithTag("assistant-sheet").assertIsDisplayed()
        rule.onNodeWithTag("group-input").assertIsDisplayed()
        rule.onNodeWithTag("group-send").assertIsDisplayed()
        closeAssistant()

        rule.onNodeWithTag("navigation-tasks").performClick()
        waitTag("add-task")
        rule.onNodeWithTag("add-task").assertIsDisplayed()
        // 输入栏跨页常驻：每页都只有这一个对话入口，不再单独长浮标。
        rule.onNodeWithTag("composer-bar").assertIsDisplayed()
        rule.onNodeWithTag("navigation-mine").performClick()
        waitTag("mine-profile-page")
        waitTag("usage-summary-card")
        rule.onNodeWithTag("usage-summary-text").assertExists()
        rule.onNodeWithTag("numeric-profile-open").assertExists()
        rule.onNodeWithTag("composer-bar").assertIsDisplayed()
        rule.onNodeWithTag("add-task").assertDoesNotExist()
        rule.onNodeWithTag("navigation-agent").assertDoesNotExist()
    }

    @Test fun original_home_capture_still_opens_without_changing_tasks() {
        val tasks = (rule.activity.application as ReplandApplication).appContainer.taskRepository
        val before = runBlocking { tasks.observeTasks().first() }
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-today").performClick()
        // 空日程是「记一件事」胶囊，已有安排时是书堆卡右上角的加号：两种状态都算手动入口。
        val entry = listOf("schedule-create-add", "current-event-add").firstOrNull { tag ->
            runCatching { rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag(tag)) }.isSuccess
        } ?: throw AssertionError("今天的日程页没有任何手动新增入口")
        rule.onNodeWithTag(entry).performClick()
        waitTag("schedule-event-sheet")
        rule.onNodeWithTag("schedule-event-title").assertExists()
        // 一页式表单用返回键收（BackHandler），没有单独的关闭按钮。
        NativeSheetTestInput.shell("input keyevent 4")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("schedule-event-sheet").fetchSemanticsNodes().isEmpty()
        }
        waitTag("today-page-scroll")
        assertEquals(before, runBlocking { tasks.observeTasks().first() })
    }

    @Test fun unsent_assistant_input_survives_close_reopen_and_activity_recreation() {
        val draft = "还未发送的一句话事项 ${UUID.randomUUID()}"
        val app = (rule.activity.application as ReplandApplication).appContainer
        val before = runBlocking { app.taskRepository.observeTasks().first() }
        rule.openScheduleAssistant()
        rule.onNodeWithTag("group-input").performTextReplacement(draft)
        closeAssistant()

        rule.openScheduleAssistant()
        rule.onNodeWithTag("group-input").assertTextContains(draft, substring = false)
        rule.activityRule.scenario.recreate()
        waitTag("assistant-sheet")
        waitTag("group-input")
        rule.onNodeWithTag("group-input").assertTextContains(draft, substring = false)
        closeAssistant()

        rule.activityRule.scenario.recreate()
        rule.openScheduleAssistant()
        rule.onNodeWithTag("group-input").assertTextContains(draft, substring = false)
        assertEquals(before, runBlocking { app.taskRepository.observeTasks().first() })
    }

    @Test fun schedule_assistant_query_preserves_tasks_plans_and_execution_evidence() {
        val app = (rule.activity.application as ReplandApplication).appContainer
        val id = "qa-floating-query-${UUID.randomUUID()}"
        runBlocking { app.taskRepository.save(TaskDraft(id = id, displayName = "悬浮助手查询样例 $id", totalDurationMinutes = 30)) }
        try {
            rule.openScheduleAssistant()
            val tasksBefore = runBlocking { app.taskRepository.observeTasks().first() }
            val plansBefore = runBlocking { app.planRepository.observePlanHistory().first() }
            val currentBefore = runBlocking { app.planRepository.observeCurrentPlan().first() }
            val logsBefore = runBlocking { tasksBefore.associate { it.id to app.taskRepository.observeExecutionLogs(it.id).first() } }
            val delegationBefore = runBlocking { app.autonomyRepository.observe().first() }

            rule.onNodeWithTag("group-input").performTextReplacement("查看待办")
            rule.onNodeWithTag("group-send").performClick()
            // QA 里云端能力是关的：面板必须当场把「这一步没跑通」说清楚，
            // 而不是把这句话吞掉（群聊静默丢失是真实出现过的问题）。
            waitTag("group-notice")
            rule.assertNoticeContains("云端未启用")

            assertEquals(tasksBefore, runBlocking { app.taskRepository.observeTasks().first() })
            assertEquals(plansBefore, runBlocking { app.planRepository.observePlanHistory().first() })
            assertEquals(currentBefore, runBlocking { app.planRepository.observeCurrentPlan().first() })
            assertEquals(logsBefore, runBlocking { tasksBefore.associate { it.id to app.taskRepository.observeExecutionLogs(it.id).first() } })
            assertEquals(delegationBefore, runBlocking { app.autonomyRepository.observe().first() })
        } finally {
            SQLiteDatabase.openDatabase(rule.activity.getDatabasePath("repland.db").absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id)) }
        }
    }
}
