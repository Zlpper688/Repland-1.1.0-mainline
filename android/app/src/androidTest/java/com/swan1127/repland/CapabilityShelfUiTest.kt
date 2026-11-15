package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 能力货架收在设置页折叠区里：六行真实能力、三态语言、授权前先讲清三段信任框架。 */
@RunWith(AndroidJUnit4::class)
class CapabilityShelfUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun pause_automatic_maintenance() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun shelf_lists_real_capabilities_and_opens_trust_dialog_before_granting() {
        rule.openMineSettings()
        rule.onNodeWithTag("capability-shelf-disclosure").performScrollTo().performClick()
        waitTag("capability-shelf")
        for (key in listOf("notifications", "exact-alarm", "battery", "voice", "cloud-ai", "team-sync")) {
            rule.onNodeWithTag("capability-$key").performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithTag("capability-shelf-note").performScrollTo().assertIsDisplayed()

        // 通知行带信任框架：点一下先弹三段说明，而不是直接跳系统授权。
        rule.onNodeWithTag("capability-notifications").performScrollTo().performClick()
        waitTag("capability-trust-dialog")
        for (section in listOf("让助手更好地帮你", "数据由你掌控", "需要保持关注")) {
            rule.onNodeWithText(section).assertExists()
        }
        rule.onNodeWithTag("capability-trust-dismiss").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("capability-trust-dialog").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("capability-shelf").assertExists()
    }

    @Test fun shelf_stays_collapsed_until_expanded_so_settings_page_keeps_its_calm() {
        rule.openMineSettings()
        rule.onNodeWithTag("capability-shelf-disclosure").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("capability-shelf").assertDoesNotExist()
        rule.onNodeWithTag("capability-shelf-disclosure").performClick()
        waitTag("capability-shelf")
        rule.onNodeWithTag("capability-shelf-disclosure").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("capability-shelf").fetchSemanticsNodes().isEmpty()
        }
    }
}