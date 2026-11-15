package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.schedule.scheduleDateHeadline
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduleNavigationStateUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun use_manual_planning_mode_for_legacy_workflow() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun selectDate(date: LocalDate) {
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-today").performClick()
        // 月视图现在是分段控件的一格（schedule-scope-month），旧的独立 month-schedule 入口已删除。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-month"))
        rule.onNodeWithTag("schedule-scope-month").performClick()
        rule.clickScheduleDate(date)
    }

    /** 日/周/月挤在同一个分段模块里，重建后它可能被滚出屏幕，先把它带回来再点。 */
    private fun selectScope(scope: String) {
        val tag = "schedule-scope-$scope"
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun assertScopeSelected(scope: String) =
        rule.onNodeWithTag("schedule-scope-$scope").assertIsSelected()

    @Test fun selected_date_header_and_day_view_survive_activity_recreation() {
        val day = LocalDate.now().plusDays(2)
        selectDate(day)
        val expected = scheduleDateHeadline(day, LocalDate.now())
        rule.onNodeWithTag("schedule-selected-date").assertTextEquals(expected)
        // 月格点完日子视口仍停在月视图：时间轴卡片只活在日视图，这里要的是「日」这一格。
        selectScope("day")
        assertScopeSelected("day")
        rule.activityRule.scenario.recreate()
        waitTag("schedule-selected-date")
        // 转屏不是新会话：用户正在看的这一天和他挑的那一格都得原样回来。
        rule.onNodeWithTag("schedule-selected-date").assertTextEquals(expected)
        assertScopeSelected("day")
    }

    @Test fun returning_from_block_detail_retains_selected_date_mode_and_scroll_even_after_recreation() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        val app = (rule.activity.application as ReplandApplication).appContainer
        val id = "qa-scroll-${UUID.randomUUID()}"
        val day = LocalDate.now().plusDays(2)
        val segment = runBlocking {
            app.taskRepository.save(TaskDraft(id = id, displayName = "QA 日程返回位置", totalDurationMinutes = 30))
            val plan = app.planRepository.observeCurrentPlan().first()
            val weekly = app.timeRepository.observeWeeklyBlocks().first()
            val overrides = app.timeRepository.observeDateOverrides().first()
            val semester = app.timeRepository.observeTimeConstraintSettings().first().semesterFirstWeekMonday
            // 取当天最后一个可排的槽：排在日头上一屏不用滚，这条断言就退化成空断言了。
            val start = (0..1380 step 30).last { start -> runCatching {
                PlacementValidator.requireValid(PlannedSegment(taskId = id, date = day, startMinute = start, endMinute = start + 30),
                    plan?.segments.orEmpty(), weekly, overrides, semester)
            }.isSuccess }
            app.planRepository.placeTask(id, day, start, start + 30, "focus")
            app.planRepository.observeCurrentPlan().first()!!.segments.single { it.taskId == id }
        }
        val entryId = "segment:${segment.id}"
        try {
            selectDate(day)
            selectScope("day")
            assertScopeSelected("day")
            // 日视图真正会滚的是时间轴自身（schedule-day-scroll）；首页外层在长屏上常常整页装得下。
            rule.onNodeWithTag("timeline-$entryId").performScrollTo()
            rule.waitForIdle()
            val before = rule.onNodeWithTag("schedule-day-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("The fixture must actually scroll before opening detail", before > 0f)
            rule.onNodeWithTag("timeline-$entryId").performClick()
            waitTag("timeline-entry-detail-$entryId")
            rule.onNodeWithTag("timeline-entry-detail-$entryId").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            waitTag("schedule-selected-date")
            rule.waitForIdle()
            // 1.1.0 起浮层跟随用户：旋转时正在看的时段（以及刚拨的轮盘）一起回来，
            // 否则手动改时间的人转个屏就把选好的档位丢了。底下这页看的是哪天、哪一格、滚到哪儿，都不该动。
            rule.onNodeWithTag("timeline-entry-detail-$entryId").assertIsDisplayed()
            rule.onNodeWithTag("schedule-selected-date")
                .assertTextEquals(scheduleDateHeadline(day, LocalDate.now()))
            assertScopeSelected("day")
            val after = rule.onNodeWithTag("schedule-day-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Scroll changed from $before to $after", kotlin.math.abs(before - after) <= 1f)
            rule.onNodeWithTag("timeline-$entryId").assertIsDisplayed()
        } finally {
            runBlocking {
                app.planRepository.observeCurrentPlan().first()?.segments?.firstOrNull { it.taskId == id }?.let {
                    app.planRepository.removePlacement(it.id)
                }
            }
            SQLiteDatabase.openDatabase(rule.activity.getDatabasePath("repland.db").absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id)) }
        }
    }
}
