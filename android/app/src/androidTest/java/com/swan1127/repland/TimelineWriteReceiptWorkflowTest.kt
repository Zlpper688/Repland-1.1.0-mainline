package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.components.taskDateLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Actual Activity -> guarded VM -> Room, isolated QA package, retained data. */
class TimelineWriteReceiptWorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }
    private val app get() = (rule.activity.application as ReplandApplication).appContainer
    private fun waitTag(tag: String) = rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun visible(tag: String): SemanticsNodeInteraction {
        waitTag(tag); val node=rule.onNodeWithTag(tag); runCatching { node.performScrollTo() }; return node.assertIsDisplayed()
    }
    private fun open(day: LocalDate) {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        visible("navigation-today").performClick()
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-month"))
        visible("schedule-scope-month").performClick()
        rule.clickScheduleDate(day)
        // 月格选完日子视口还在月;时间轴卡片只在日视图里,先翻回日视图再找它们。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-day"))
        visible("schedule-scope-day").performClick()
        rule.waitForIdle()
    }
    private fun freeStart(day: LocalDate): Int = runBlocking {
        val entries=ScheduleTimeline.entries(day, app.timeRepository.observeWeeklyBlocks().first(),
            app.timeRepository.observeDateOverrides().first(), app.planRepository.observeCurrentPlan().first()?.segments.orEmpty(),
            app.taskRepository.observeTasks().first(), app.timeRepository.observeTimeConstraintSettings().first().semesterFirstWeekMonday)
        (0..21).map { it*60 }.first { start -> entries.none { start < it.endMinute && start+90 > it.startMinute } }
    }
    private fun db()=SQLiteDatabase.openDatabase(rule.activity.getDatabasePath("repland.db").absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(rule.activity.getExternalFilesDir(null),name).outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
    }
    @Test fun failed_exact_move_keeps_raw_times_and_retry_preserves_task_and_logs() {
        val day=LocalDate.now().plusDays(3); val start=freeStart(day); val id="qa-final-move-${UUID.randomUUID()}"
        runBlocking { app.taskRepository.save(TaskDraft(id=id,displayName="最终时间调整",totalDurationMinutes=30)); app.planRepository.placeTask(id,day,start,start+30,"focus") }
        val plan=runBlocking { app.planRepository.observeCurrentPlan().first() }!!
        val segment=plan.segments.single { it.taskId==id }; val entry="segment:${segment.id}"
        val task=runBlocking { app.taskRepository.observeTasks().first().single { it.id==id } }
        val logs=runBlocking { app.taskRepository.observeExecutionLogs(id).first() }
        db().use { it.execSQL("CREATE TRIGGER qa_final_plan_write BEFORE INSERT ON plans BEGIN SELECT RAISE(ABORT, 'qa failure'); END") }
        try {
            open(day); rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("timeline-$entry"))
            visible("timeline-$entry").performTouchInput { click(Offset(center.x,20f)) }
            visible("timeline-time-editor-$entry").performClick()
            val from=TimeBlockValidator.formatTime(start+30); val until=TimeBlockValidator.formatTime(start+60)
            // 原时长 30 分钟由起点锁定：只滚起点就能得到 from–until。
            rule.pickWheelTime("timeline-start-$entry", from)
            visible("timeline-time-save-$entry").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("操作未保存",substring=true).fetchSemanticsNodes().isNotEmpty() }
            capture("final-time-write-failure.png")
            rule.assertWheelTime("timeline-start-$entry", from)
            // 时间编辑器支持换天之后，预览行前面带上了日期：只断言还在这天、这两个时刻。
            rule.onNodeWithTag("timeline-range-preview-$entry",useUnmergedTree=true)
                .assertTextEquals("${taskDateLabel(day)} · $from–$until")
            assertEquals(plan,runBlocking { app.planRepository.observeCurrentPlan().first() })
            db().use { it.execSQL("DROP TRIGGER qa_final_plan_write") }
            visible("timeline-time-save-$entry").performClick()
            rule.waitUntil(15_000) { runBlocking { app.planRepository.observeCurrentPlan().first()?.id != plan.id } }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("timeline-start-$entry").fetchSemanticsNodes().isEmpty() }
            assertEquals(start+30,runBlocking { app.planRepository.observeCurrentPlan().first() }!!.segments.single { it.taskId==id }.startMinute)
            assertEquals(task,runBlocking { app.taskRepository.observeTasks().first().single { it.id==id } }); assertEquals(logs,runBlocking { app.taskRepository.observeExecutionLogs(id).first() })
        } finally {
            db().use { it.execSQL("DROP TRIGGER IF EXISTS qa_final_plan_write") }
            runBlocking { app.planRepository.observeCurrentPlan().first()?.segments?.singleOrNull { it.taskId==id }?.let { app.planRepository.removePlacement(it.id) } }
        }
    }
}
