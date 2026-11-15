package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.schedule.scheduleDateHeadline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Before fun retain_history_but_remove_previous_placements_owned_by_this_qa_fixture() = runBlocking {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        val app = (rule.activity.application as ReplandApplication).appContainer
        val activeTask = app.executionSessionRepository.observeActive().first()?.taskId
        // Repeated runs retain the QA database. Use the real guarded operation only
        // for this fixture's ordinary placements; keep tasks, logs and plan history.
        // Each change creates a new plan and new segment IDs; reread before removal.
        while (true) {
            val owned = app.planRepository.observeCurrentPlan().first()?.segments.orEmpty().firstOrNull {
                (it.taskId.startsWith("qa-header-") || it.taskId.startsWith("qa-execution-")) && it.taskId != activeTask
            } ?: break
            app.planRepository.removePlacement(owned.id)
        }
    }
    // 失败的一步会把本轮留在打开状态，后面的测试类再开始自己的轮次就会被「先结束当前专注」挡住。
    // 收尾只结束本夹具自己的轮次、摘掉自己的安排，任务与执行历史都留着。
    @After fun end_rounds_and_remove_placements_left_by_a_failed_step() = runBlocking {
        val app = (rule.activity.application as ReplandApplication).appContainer
        app.executionSessionRepository.observeActive().first()
            ?.takeIf { it.taskId.startsWith("qa-header-") || it.taskId.startsWith("qa-execution-") }
            ?.let { runCatching { app.executionSessionRepository.finish(it.id, ExecutionOutcome.CONTINUE, TaskFeedback()) } }
        while (true) {
            val owned = app.planRepository.observeCurrentPlan().first()?.segments.orEmpty().firstOrNull {
                it.taskId.startsWith("qa-header-") || it.taskId.startsWith("qa-execution-")
            } ?: break
            app.planRepository.removePlacement(owned.id)
        }
    }
    private suspend fun freeSlot(id: String, firstOnDay: Boolean = false): PlannedSegment {
        val app = (rule.activity.application as ReplandApplication).appContainer
        val tasks = app.taskRepository.observeTasks().first()
        val plan = app.planRepository.observeCurrentPlan().first()
        val weekly = app.timeRepository.observeWeeklyBlocks().first()
        val overrides = app.timeRepository.observeDateOverrides().first()
        val semester = app.timeRepository.observeTimeConstraintSettings().first().semesterFirstWeekMonday
        return (1L..20L).asSequence().flatMap { offset ->
            val day = LocalDate.now().plusDays(offset)
            val entries = ScheduleTimeline.entries(day, weekly, overrides, plan?.segments.orEmpty(), tasks, semester)
            (0..1380 step 30).asSequence().filter { start -> !firstOnDay || entries.none { it.startMinute < start + 30 } }
                .map { start -> PlannedSegment(taskId = id, date = day, startMinute = start, endMinute = start + 30) }
        }.first { candidate -> runCatching {
            PlacementValidator.requireValid(candidate, plan?.segments.orEmpty(), weekly, overrides, semester,
                tasks.filter { it.userPriority == TaskPriority.REQUIRED }.map { it.id }.toSet())
        }.isSuccess }
    }
    // 首页滚动和底部弹层要靠真实帧推进：时钟自动推进时用 waitUntil/performScrollTo 即可。
    // 盖楼页的楼房动画每帧写状态，打开它以后 Compose 永远不空闲，所以进入前把时钟定住，
    // 由 pumpUntil 手动推帧；离开盖楼页再恢复自动推进。
    private fun settle(millis: Long = 500) {
        if (!rule.mainClock.autoAdvance) rule.mainClock.advanceTimeBy(millis)
    }
    private fun pumpUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "界面在 ${timeoutMillis}ms 内没有到达预期状态" }
            if (rule.mainClock.autoAdvance) Thread.sleep(16) else rule.mainClock.advanceTimeBy(64)
            Thread.sleep(4)
        }
    }
    private fun waitTag(tag: String) {
        pumpUntil { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun waitGone(tag: String) = pumpUntil { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    // 暂停的时钟下点击后必须把动画帧推完：留一帧未处理的重组，下一次交互会永远等空闲。
    private fun clickTag(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        settle()
    }
    private fun scrollClickTag(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        settle()
    }
    private fun openSessionFromPill() {
        // 浮条一点就打开盖楼页；时钟必须在点之前定住，否则后续交互等不到空闲。
        rule.mainClock.autoAdvance = false
        clickTag("focus-pill")
        waitTag("execution-pause-resume")
    }
    private fun leaveSession(tag: String = "execution-return") {
        clickTag(tag)
        waitGone("execution-session")
        waitTag("focus-pill")
        rule.mainClock.autoAdvance = true
    }
    @Test fun selected_day_header_starts_confirmed_slot_and_keeps_plan_on_return() {
        val app = (rule.activity.application as ReplandApplication).appContainer
        val id = "qa-header-${System.currentTimeMillis()}"
        val slot = runBlocking {
            app.taskRepository.save(TaskDraft(id, "今日开始入口", "", TaskCategory.COURSE, TaskPriority.HIGH, 1, 30, null))
            val candidate = freeSlot(id, firstOnDay = true)
            app.planRepository.placeTask(id, candidate.date, candidate.startMinute, candidate.endMinute, "focus")
            app.planRepository.observeCurrentPlan().first()!!.segments.first { it.taskId == id }
        }
        val planId = runBlocking { app.planRepository.observeCurrentPlan().first()!!.id }
        val draftBefore = runBlocking { app.planRepository.observeDraft().first() }
        // 读取就绪前首页还没有滚动容器；直接 performScrollToNode 会找不到节点。
        waitTag("today-page-scroll")
        // 日/周/月现在是同一个分段控件的三个格子：点 schedule-scope-month 才进月视图。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-month"))
        clickTag("schedule-scope-month")
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("month-date-${slot.date}"))
        clickTag("month-date-${slot.date}")
        rule.onNodeWithTag("schedule-selected-date")
            .assertTextEquals(scheduleDateHeadline(slot.date, LocalDate.now()))
        // 月格选完日子视口仍停在月；开始一轮要从那一天的时间轴卡片进，先翻回「日」。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag("schedule-scope-day"))
        clickTag("schedule-scope-day")
        val card = "timeline-segment:${slot.id}"
        val startFocus = "timeline-focus-segment:${slot.id}"
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag(card))
        clickTag(card)
        waitTag(startFocus)
        rule.onNodeWithTag(startFocus).performScrollTo()
        rule.mainClock.autoAdvance = false
        clickTag(startFocus)
        val session = runBlocking {
            withTimeout(5_000) { app.executionSessionRepository.observeActive().first { it?.segmentId == slot.id } }
        }
        waitTag("execution-pause-resume")
        clickTag("execution-pause-resume")
        leaveSession()
        // 已经有一轮没跑完时再点「进入专注」不能开出第二轮：它把人带回原来那一轮，
        // 旧按钮靠禁用说话，现在这条约束只能靠会话身份没有变化来证明。
        rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag(card))
        clickTag(card)
        waitTag(startFocus)
        rule.onNodeWithTag(startFocus).performScrollTo()
        rule.mainClock.autoAdvance = false
        clickTag(startFocus)
        assertEquals(session?.id, runBlocking { app.executionSessionRepository.observeActive().first()?.id })
        waitTag("execution-finish")
        clickTag("execution-finish")
        waitGone("execution-session")
        rule.mainClock.autoAdvance = true
        runBlocking { withTimeout(5_000) { app.executionSessionRepository.observeActive().first { it == null } } }
        assertEquals(planId, runBlocking { app.planRepository.observeCurrentPlan().first()!!.id })
        assertEquals(draftBefore, runBlocking { app.planRepository.observeDraft().first() })
        assertEquals(TaskStatus.IN_PROGRESS, runBlocking { app.taskRepository.observeTasks().first().first { it.id == id }.status })
    }
    @Test fun activity_recreation_restores_round_and_finishing_offers_preview_without_completing_task() {
        val app = (rule.activity.application as ReplandApplication).appContainer
        org.junit.Assume.assumeTrue("This workflow requires a QA device without an unrelated active round.",
            runBlocking { app.executionSessionRepository.observeActive().first() == null })
        val id = "qa-execution-${System.currentTimeMillis()}"
        runBlocking {
            app.taskRepository.save(TaskDraft(id, "执行闭环测试", "", TaskCategory.COURSE, TaskPriority.HIGH, 1, 60, null))
            val candidate = freeSlot(id)
            app.planRepository.placeTask(id, candidate.date, candidate.startMinute, candidate.endMinute, "focus")
            val slot = app.planRepository.observeCurrentPlan().first()!!.segments.first { it.taskId == id }
            app.executionSessionRepository.start(slot.id)
        }
        waitTag("focus-pill")
        rule.onNodeWithTag("accept-plan-draft").assertDoesNotExist()
        assertNull(runBlocking { app.planRepository.observeDraft().first() })
        rule.onNodeWithTag("focus-pill").assertIsDisplayed()
        openSessionFromPill()
        clickTag("execution-pause-resume")
        runBlocking { withTimeout(5_000) { app.executionSessionRepository.observeActive().first { it?.isPaused == true } } }
        clickTag("execution-return")
        waitGone("execution-session")
        waitTag("focus-pill")
        rule.mainClock.autoAdvance = true
        rule.activityRule.scenario.recreate()
        waitTag("focus-pill")
        rule.onNodeWithTag("focus-pill").assertIsDisplayed()
        openSessionFromPill()
        rule.onNodeWithText("执行闭环测试 · 已暂停").assertExists()
        rule.onNodeWithTag("accept-plan-draft").assertDoesNotExist()
        leaveSession()
        openSessionFromPill()
        waitTag("execution-finish")
        clickTag("execution-finish")
        waitGone("execution-session")
        rule.mainClock.autoAdvance = true
        runBlocking { withTimeout(5_000) { app.executionSessionRepository.observeActive().first { it == null } } }
        assertEquals(TaskStatus.IN_PROGRESS, runBlocking { app.taskRepository.observeTasks().first().first { it.id == id }.status })
        val planBefore = runBlocking { app.planRepository.observeCurrentPlan().first()!!.id }
        waitTag("execution-replan")
        scrollClickTag("execution-replan")
        waitTag("accept-plan-draft")
        assertEquals(planBefore, runBlocking { app.planRepository.observeCurrentPlan().first()!!.id })
        // Finish the user's cancel branch rather than leave a modal QA proposal over
        // the next Activity. Later page-navigation tests must target the page window.
        rule.onNodeWithText(rule.activity.getString(R.string.discard_plan_draft)).performClick()
        settle()
        waitGone("accept-plan-draft")
        assertNull(runBlocking { app.planRepository.observeDraft().first() })
        assertEquals(planBefore, runBlocking { app.planRepository.observeCurrentPlan().first()!!.id })
    }
}
