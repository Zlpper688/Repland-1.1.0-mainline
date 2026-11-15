package com.swan1127.repland

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking

/** Legacy manual-workflow assertions explicitly exercise the app with daily delegation paused. */
internal fun pauseAutomaticPlanningForLegacyWorkflow(activity: MainActivity) = runBlocking {
    (activity.application as ReplandApplication).appContainer.autonomyRepository.setEnabled(false)
}

/**
 * 群聊的会话是进程级对象：它跨面板开关、跨 Activity 重建活着，测试之间也必须清干净，
 * 否则上一格测试里的一句话会替下一格测试作答。
 */
internal fun resetAssistantSessionForTest() {
    com.swan1127.repland.ui.agent.GroupChatSession.items = emptyList()
    com.swan1127.repland.ui.agent.GroupChatSession.transcript = emptyList()
    com.swan1127.repland.ui.agent.GroupChatSession.pending = emptyList()
    com.swan1127.repland.ui.agent.GroupChatSession.nextId = 0
    com.swan1127.repland.ui.agent.GroupChatSession.createdTasks = emptyList()
    com.swan1127.repland.ui.agent.GroupChatSession.scheduledTaskIds = emptySet()
    com.swan1127.repland.ui.agent.GroupChatSession.draft = ""
}

/** Open the only assistant through the shared composer entry above the bottom bar. */
internal fun ComposeContentTestRule.openScheduleAssistant() {
    waitUntil(10_000) { onAllNodesWithTag("navigation-today").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("navigation-today").performClick()
    waitUntil(10_000) { onAllNodesWithTag("composer-bar").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("composer-bar").performClick()
    // 输入栏打开的是群聊面板（ui/agent/GroupChatSheet.kt）。
    waitUntil(10_000) { onAllNodesWithTag("group-input").fetchSemanticsNodes().isNotEmpty() }
}

/** 通知卡片的文字在子节点上，带标签的 Surface 自己不合并文本。 */
internal fun ComposeContentTestRule.assertNoticeContains(text: String) {
    onNodeWithTag("group-notice").assert(hasAnyDescendant(hasText(text, substring = true)))
}

/** Settings are reached through the profile page, never through hidden UI shortcuts. */internal fun ComposeContentTestRule.openMineSettings() {
    waitUntil(10_000) { onAllNodesWithTag("navigation-mine").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("navigation-mine").performClick()
    waitUntil(10_000) { onAllNodesWithTag("mine-settings").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("mine-settings").performScrollTo().performClick()
    waitUntil(10_000) { onAllNodesWithTag("mine-settings-page").fetchSemanticsNodes().isNotEmpty() }
}
