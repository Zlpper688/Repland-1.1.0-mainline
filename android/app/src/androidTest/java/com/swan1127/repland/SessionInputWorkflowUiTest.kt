package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swan1127.repland.data.room.RoomExecutionSessionRepository
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.tasks.TaskViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.io.File

class SessionInputWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }
    private val container get() = (rule.activity.application as ReplandApplication).appContainer.also { check(rule.activity.packageName == "com.swan1127.repland.qa") }
    private fun waitTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = rule.waitUntil(20_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun fill(tag: String, text: String) = rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    private fun fault(initial: Boolean = false): Pair<ReadFaultSessions, TaskViewModel> {
        val source = ReadFaultSessions(container.executionSessionRepository).apply { failed.value = initial }; lateinit var vm: TaskViewModel
        rule.runOnUiThread {
            vm = TaskViewModel(container.taskRepository, source)
            rule.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${TaskViewModel::class.java.canonicalName}", vm)
        }
        rule.activityRule.scenario.recreate(); return source to vm
    }
    private fun sql() = android.database.sqlite.SQLiteDatabase.openDatabase(
        rule.activity.getDatabasePath("repland.db").absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
    /** Repeated runs retain the QA database, and `start` refuses to replace a round that is still
     * open (data/room/RoomExecutionSessionRepository.kt:19-24). Retire an unrelated leftover through
     * the repository's own guarded ending, so this fixture never silently overwrites someone's round. */
    private suspend fun retireUnrelatedRound() {
        val sessions = container.executionSessionRepository
        val leftover = sessions.observeActive().first() ?: return
        runCatching { sessions.finish(leftover.id, ExecutionOutcome.CONTINUE, TaskFeedback()) }
            // Its task may already be gone; then only the retained active-round row is left to clear.
            .onFailure { sql().use { it.delete("planning_workspace", "key = ?", arrayOf(RoomExecutionSessionRepository.KEY)) } }
    }
    private fun seed(id: String) = runBlocking {
        retireUnrelatedRound()
        check(container.executionSessionRepository.observeActive().first() == null) { "QA fixture must not replace an unrelated round" }
        container.taskRepository.save(TaskDraft(id, "QA-session25-$id", "", TaskCategory.COURSE, TaskPriority.MEDIUM, 1, 60, null))
        val existing = container.planRepository.observeCurrentPlan().first()?.segments.orEmpty()
        val weekly = container.timeRepository.observeWeeklyBlocks().first()
        val overrides = container.timeRepository.observeDateOverrides().first()
        val semester = container.timeRepository.observeTimeConstraintSettings().first().semesterFirstWeekMonday
        val candidate = (1L..30L).asSequence().flatMap { day ->
            (0..1380 step 60).asSequence().map { start -> PlannedSegment(taskId = id, date = LocalDate.now().plusDays(day), startMinute = start, endMinute = start + 60) }
        }.first { runCatching { PlacementValidator.requireValid(it, existing, weekly, overrides, semester) }.isSuccess }
        container.planRepository.placeTask(id, candidate.date, candidate.startMinute, candidate.endMinute, candidate.trackId)
        val segment = container.planRepository.observeCurrentPlan().first()!!.segments.first { it.taskId == id }
        container.executionSessionRepository.start(segment.id)
        container.executionSessionRepository.pause(container.executionSessionRepository.observeActive().first()!!.id)
    }
    private fun cleanup(id: String) = runBlocking {
        container.executionSessionRepository.observeActive().first()?.takeIf { it.taskId == id }?.let {
            container.executionSessionRepository.finish(it.id, ExecutionOutcome.CONTINUE, TaskFeedback())
        }
        container.planRepository.observeCurrentPlan().first()?.segments?.filter { it.taskId == id }?.forEach {
            container.planRepository.removePlacement(it.id)
        }
    }
    @Test fun failed_round_read_keeps_the_round_resumable_through_recreation_and_retry() {
        readFailureWorkflow(false)
    }
    @Test fun rotating_open_round_keeps_native_viewport_and_the_same_round() {
        readFailureWorkflow(true)
    }
    private fun readFailureWorkflow(rotateWhileOpen: Boolean) {
        val id = UUID.randomUUID().toString()
        try {
            seed(id); val original = runBlocking { container.executionSessionRepository.observeActive().first() }!!
            val plan = runBlocking { container.planRepository.observeCurrentPlan().first() }
            val (source, vm) = fault(); waitTag("focus-pill")
            // 进行中的这一轮从常驻专注条重新进入盖楼页。
            rule.onNodeWithTag("focus-pill").performClick(); waitTag("execution-pause-resume")
            NativeSheetTestInput.awaitFocusedDialog(rule, "QA-session25-$id")
            NativeSheetTestInput.assertDialogWithinDisplay(rule, "qa-focus26-open")
            if (rotateWhileOpen) {
                rule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                waitTag("execution-pause-resume")
                NativeSheetTestInput.awaitFocusedDialog(rule, "QA-session25-$id")
                NativeSheetTestInput.assertDialogWithinDisplay(rule, "qa-focus26-rotated")
            }
            source.failed.value = true
            rule.waitUntil(10_000) { vm.sessionReadState.value.error != null }
            // 这一轮读不回来时，会改状态的操作都得停；「返回」只是离开这一屏，仍然可用。
            rule.onNodeWithTag("execution-pause-resume").assertIsNotEnabled()
            rule.onNodeWithTag("execution-finish").assertIsNotEnabled()
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
            rule.activityRule.scenario.recreate(); waitTag("execution-pause-resume")
            rule.onNodeWithTag("execution-pause-resume").assertIsNotEnabled()
            NativeSheetTestInput.assertDialogWithinDisplay(rule, "qa-focus26-recreated")
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try { File(rule.activity.getExternalFilesDir(null), "qa-session25-read-failure.png").outputStream().use { check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { screenshot.recycle() }
            source.failed.value = false
            rule.onNode(hasTestTag("retry-data-read") and hasAnyAncestor(hasTestTag("execution-session")))
                .assertIsDisplayed()
            NativeSheetTestInput.assertControlWithinWindow(rule, "retry-data-read", "execution-session")
            rule.onNode(hasTestTag("retry-data-read") and hasAnyAncestor(hasTestTag("execution-session"))).performClick()
            rule.waitUntil(10_000) { vm.sessionReadState.value.isTrusted }
            rule.onNodeWithTag("execution-pause-resume").assertIsEnabled()
            NativeSheetTestInput.assertControlWithinWindow(rule, "execution-pause-resume")
            rule.onNodeWithTag("execution-return").assertIsDisplayed()
            NativeSheetTestInput.assertControlWithinWindow(rule, "execution-return")
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
            assertEquals(plan, runBlocking { container.planRepository.observeCurrentPlan().first() })
            rule.onNodeWithTag("execution-return").performClick(); gone("execution-session")
            // 返回只离开这一屏：本轮还在、任务还在进行中、安排一格没动。
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
            assertEquals(TaskStatus.IN_PROGRESS, runBlocking { container.taskRepository.observeTasks().first().single { it.id == id }.status })
            assertEquals(plan, runBlocking { container.planRepository.observeCurrentPlan().first() })
        } finally {
            cleanup(id)
            if (rotateWhileOpen) rule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun native_back_closes_the_focus_sheet_without_finishing_the_round() {
        val id = UUID.randomUUID().toString()
        try {
            seed(id)
            val original = runBlocking { container.executionSessionRepository.observeActive().first() }
            fault(); waitTag("focus-pill")
            rule.onNodeWithTag("focus-pill").performClick(); waitTag("execution-pause-resume")
            NativeSheetTestInput.awaitFocusedDialog(rule, "QA-session25-$id")
            NativeSheetTestInput.back(rule, "QA-session25-$id")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("execution-session").fetchSemanticsNodes().isEmpty() }
            // 返回只是合上盖楼页：本轮没结束，专注条还在，再点还能回来。
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
            waitTag("focus-pill")
            rule.onNodeWithTag("focus-pill").performClick(); waitTag("execution-pause-resume")
            NativeSheetTestInput.assertControlWithinWindow(rule, "execution-return")
            rule.onNodeWithTag("execution-return").performClick(); gone("execution-session")
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
        } finally {
            cleanup(id)
        }
    }
    @Test fun initially_failed_round_read_has_retry_and_does_not_offer_a_false_resume() {
        val id = UUID.randomUUID().toString()
        try {
            seed(id); val original = runBlocking { container.executionSessionRepository.observeActive().first() }
            val (source, vm) = fault(true)
            // An open round is re-entered through the floating focus pill (ui/ReplandApp.kt:1334,
            // tagged at 2106); the session dialog's leave button is execution-return, labelled 返回
            // (ui/schedule/ExecutionSessionDialog.kt:187-188).
            rule.waitUntil(10_000) { vm.sessionReadState.value.error != null }
            rule.onNodeWithTag("focus-pill").assertDoesNotExist()
            waitTag("retry-data-read"); source.failed.value = false
            rule.onNodeWithTag("retry-data-read").performClick(); waitTag("focus-pill")
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
            assertEquals(0, source.mutations.get())
            rule.onNodeWithTag("focus-pill").assertIsDisplayed().performClick(); waitTag("execution-pause-resume")
            rule.onNodeWithTag("execution-return").performClick()
            assertEquals(original, runBlocking { container.executionSessionRepository.observeActive().first() })
        } finally { cleanup(id) }
    }
    @Test fun task_editor_keeps_illegal_raw_numbers_and_unknown_duration_without_changing_identity() {
        val id = UUID.randomUUID().toString(); val name = "QA-numbers25-$id"
        runBlocking { container.taskRepository.save(TaskDraft(id, name, "", TaskCategory.COURSE, TaskPriority.MEDIUM, 1, 60, null)) }
        val original = runBlocking { container.taskRepository.observeTasks().first().single { it.id == id } }
        val (_, vm) = fault()
        rule.waitUntil(10_000) { vm.uiState.value.isTrusted && vm.uiState.value.tasks.any { it.id == id } }
        waitTag("navigation-tasks"); rule.onNodeWithTag("navigation-tasks").performClick(); waitTag("task-groups-scroll")
        rule.onNodeWithTag("task-filter-INBOX").performClick(); waitTag("task-list-scroll")
        rule.onNodeWithTag("task-list-scroll").performScrollToNode(hasTestTag("task-card-$name"))
        rule.onNodeWithTag("task-card-$name").performClick(); waitTag("task-detail-scroll")
        // 编辑任务 moved behind the detail overflow entry (ui/ReplandApp.kt:2742, 2750).
        rule.onNodeWithTag("task-detail-more").performClick(); waitTag("menu-edit-task")
        rule.onNodeWithTag("menu-edit-task").performScrollTo().performClick()
        waitTag("task-editor-duration")
        listOf("-30", "3.5", "999999999999999999999").forEach {
            fill("task-editor-duration", it); rule.onNodeWithTag("task-editor-save").performClick()
            rule.onNodeWithTag("task-editor-duration").performScrollTo().assertTextContains(it)
            assertEquals(original, runBlocking { container.taskRepository.observeTasks().first().single { it.id == id } })
        }
        fill("task-editor-duration", ""); fill("task-editor-days", "-2")
        rule.activityRule.scenario.recreate(); waitTag("task-editor-days")
        rule.onNodeWithTag("task-editor-days").performScrollTo().assertTextContains("-2")
        rule.onNodeWithTag("task-editor-save").performClick()
        assertEquals(original, runBlocking { container.taskRepository.observeTasks().first().single { it.id == id } })
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(rule.activity.getExternalFilesDir(null), "qa-numbers25-invalid.png").outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        fill("task-editor-days", "2"); rule.onNodeWithTag("task-editor-save").performClick(); gone("task-editor-days")
        val saved = runBlocking { container.taskRepository.observeTasks().first().single { it.id == id } }
        assertEquals(2, saved.estimatedDays); assertNull(saved.totalDurationMinutes); assertEquals(original.userPriority, saved.userPriority)
    }
}
