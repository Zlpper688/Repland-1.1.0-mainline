package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swan1127.repland.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class TaskFeedbackWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }
    private val repo get() = (rule.activity.application as ReplandApplication).appContainer.taskRepository
    private fun waitTag(tag: String) {
        try { rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { screenshot("qa-feedback21-timeout-$tag.png"); throw failure }
    }
    private fun gone(tag: String) = rule.waitUntil(20_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun fill(tag: String, value: String) = rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun seed(): Pair<String, String> {
        val id = UUID.randomUUID().toString(); val name = "QA-feedback21-$id"
        runBlocking { repo.save(TaskDraft(id = id, displayName = name, description = "反馈测试", category = TaskCategory.COURSE,
            userPriority = TaskPriority.MEDIUM, estimatedDays = 1, totalDurationMinutes = 30, dueDate = null, scheduledForDate = LocalDate.now())) }
        return id to name
    }
    // An ended task is the only row that still offers 记录执行反馈 (ui/ReplandApp.kt:3168-3176),
    // and ended rows live in the 已完成 filter (ui/ReplandApp.kt:2377), not in TODAY.
    private fun open(name: String, filter: String = "TODAY") {
        waitTag("navigation-tasks"); rule.onNodeWithTag("navigation-tasks").performClick()
        // Retained QA tasks can exceed the overview's three-card group preview.
        // Use the real filter for this explicitly today-scheduled fixture.
        waitTag("task-filter-$filter"); rule.onNodeWithTag("task-filter-$filter").performClick(); waitTag("task-list-scroll")
        rule.onNodeWithTag("task-list-scroll").performScrollToNode(hasTestTag("task-card-$name"))
        rule.onNodeWithTag("task-card-$name").performClick(); waitTag("task-detail-scroll")
    }
    private fun action(tag: String) {
        rule.onNodeWithTag("task-detail-scroll").performScrollToNode(hasTestTag(tag))
        rule.waitUntil(10_000) { !rule.onNodeWithTag(tag).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        // Lazy discovery finds an item even if this descendant is below the viewport.
        // Scroll the actual control into view and still use a real pointer click.
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val controlBounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("task-detail-scroll").fetchSemanticsNode().boundsInRoot
        assertTrue("control must be fully inside the scroll viewport", controlBounds.top >= viewport.top && controlBounds.bottom <= viewport.bottom)
        if (tag.startsWith("execution-log-correct-")) screenshot("qa-feedback21-before-correct.png")
        rule.onNodeWithTag(tag).performClick()
    }
    // 顺延 / 替换 / 取消 no longer sit in the detail body: they are DropdownMenu items behind the
    // single overflow entry (ui/ReplandApp.kt:2742) and carry a menu- prefixed tag
    // (ui/ReplandApp.kt:2757, 2764, 2769). Open the overflow first, then use a real pointer click.
    private fun menuAction(tag: String) {
        rule.onNodeWithTag("task-detail-more").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { !rule.onNodeWithTag(tag).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        // 菜单项在独立弹窗里，坐标不跟任务页滚动视口共用一套；能滚进菜单、点得到即可。
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        // The overflow dropdown dismisses on selection; never leave a stale menu over the next step.
        gone(tag)
    }
    private fun screenshot(name: String) {
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(rule.activity.getExternalFilesDir(null), name).outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun sql(): SQLiteDatabase {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        return SQLiteDatabase.openDatabase(rule.activity.getDatabasePath("repland.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }
    private fun cleanup(id: String) { sql().use { it.execSQL("DELETE FROM execution_logs WHERE taskId = ?", arrayOf(id)); it.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id)) } }

    @Test fun feedback_input_recreation_and_invalid_numbers_do_not_write_then_correction_keeps_history() {
        val (id, name) = seed()
        // 详情页只给已结束的任务留「记录执行反馈」；部分完成现在只发生在专注会话里。
        runBlocking { repo.confirmStatus(id, TaskStatus.COMPLETED) }
        // 状态变更本身会留一行日志；这格只关心反馈写入，先把夹具的这行清掉。
        sql().use { it.execSQL("DELETE FROM execution_logs WHERE taskId = ?", arrayOf(id)) }
        try {
            open(name, "COMPLETED"); action("record-feedback"); waitTag("feedback-content")
            fill("feedback-content", "完成第一部分"); fill("feedback-progress", "40"); fill("feedback-actual-duration", "99999999999999999999")
            rule.activityRule.scenario.recreate(); waitTag("feedback-content")
            rule.onNodeWithTag("feedback-content").assertTextContains("完成第一部分")
            rule.onNodeWithTag("feedback-actual-duration").performScrollTo().assertTextContains("99999999999999999999")
            for (number in listOf("99999999999999999999", "-1", "1.5")) {
                fill("feedback-actual-duration", number); rule.onNodeWithTag("feedback-confirm").performClick(); rule.waitForIdle()
                assertTrue(runBlocking { repo.observeExecutionLogs(id).first().isEmpty() }); rule.onNodeWithTag("feedback-content").assertExists()
            }
            fill("feedback-actual-duration", "20"); fill("feedback-progress", "40")
            rule.onNodeWithTag("feedback-confirm").performClick(); gone("feedback-content")
            rule.onNodeWithText("已保存反馈；任务状态未改变。").assertIsDisplayed(); screenshot("qa-feedback21-receipt.png")
            val original = runBlocking { repo.observeExecutionLogs(id).first().single() }
            assertEquals(ExecutionLogEventType.FEEDBACK, original.eventType)
            assertEquals(TaskStatus.COMPLETED, runBlocking { repo.observeTasks().first().single { it.id == id }.status })
            action("execution-log-correct-${original.id}"); waitTag("feedback-content")
            fill("feedback-content", "纠正第一部分"); fill("feedback-progress", "35")
            rule.activityRule.scenario.recreate(); waitTag("feedback-content")
            rule.onNodeWithTag("feedback-content").assertTextContains("纠正第一部分")
            rule.onNodeWithTag("feedback-confirm").performClick(); gone("feedback-content")
            val logs = runBlocking { repo.observeExecutionLogs(id).first() }
            assertEquals(2, logs.size); assertEquals(original, logs.single { it.id == original.id })
            assertEquals(original.id, logs.single { it.eventType == ExecutionLogEventType.CORRECTION }.correctedLogId)
            rule.onNodeWithText("已追加纠正记录；原始历史仍保留。").assertIsDisplayed()
        } finally { cleanup(id) }
    }

    @Test fun write_failure_keeps_feedback_input_through_recreation_retry_has_one_real_receipt() {
        val (id, name) = seed(); val db = sql()
        try {
            // 详情页只给已结束的任务留「记录执行反馈」（ui/ReplandApp.kt:3168-3176）；
            // 未开始的活跃任务只显示「完成」。夹具先结掉任务，再单独验反馈写入。
            runBlocking { repo.confirmStatus(id, TaskStatus.COMPLETED) }
            db.execSQL("DELETE FROM execution_logs WHERE taskId = ?", arrayOf(id))
            val original = runBlocking { repo.observeTasks().first().single { it.id == id } }
            db.execSQL("CREATE TRIGGER qa_feedback_ui_failure BEFORE INSERT ON execution_logs BEGIN SELECT RAISE(ABORT, 'qa ui failure'); END")
            open(name, "COMPLETED"); action("record-feedback"); waitTag("feedback-content")
            fill("feedback-content", "保留反馈文字"); fill("feedback-actual-duration", "25"); fill("feedback-progress", "30")
            rule.onNodeWithTag("feedback-confirm").performClick(); waitTag("feedback-save-error")
            assertEquals(original, runBlocking { repo.observeTasks().first().single { it.id == id } })
            assertTrue(runBlocking { repo.observeExecutionLogs(id).first().isEmpty() })
            rule.onNodeWithTag("feedback-save-error").performScrollTo(); screenshot("qa-feedback21-failure.png")
            rule.activityRule.scenario.recreate(); waitTag("feedback-content")
            rule.onNodeWithTag("feedback-content").assertTextContains("保留反馈文字")
            rule.onNodeWithTag("feedback-save-error").assertExists()
            fill("feedback-progress", "30"); db.execSQL("DROP TRIGGER qa_feedback_ui_failure")
            rule.onNodeWithTag("feedback-confirm").performClick(); gone("feedback-content")
            val log = runBlocking { repo.observeExecutionLogs(id).first().single() }
            assertEquals(TaskStatus.COMPLETED, log.confirmedStatus); assertEquals(25, log.feedback.actualDurationMinutes)
            assertEquals("保留反馈文字", log.feedback.completedContent)
            assertEquals(TaskStatus.COMPLETED, runBlocking { repo.observeTasks().first().single { it.id == id }.status })
            rule.onNodeWithText("已保存反馈；任务状态未改变。").assertIsDisplayed()
        } finally { db.execSQL("DROP TRIGGER IF EXISTS qa_feedback_ui_failure"); db.close(); cleanup(id) }
    }

    @Test fun postpone_is_one_tap_and_can_be_taken_back_then_cancel_target_survives_recreation() {
        val (id, name) = seed()
        try {
            open(name); menuAction("menu-postpone-task")
            rule.onNodeWithText("已顺延；没有替你挑新的时段。").assertIsDisplayed()
            assertEquals(TaskStatus.POSTPONED, runBlocking { repo.observeTasks().first().single { it.id == id }.status })
            assertEquals(1, runBlocking { repo.observeTasks().first().single { it.id == id }.postponeCount })
            // 点错一次「顺延」不该变成单向门。
            rule.onNodeWithText("撤销").performClick()
            // 撤销回执照标签原样念一遍：标签自带书名号，回执再套一层就成了「「…」」。
            rule.onNodeWithText("已撤销「$name」还是未开始。").assertIsDisplayed()
            assertEquals(TaskStatus.NOT_STARTED, runBlocking { repo.observeTasks().first().single { it.id == id }.status })
            assertEquals(0, runBlocking { repo.observeTasks().first().single { it.id == id }.postponeCount })
            menuAction("menu-cancel-task"); waitTag("cancel-task-confirm"); rule.activityRule.scenario.recreate(); waitTag("cancel-task-confirm")
            rule.onNodeWithTag("cancel-task-confirm").performClick(); gone("cancel-task-confirm")
            assertEquals(TaskStatus.CANCELLED, runBlocking { repo.observeTasks().first().single { it.id == id }.status })
            // 顺延、撤销回未开始、取消各留一条证据；撤销真的恢复了状态，历史里就该看得到。
            val logs = runBlocking { repo.observeExecutionLogs(id).first() }
            assertEquals(
                listOf(TaskStatus.POSTPONED, TaskStatus.NOT_STARTED, TaskStatus.CANCELLED),
                logs.map { it.confirmedStatus },
            )
            rule.onNodeWithText("已取消任务，执行历史仍保留。").assertIsDisplayed()
        } finally { cleanup(id) }
    }

    @Test fun removed_feedback_target_is_not_recreated_or_redirected_after_recreation() {
        val (id, name) = seed()
        try {
            // 记录执行反馈 only renders after a task leaves the active set (ui/ReplandApp.kt:3160-3176),
            // and ended rows are listed by the 已完成 filter (ui/ReplandApp.kt:2377). The guarded
            // behaviour is the vanished feedback target, so reach the real sheet through its entry.
            runBlocking { repo.confirmStatus(id, TaskStatus.CANCELLED) }
            open(name, "COMPLETED"); action("record-feedback"); waitTag("feedback-content"); fill("feedback-content", "不应写到其他任务")
            cleanup(id)
            // The external QA SQLite connection does not notify Room's observer.
            // A normal write to our own sentinel forces a real task-table refresh.
            val (sentinelId, _) = seed(); cleanup(sentinelId)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("原任务或记录已不存在").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("原任务或记录已不存在").assertIsDisplayed()
            assertFalse(runBlocking { repo.observeTasks().first().any { it.id == id } })
            assertTrue(runBlocking { repo.observeExecutionLogs(id).first().isEmpty() })
            rule.onNodeWithText("关闭").performClick()
        } finally { cleanup(id) }
    }

    @Test fun postponement_plan_preview_dismisses_to_schedule_without_opening_assistant_or_pdf_settings() {
        val (id, name) = seed()
        try {
            runBlocking { repeat(3) { repo.confirmStatus(id, TaskStatus.POSTPONED, TaskFeedback(postponeReason = "明确延期样例")) } }
            open(name); action("postpone-review-plan"); waitTag("plan-draft-preview")
            rule.onNodeWithText("放弃本次调整").performClick(); gone("plan-draft-preview")
            waitTag("composer-bar"); rule.onNodeWithTag("composer-bar").assertExists()
            rule.onNodeWithTag("assistant-sheet").assertDoesNotExist()
            rule.onNodeWithText("导入固定课程").assertDoesNotExist()
            assertEquals(3, runBlocking { repo.observeTasks().first().single { it.id == id }.postponeCount })
        } finally { cleanup(id) }
    }

    @Test fun replacement_restores_input_and_commits_one_new_identity_without_erasing_original() {
        val (id, name) = seed(); var replacementId: String? = null
        try {
            open(name); menuAction("menu-replace-task"); waitTag("task-editor-name")
            fill("task-editor-name", "$name-replacement"); fill("task-editor-description", "替换后的范围")
            rule.activityRule.scenario.recreate(); waitTag("task-editor-name")
            rule.onNodeWithTag("task-editor-name").assertTextContains("$name-replacement")
            rule.onNodeWithTag("task-editor-save").performClick(); gone("task-editor-name")
            val logs = runBlocking { repo.observeExecutionLogs(id).first() }; assertEquals(1, logs.size)
            replacementId = logs.single().replacementTaskId; assertNotNull(replacementId)
            val tasks = runBlocking { repo.observeTasks().first() }
            assertEquals(TaskStatus.REPLACED, tasks.single { it.id == id }.status)
            assertEquals("$name-replacement", tasks.single { it.id == replacementId }.displayName)
            rule.onNodeWithText("已保存替换事项；原任务和历史仍保留。").assertIsDisplayed()
        } finally { cleanup(id); replacementId?.let(::cleanup) }
    }
}
