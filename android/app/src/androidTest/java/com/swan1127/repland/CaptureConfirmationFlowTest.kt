package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.domain.model.TaskInputSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * P0-2 捕获后置流的 E2E：捕获 → 确认面板分组 → 两条去向都要落到正确的数据。
 * - 「排入今天」：scheduledForDate=当天、inputSources.scheduledDate=ACCEPTED_SUGGESTION，
 *   且当前计划出现当天的时段。
 * - 「只记入事项库」：scheduledForDate=null、inputSources.scheduledDate=USER_INPUT，
 *   计划里不出现时段。
 */
class CaptureConfirmationFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }

    private val container get() = (rule.activity.application as ReplandApplication).appContainer.also {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
    }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** 任务页 → 捕获表单 →（可选时长）→ 以「只记入待安排」语义保存。返回唯一可检索的描述文本。 */
    private fun captureAndWaitForPanel(uniqueText: String, withDuration: Boolean) {
        // 全新安装会先停在登录门：以「先离线使用」进入个人域，导航栏才可见。
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("navigation-today").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("gate-offline").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithTag("gate-offline").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("gate-offline").performClick()
        }
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-tasks").performClick()
        waitTag("add-task")
        rule.onNodeWithTag("add-task").performClick()
        waitTag("task-capture-input")
        // 冷启动首次输入偶发不落进草稿：写入后核对 EditableText，丢了就补一次。
        fun inputCaptureText(text: String) {
            rule.onNodeWithTag("task-capture-input").performTextInput(text)
            val current = rule.onNodeWithTag("task-capture-input")
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            if (!current.contains(text)) {
                rule.onNodeWithTag("task-capture-input").performTextInput(text)
            }
        }
        inputCaptureText(uniqueText)
        if (withDuration) {
            rule.onNodeWithTag("task-capture-duration").performClick()
            waitTag("task-capture-duration-30")
            rule.onNodeWithTag("task-capture-duration-30").performClick()
        }
        // 输入会唤起 IME；先把键盘收起再点保存，避免原生键盘窗口吃掉点击。
        if (NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")) {
            NativeSheetTestInput.shell("input keyevent 4")
            rule.waitUntil(10_000) { !NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true") }
        }
        rule.waitForIdle()
        // 冷启动首次点击偶发不生效：超时后补一次点击再等。
        fun panelShown() = rule.onAllNodesWithTag("capture-confirm-panel").fetchSemanticsNodes().isNotEmpty()
        rule.onNodeWithTag("task-capture-save-inbox").performClick()
        try {
            rule.waitUntil(12_000) { panelShown() }
        } catch (timeout: ComposeTimeoutException) {
            rule.onNodeWithTag("task-capture-save-inbox").performClick()
            rule.waitUntil(10_000) { panelShown() }
        }
        waitTag("capture-confirm-panel")
    }

    private fun deleteTaskRow(id: String) {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            rule.activity.getDatabasePath("repland.db").absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id))
        }
    }

    private fun findTask(text: String) = runBlocking {
        container.taskRepository.observeTasks().first().firstOrNull { it.description == text || it.displayName == text }
    }

    @Test fun capture_confirm_schedule_today_sets_accepted_suggestion_and_places_segment() {
        val text = "QAcapA-${UUID.randomUUID().toString().take(8)}"
        val today = LocalDate.now()
        try {
            captureAndWaitForPanel(text, withDuration = true)
            rule.onNodeWithTag("capture-confirm-schedule-today-group").assertIsDisplayed()
            rule.onNodeWithTag("capture-confirm-undecided-group").assertIsDisplayed()
            rule.onNodeWithTag("capture-confirm-schedule-today").assertIsEnabled().performClick()
            // 面板关闭 = 排期与标记都已完成；直接以数据库为准断言。
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("capture-confirm-panel").fetchSemanticsNodes().isEmpty() }
            rule.waitUntil(10_000) {
                val task = findTask(text)
                task != null && task.scheduledForDate == today &&
                    task.inputSources.scheduledDate == TaskInputSource.ACCEPTED_SUGGESTION
            }
            val task = findTask(text)!!
            assertEquals(today, task.scheduledForDate)
            assertEquals(TaskInputSource.ACCEPTED_SUGGESTION, task.inputSources.scheduledDate)
            val segment = runBlocking {
                container.planRepository.observeCurrentPlan().first()?.segments?.firstOrNull { it.taskId == task.id }
            }
            assertNotNull("排入今天后当前计划应出现该任务的时段", segment)
            assertEquals(today, segment!!.date)
        } finally {
            findTask(text)?.let { task ->
                runBlocking {
                    container.planRepository.observeCurrentPlan().first()?.segments
                        ?.firstOrNull { it.taskId == task.id }?.let { segment -> container.planRepository.removePlacement(segment.id) }
                }
            }
            findTask(text)?.let { deleteTaskRow(it.id) }
        }
    }

    @Test fun capture_confirm_library_only_keeps_user_input_and_unscheduled() {
        val text = "QAcapB-${UUID.randomUUID().toString().take(8)}"
        try {
            captureAndWaitForPanel(text, withDuration = false)
            rule.onNodeWithTag("capture-confirm-undecided-group").assertIsDisplayed()
            rule.onNodeWithTag("capture-confirm-library-only").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("capture-confirm-panel").fetchSemanticsNodes().isEmpty() }
            rule.waitUntil(10_000) { findTask(text) != null }
            val task = findTask(text)!!
            assertNull("只记入事项库不应有排期日", task.scheduledForDate)
            assertEquals(TaskInputSource.USER_INPUT, task.inputSources.scheduledDate)
            val segment = runBlocking {
                container.planRepository.observeCurrentPlan().first()?.segments?.firstOrNull { it.taskId == task.id }
            }
            assertNull("只记入事项库不应出现时段", segment)
        } finally {
            findTask(text)?.let { deleteTaskRow(it.id) }
        }
    }
}
