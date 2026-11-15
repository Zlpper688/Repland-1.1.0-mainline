package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 每日简报 + 助手日记的三条产品边界：
 *  - 简报是确定性本地能力，胶囊与手打关键词同路（手动=对话等价），离线也能出；
 *  - 同一天只留最近一份卡，刷新就地编辑（借鉴 #8），历史走助手日记按主题成链（借鉴 #6）；
 *  - 日记是问责面：回话了才标"已回复"，没回话就直说没有后续依据，绝不编造（借鉴 #5）。
 * 主动贴简报依赖"今天是否真有安排"，不确定，所以每格都显式请求简报，不靠自动贴。
 */
@RunWith(AndroidJUnit4::class)
class BriefDiaryUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun reset_pause_and_clear_journal() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        resetAssistantSessionForTest()
        // 日记落盘：上一格测试的快照不能替这一格作答，开测前清干净。
        runBlocking {
            (rule.activity.application as ReplandApplication).appContainer.assistantJournalStore.clear()
        }
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    private fun waitText(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun requestBriefViaCapsule() {
        rule.onNodeWithTag("group-plus").performClick()
        rule.onNodeWithTag("group-brief").performClick()
        waitTag("group-brief-card")
    }

    @Test fun brief_card_from_plus_capsule_is_local_and_honest() {
        rule.openScheduleAssistant()
        requestBriefViaCapsule()

        // 带标签的是不合并文本的 Row，断言走子节点。
        rule.onNodeWithTag("group-brief-card")
            .assert(hasAnyDescendant(hasText("今日简报", substring = true)))
        rule.onNodeWithTag("group-brief-card")
            .assert(hasAnyDescendant(hasText("按本地日程整理 · 未调用云端", substring = true)))
    }

    @Test fun same_day_refresh_edits_one_card_in_place() {
        rule.openScheduleAssistant()
        requestBriefViaCapsule()
        // 数卡用卡片脚注（每张简报卡唯一）；不数「今日简报」——用户那句请求气泡也是这四个字。
        assertEquals(1, rule.onAllNodesWithText("按本地日程整理", substring = true).fetchSemanticsNodes().size)

        requestBriefViaCapsule()
        waitText("已更新")
        // 刷新就地编辑：仍然只有一张卡，且带上"已更新"编辑视角标记。
        assertEquals(1, rule.onAllNodesWithText("按本地日程整理", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("group-brief-card")
            .assert(hasAnyDescendant(hasText("已更新", substring = true)))
    }

    @Test fun diary_chains_by_topic_and_shows_honest_reply_status() {
        rule.openScheduleAssistant()
        requestBriefViaCapsule()

        rule.onNodeWithTag("group-plus").performClick()
        rule.onNodeWithTag("group-diary").performClick()
        waitTag("assistant-diary-sheet")

        // 只读 framing + 至少一条主动留痕。
        rule.onNodeWithText("助手主动说过的话，按主题成链。只读，不改写。").assertExists()
        assertTrue(rule.onAllNodesWithTag("diary-entry").fetchSemanticsNodes().isNotEmpty())
        // 显式请求 = 用户回话，如实标"已回复"。
        assertTrue(rule.onAllNodesWithText("已回复", substring = true).fetchSemanticsNodes().isNotEmpty())
        // 问责边界：没回话就没有后续依据，不编造。
        rule.onNodeWithText("没回话就没有后续依据，不编造", substring = true)
            .performScrollTo().assertExists()

        rule.onNodeWithTag("assistant-diary-close").performClick()
        waitGone("assistant-diary-sheet")
        rule.onNodeWithTag("group-input").assertExists()
    }

    @Test fun typed_keyword_produces_the_same_brief_offline_parity() {
        rule.openScheduleAssistant()
        // 手打"简报"与胶囊同路：先于云端闸门接住，不依赖密钥（手动=对话等价）。
        rule.onNodeWithTag("group-input").performTextReplacement("简报")
        rule.onNodeWithTag("group-send").performClick()
        waitTag("group-brief-card")

        rule.onNodeWithTag("group-brief-card")
            .assert(hasAnyDescendant(hasText("按本地日程整理 · 未调用云端", substring = true)))
    }
}