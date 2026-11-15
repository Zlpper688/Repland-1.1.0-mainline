package com.swan1127.repland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.TasksScreen
import com.swan1127.repland.ui.theme.ReplandTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 长按进多选、按住扫过的行都勾上、停在边缘自己往下滚：三件事都要真的发生。
 * 全程不调用 performScrollTo，否则「自动滚动」就被测试自己作弊掉了。
 */
class TaskSelectionGestureUiTest {
    @get:Rule val rule = createComposeRule()

    private var opened = 0
    private val tasks = (0 until 24).map { index ->
        Task("drag-$index", "", "拖动事项 $index", TaskCategory.COURSE, TaskPriority.MEDIUM, 1, 30,
            null, TaskStatus.NOT_STARTED, null, null, null, 0, 0, 0)
    }

    private fun openInbox() {
        opened = 0
        rule.setContent {
            ReplandTheme {
                Box(Modifier.width(360.dp).height(600.dp)) {
                    TasksScreen(tasks, false, { opened++ }, {}, {})
                }
            }
        }
        rule.onNodeWithTag("task-filter-INBOX").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("task-card-拖动事项 0").assertExists()
    }

    private fun listBounds() = rule.onNodeWithTag("task-list-scroll").fetchSemanticsNode().boundsInRoot

    private fun centerInList(tag: String): Offset {
        val list = listBounds()
        val row = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(row.center.x - list.left, row.center.y - list.top)
    }

    private fun selectedCount(): Int {
        val text = rule.onNodeWithTag("batch-count").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        return Regex("\\d+").find(text)?.value?.toIntOrNull() ?: -1
    }

    @Test fun long_press_enters_selection_and_does_not_open_detail() {
        openInbox()
        rule.onNodeWithTag("task-card-拖动事项 0").performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        rule.waitForIdle()
        assertEquals(1, selectedCount())
        assertEquals("长按是选择，不是打开详情", 0, opened)
        rule.onNodeWithTag("batch-exit").performClick()
        rule.onNodeWithTag("batch-count").assertDoesNotExist()
    }

    @Test fun drag_sweeps_every_row_it_passes_over() {
        openInbox()
        val start = centerInList("task-card-拖动事项 0")
        val landing = centerInList("task-card-拖动事项 3")
        // 一次事件跨四行：中间两行没有落点，也必须被勾上。
        rule.onNodeWithTag("task-list-scroll").performTouchInput {
            down(start)
            advanceEventTime(800)
            moveTo(landing)
            advanceEventTime(40)
            up()
        }
        rule.waitForIdle()
        assertEquals(4, selectedCount())
        assertEquals(0, opened)
    }

    @Test fun holding_at_the_bottom_edge_scrolls_and_keeps_painting() {
        openInbox()
        val start = centerInList("task-card-拖动事项 0")
        val list = listBounds()
        val edge = Offset(list.width / 2f, list.height - 40f)
        rule.onNodeWithTag("task-list-scroll").performTouchInput {
            down(start)
            advanceEventTime(800)
            moveTo(edge)
        }
        rule.waitUntil(20_000) { selectedCount() == tasks.size }
        rule.onNodeWithTag("task-list-scroll").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("停在下边缘应当一路滚到底并把路过的行都勾上", tasks.size, selectedCount())
    }

    @Test fun icon_row_selects_all_and_inverts_only_what_is_on_screen() {
        openInbox()
        rule.onNodeWithTag("batch-select-all").performClick()
        rule.waitForIdle()
        assertEquals(tasks.size, selectedCount())
        rule.onNodeWithTag("batch-invert").performClick()
        rule.waitForIdle()
        assertEquals(0, selectedCount())
        rule.onNodeWithTag("batch-select-all").performClick()
        rule.onNodeWithTag("task-card-拖动事项 0").performClick()
        rule.waitForIdle()
        assertEquals(tasks.size - 1, selectedCount())
        rule.onNodeWithTag("batch-exit").performClick()
        rule.onNodeWithTag("batch-count").assertDoesNotExist()
        assertEquals("图标行只做选择，不该顺手打开详情", 0, opened)
    }
}