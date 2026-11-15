package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 技能清单是只读行为契约：读/写分组、原样展示触发描述，不提供任何开关（守住手动=对话等价）。 */
@RunWith(AndroidJUnit4::class)
class ToolCatalogUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun reset_and_pause() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        resetAssistantSessionForTest()
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun catalog_opens_from_plus_menu_and_groups_reads_and_writes_without_toggles() {
        rule.openScheduleAssistant()
        rule.onNodeWithTag("group-plus").performClick()
        rule.onNodeWithTag("group-tools").performClick()
        waitTag("tool-catalog-sheet")

        rule.onNodeWithText("读 · 直接做").assertExists()
        rule.onNodeWithText("写 · 先确认后落库").assertExists()
        // 真实工具面：读类直接做，写类必经确认卡。
        rule.onNodeWithTag("tool-catalog-query_tasks").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("tool-catalog-create_task").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("清单随队伍与身份变化；这里只展示，不提供开关。").performScrollTo().assertExists()

        rule.onNodeWithTag("tool-catalog-close").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("tool-catalog-sheet").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("group-input").assertExists()
    }
}