package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 一键模板只预填、不自动保存：点完标题/时间/时长/提醒都落到表单上，仍可逐项改。 */
@RunWith(AndroidJUnit4::class)
class ScheduleEventTemplateUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun pause_automatic_maintenance() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openScheduleSheet() {
        waitTag("navigation-today")
        rule.onNodeWithTag("navigation-today").performClick()
        val entry = listOf("schedule-create-add", "current-event-add").firstOrNull { tag ->
            runCatching { rule.onNodeWithTag("today-page-scroll").performScrollToNode(hasTestTag(tag)) }.isSuccess
        } ?: throw AssertionError("今天的日程页没有任何手动新增入口")
        rule.onNodeWithTag(entry).performClick()
        waitTag("schedule-event-sheet")
    }

    @Test fun bedtime_template_prefills_title_time_duration_and_reminder() {
        openScheduleSheet()
        for (key in listOf("morning", "review", "weekly")) {
            rule.onNodeWithTag("schedule-event-template-$key").performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithTag("schedule-event-template-review").performClick()
        rule.onNodeWithTag("schedule-event-title").assertTextContains("睡前回顾")
        rule.onNodeWithTag("schedule-event-start-row").assertTextContains("22:00", substring = true)
        rule.onNodeWithText("时长 15 分钟", substring = true).assertExists()
        rule.onNodeWithText("开始前", substring = true).assertExists()
    }

    @Test fun switching_template_replaces_the_previous_prefill() {
        openScheduleSheet()
        rule.onNodeWithTag("schedule-event-template-review").performScrollTo().performClick()
        rule.onNodeWithTag("schedule-event-title").assertTextContains("睡前回顾")
        rule.onNodeWithTag("schedule-event-template-weekly").performScrollTo().performClick()
        rule.onNodeWithTag("schedule-event-title").assertTextContains("每周复盘")
        rule.onNodeWithText("时长 30 分钟", substring = true).assertExists()
        rule.onNodeWithTag("schedule-event-start-row").assertTextContains("20:00", substring = true)
    }
}