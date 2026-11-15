package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.ui.agent.ChatItem
import com.swan1127.repland.ui.agent.GroupChatSession
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 长回复折叠→全文阅读器→长按表态这条质感链路的回归判据。 */
class ReplyBubbleUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val longReply = (1..10).joinToString("\n") {
        "第${it}段：先把明天下午的空档留给复习，其余任务按轻重往后挪，冲突的时段单独标出来等你核对。"
    }

    @Before fun seedLongReply() {
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        resetAssistantSessionForTest()
        GroupChatSession.items = listOf(ChatItem.Agent("seed-long", "planner", longReply))
    }

    private fun waitTag(tag: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun longReplyFoldsOpensReaderAndAcceptsFeedback() {
        rule.openScheduleAssistant()
        waitTag("reply-expand")
        rule.onNodeWithTag("reply-expand").performClick()
        waitTag("reply-reader")
        rule.onNodeWithText("全文").assertIsDisplayed()
        rule.onNodeWithTag("reply-reader-close").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("reply-reader").fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithTag("group-reply").performTouchInput { longClick() }
        rule.onNodeWithText("喜欢").performClick()
        rule.onNodeWithText("已记下：你喜欢这条回答", substring = true).assertIsDisplayed()
    }
}