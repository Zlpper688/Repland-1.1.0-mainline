package com.swan1127.repland

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import com.swan1127.repland.ui.schedule.scheduleDateHeadline
import java.time.LocalDate

/** Bring a month cell above the floating entry before sending a real visible click. */
internal fun ComposeTestRule.clickScheduleDate(date: LocalDate) {
    val dateTag = "month-date-$date"
    val list = onNodeWithTag("today-page-scroll")
    list.performScrollToNode(hasTestTag(dateTag))
    var attempts = 0
    while (true) {
        waitForIdle()
        val cell = onNodeWithTag(dateTag).fetchSemanticsNode().boundsInRoot
        val entry = onAllNodesWithTag("composer-bar").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        val overlapped = entry != null && cell.left < entry.right && cell.right > entry.left &&
            cell.top < entry.bottom && cell.bottom > entry.top
        if (!overlapped) break
        check(attempts++ < 4) { "Calendar date $date is still covered by the composer: cell=$cell, entry=$entry" }
        // Positive scrolling moves the calendar upward; leave a physical touch margin.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, cell.bottom - requireNotNull(entry).top + 16f)
        }
    }
    onNodeWithTag(dateTag).assertIsDisplayed().performClick()
    waitForIdle()
    // 顶部日期念的是「今天 / 明天 / 3月5日 周三」，不是绝对日期串：断言必须用同一段文案的生成函数，
    // 否则测试对的是一个界面上从来不存在的字。
    onNodeWithTag("schedule-selected-date").assertTextEquals(scheduleDateHeadline(date, LocalDate.now()))
}
