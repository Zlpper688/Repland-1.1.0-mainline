package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Activity -> today's adapter -> ViewModel -> Room; QA package only. */
class PlacementNumericWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val frame = java.util.concurrent.CountDownLatch(1)
        rule.runOnUiThread { rule.activity.window.decorView.postOnAnimation {
            rule.activity.window.decorView.postOnAnimation { frame.countDown() }
        } }
        check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    private fun waitTag(tag: String) {
        try { rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        } } catch (failure: Throwable) {
            screenshot("qa-placement29-navigation-failure.png")
            throw failure
        }
    }
    private fun visible(tag: String): SemanticsNodeInteraction {
        val node = rule.onNodeWithTag(tag)
        // Native IME resizing is not part of Compose's idle clock. Re-read and
        // scroll within the settled window, retaining the visibility requirement.
        rule.waitUntil(5_000) { runCatching {
            if (rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)))
                    .fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
            node.assertIsDisplayed()
            val observed = node.fetchSemanticsNode()
            val size = observed.layoutInfo.coordinates.size
            check(observed.boundsInRoot.width >= size.width - 1 && observed.boundsInRoot.height >= size.height - 1)
        }.isSuccess }
        return node.assertIsDisplayed()
    }

    @Test fun event_chunk_shown_in_sheet_is_exactly_committed_without_rewriting_task_duration() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        val app = (rule.activity.application as ReplandApplication).appContainer
        // Retained QA runs must not fill every tomorrow slot. Remove only this
        // fixture's ordinary placements through the real guarded operation;
        // retain tasks, execution records and all plan versions.
        val activeTask = runBlocking { app.executionSessionRepository.observeActive().first()?.taskId }
        runBlocking {
            while (true) {
                val owned = app.planRepository.observeCurrentPlan().first()?.segments.orEmpty().firstOrNull {
                    it.taskId.startsWith("qa-placement29-") && it.taskId != activeTask
                } ?: break
                app.planRepository.removePlacement(owned.id)
            }
        }
        val id = "qa-placement29-${UUID.randomUUID()}"
        val name = "本次安排验收29-${id.takeLast(6)}"
        val date = LocalDate.now().plusDays(1)
        runBlocking { app.taskRepository.save(TaskDraft(id, name, "", TaskCategory.COURSE,
            TaskPriority.MEDIUM, 1, 300, null)) }
        val task = runBlocking { app.taskRepository.observeTasks().first().single { it.id == id } }
        waitTag("navigation-today"); visible("navigation-today").performClick()
        waitTag("today-page-scroll")
        // 日/周/月是同一个分段控件的三个格子：点月才算进入月视图，旧的独立 month-schedule 入口已不存在。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-month"))
        visible("schedule-scope-month").performClick()
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("month-date-$date"))
        visible("month-date-$date").performClick()
        // 月格里选完日子视口仍是月：安排已有事项与时间轴卡片只活在日视图，先翻回去。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-day"))
        visible("schedule-scope-day").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-arrange-task"))
        visible("schedule-arrange-task").performClick()
        waitTag("event-object-$id")
        // 建议时间直接写在行上：点一下即排入，不再为每件事项走一遍轮盘弹窗。
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("点一下排入", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        visible("event-object-$id").performClick()
        val saved = runBlocking { withTimeout(10_000) { app.planRepository.observeCurrentPlan().first {
            it?.segments?.any { segment -> segment.taskId == id } == true
        } } }!!
        val segment = saved.segments.single { it.taskId == id }
        screenshot("qa-placement29-placed.png")
        // 300 分钟的任务只承诺一个 240 分钟的块，且任务本身的总时长不被改写。
        assertEquals(date, segment.date)
        assertEquals(240, segment.endMinute - segment.startMinute)
        assertEquals(task, runBlocking { app.taskRepository.observeTasks().first().single { it.id == id } })
        visible("navigation-tasks").performClick()
        waitTag("task-groups-scroll")
        rule.onNodeWithTag("task-groups-scroll").performScrollToNode(hasTestTag("task-group-更晚"))
        val expand = hasText("查看全部") and hasAnyAncestor(hasTestTag("task-group-更晚"))
        if (rule.onAllNodes(expand).fetchSemanticsNodes().isNotEmpty()) rule.onNode(expand).assertIsDisplayed().performClick()
        rule.onNodeWithTag("task-groups-scroll").performScrollToNode(hasTestTag("task-card-${task.displayName}"))
        rule.onNodeWithTag("task-card-${task.displayName}").assertExists()
    }
}
