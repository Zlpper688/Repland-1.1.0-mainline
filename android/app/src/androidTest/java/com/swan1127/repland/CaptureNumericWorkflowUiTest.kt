package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsActions
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.tasks.TaskCaptureViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CaptureNumericWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun visible(tag: String): SemanticsNodeInteraction {
        val hasScrollParent = hasTestTag(tag) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        if (rule.onAllNodes(hasScrollParent).fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag(tag).performScrollTo()
        return rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun invalid_raw_survives_recreation_and_exit_discards_draft() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        val container = (rule.activity.application as ReplandApplication).appContainer
        lateinit var vm: TaskCaptureViewModel
        rule.runOnUiThread { vm = ViewModelProvider(rule.activity)[TaskCaptureViewModel::class.java] }
        rule.waitUntil(10_000) { !vm.uiState.value.loading }
        val originalDraft = vm.uiState.value.draft
        val tasks = runBlocking { container.taskRepository.observeTasks().first() }
        val plan = runBlocking { container.planRepository.observeCurrentPlan().first() }
        val order = runBlocking { container.planRepository.observeTaskOrder().first() }
        val id = UUID.randomUUID().toString()
        var savedId: String? = null
        val own = TaskCaptureDraft(id = id, text = "QA-capture28-$id",
            isCustomDuration = true, customDurationText = "45")
        try {
            rule.runOnUiThread { vm.update(own) }
            waitTag("navigation-tasks"); rule.onNodeWithTag("navigation-tasks").performClick()
            waitTag("add-task"); rule.onNodeWithTag("add-task").performClick()
            waitTag("task-capture-duration-custom-input")
            rule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().performTextReplacement("-30")
            rule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains("-30")
            visible("task-capture-save-inbox").assertIsNotEnabled().performClick()
            // 编辑期间自动暂存仍在（防进程被杀），但退出不再保留。
            runBlocking { withTimeout(10_000) { container.taskCaptureRepository.observeDraft().first {
                it?.id == id && it.customDurationText == "-30"
            } } }
            rule.activityRule.scenario.recreate(); waitTag("task-capture-duration-custom-input")
            rule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().assertTextContains("-30")
            rule.onNodeWithTag("task-capture-save-inbox").assertIsNotEnabled()
            visible("task-capture-duration-error")
            rule.waitForIdle()
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try { java.io.File(rule.activity.getExternalFilesDir(null), "qa-capture28-invalid.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { screenshot.recycle() }
            assertEquals(tasks, runBlocking { container.taskRepository.observeTasks().first() })
            assertEquals(plan, runBlocking { container.planRepository.observeCurrentPlan().first() })
            assertEquals(order, runBlocking { container.planRepository.observeTaskOrder().first() })
            // 退出先问一次：继续编辑则内容原样保留。
            visible("task-capture-close").performClick()
            waitTag("task-capture-continue-edit")
            rule.onNodeWithTag("task-capture-continue-edit").performClick()
            rule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().assertTextContains("-30")
            // 确认退出 = 整体放弃，库里草稿一并删除。
            visible("task-capture-close").performClick()
            waitTag("task-capture-confirm-exit")
            rule.onNodeWithTag("task-capture-confirm-exit").performClick()
            runBlocking { withTimeout(10_000) { container.taskCaptureRepository.observeDraft().first {
                it == null || it.id != id
            } } }
            // 重新打开是全新空白表单，而不是上次的残留。
            rule.onNodeWithTag("add-task").performClick(); waitTag("task-capture-input")
            val freshId = vm.uiState.value.draft.id
            assertNotEquals(id, freshId)
            rule.onNodeWithTag("task-capture-input").performTextInput("QA-capture28-save-$id")
            if (NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")) {
                NativeSheetTestInput.shell("input keyevent 4")
                rule.waitUntil(10_000) { !NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true") }
            }
            rule.waitForIdle()
            rule.onNodeWithTag("task-capture-duration").performScrollTo().performClick()
            waitTag("task-capture-duration-unknown")
            rule.onNodeWithTag("task-capture-duration-unknown").performClick()
            visible("task-capture-save-inbox").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { vm.uiState.value.receipt != null && !vm.uiState.value.saving }
            savedId = freshId
            val after = runBlocking { container.taskRepository.observeTasks().first() }
            assertEquals(tasks.size + 1, after.size)
            val saved = after.single { it.id == freshId }
            assertNull(saved.totalDurationMinutes)
            assertNull("未选日期就保存不应被悄悄排到今天", saved.scheduledForDate)
            assertEquals(plan, runBlocking { container.planRepository.observeCurrentPlan().first() })
            assertEquals(order, runBlocking { container.planRepository.observeTaskOrder().first() })
        } finally {
            rule.runOnUiThread { if (vm.uiState.value.draft.id == id || vm.uiState.value.draft.text.isEmpty()) {
                vm.dismissReceipt(); vm.update(originalDraft); vm.retain()
            } }
            android.database.sqlite.SQLiteDatabase.openDatabase(rule.activity.getDatabasePath("repland.db").absolutePath,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id))
                savedId?.let { db.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(it)) }
            }
        }
    }
}
