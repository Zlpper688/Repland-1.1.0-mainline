package com.swan1127.repland

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex

/**
 * 时间输入现在是时/分轮盘，测试按档位滚动而不是往框里打字。
 * 容器沿用改造前文本框的 tag，两列节点固定为 time-wheel-&lt;tag&gt;-hour / -minute。
 */
internal fun ComposeTestRule.pickWheelTime(tag: String, time: String) {
    val parts = time.trim().split(':', '：').map { it.trim().toInt() }
    waitUntil(10_000) { onAllNodesWithTag("time-wheel-$tag-hour", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    // 档位滚动是轮盘自己的动作，不借父容器：弹窗里的轮盘没有可滚祖先，performScrollTo 会直接报错。
    onNodeWithTag("time-wheel-$tag-hour", useUnmergedTree = true).performScrollToIndex(parts[0])
    onNodeWithTag("time-wheel-$tag-minute", useUnmergedTree = true).performScrollToIndex(parts[1] / 5)
    waitUntil(10_000) { onAllNodesWithTag("time-wheel-$tag-hour-item-${parts[0]}", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    assertWheelTime(tag, time)
}

/** 中间高亮档写进容器 contentDescription，用它断言轮盘当前值。 */
internal fun ComposeTestRule.assertWheelTime(tag: String, time: String) {
    val parts = time.trim().split(':', '：').map { it.trim().toInt() }
    onNodeWithTag(tag, useUnmergedTree = true)
        .assertContentDescriptionEquals("轮盘时间 ${wheelLabel(parts[0])}:${wheelLabel(parts[1] / 5 * 5)}")
}

private fun wheelLabel(value: Int): String = value.toString().padStart(2, '0')
