package com.swan1127.repland

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.domain.model.EngagementMode
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import com.swan1127.repland.ui.schedule.TimelineDashboard
import com.swan1127.repland.ui.theme.ReplandTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineDashboardUiTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        entries: List<TimelineEntry>,
        onOpenEntry: (String) -> Unit = {},
        onEditEntry: (TimelineEntry) -> Unit = {},
        onUpdateEntryTime: (TimelineEntry, Int, Int, LocalDate?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    ) {
        val day = LocalDate.now()
        rule.setContent {
            TimelineTestTheme {
                TimelineDashboard(
                    clockNow = day.atTime(8, 0),
                    entries = entries,
                    mode = EngagementMode.GUIDED,
                    onOpenEntry = onOpenEntry,
                    onEditEntry = onEditEntry,
                    onUpdateEntryTime = onUpdateEntryTime,
                    scheduleDate = day,
                )
            }
        }
    }

    @Test fun tapping_a_block_opens_detail_and_note_action_requests_an_edit() {
        val day = LocalDate.now()
        var inspected = ""
        var editRequested = ""
        show(
            listOf(
                TimelineEntry("course", "高数", TimelineKind.COURSE, day, 540, 600),
                TimelineEntry("work", "复习高数", TimelineKind.TASK, day, 660, 720, taskId = "task-one"),
            ),
            onOpenEntry = { inspected = it },
            onEditEntry = { editRequested = it.id },
        )
        rule.onNodeWithTag("timeline-work").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-entry-detail-work", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("timeline-note-work", useUnmergedTree = true).assertExists()
        assertEquals("work", inspected)
        rule.onNodeWithTag("timeline-note-work", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("work", editRequested)
    }

    @Test fun timeline_detail_adjusts_time_from_wheel_stops() {
        val day = LocalDate.now()
        var savedStart = -1
        var savedEnd = -1
        show(
            listOf(TimelineEntry("work", "复习高数", TimelineKind.TASK, day, 570, 630, taskId = "task-one")),
            onUpdateEntryTime = { _, startMinute, endMinute, _, saved ->
                savedStart = startMinute; savedEnd = endMinute; saved()
            },
        )
        rule.onNodeWithTag("timeline-work").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-work", useUnmergedTree = true).performScrollTo().performClick()
        rule.pickWheelTime("timeline-start-work", "10:00")
        // 结束轮盘已去掉，起点保持原时长 60 分钟；用 +15 得到 11:15。
        rule.onNodeWithTag("timeline-duration-plus-work", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("timeline-time-save-work", useUnmergedTree = true).performClick()
        assertEquals(10 * 60, savedStart)
        assertEquals(11 * 60 + 15, savedEnd)
    }

    /**
     * 手动路径要和对话等价：助手能把安排挪到别的日子，日视图里点开同一块也必须能。
     * 之前只有任务详情给了换天入口，日视图的「调整时间」困在当天。
     */
    @Test fun the_day_view_time_editor_moves_a_placement_to_another_day() {
        val day = LocalDate.now()
        val later = day.plusDays(2)
        var savedDate: LocalDate? = null
        show(
            listOf(TimelineEntry("segment:work", "复习高数", TimelineKind.TASK, day, 570, 630, taskId = "task-one")),
            onUpdateEntryTime = { _, _, _, date, saved -> savedDate = date; saved() },
        )
        rule.onNodeWithTag("timeline-segment:work").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-segment:work", useUnmergedTree = true).performScrollTo().performClick()
        // 时间编辑器是弹窗，内容列没有可滚祖先；日期行本身就在可见区域里。
        rule.onNodeWithTag("timeline-date-segment:work", useUnmergedTree = true).performClick()
        // 目标日子可能落在下个月，日历先翻到位再点格子。
        if (later.monthValue != day.monthValue) rule.onNodeWithTag("task-date-picker-next-month").performClick()
        rule.onNodeWithTag("task-date-cell-${later.year}-${later.monthValue}-${later.dayOfMonth}").performClick()
        rule.onNodeWithTag("task-date-picker-confirm").performClick()
        rule.onNodeWithTag("timeline-time-save-segment:work", useUnmergedTree = true).performClick()
        assertEquals("换天要带着目标日子落到仓储，否则只能挪当天", later, savedDate)
    }

    /** 课程和固定事项按星期走，日视图不给它们开换天。 */
    @Test fun a_recurring_course_keeps_its_time_editor_without_a_date_row() {
        val day = LocalDate.now()
        show(listOf(TimelineEntry("weekly:math", "高数", TimelineKind.COURSE, day, 540, 600)))
        rule.onNodeWithTag("timeline-weekly:math").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-weekly:math", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("timeline-date-weekly:math", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun overlapping_entries_merge_into_one_block_that_keeps_each_of_them() {
        val day = LocalDate.now()
        show(listOf(
            TimelineEntry("course", "高数", TimelineKind.COURSE, day, 540, 630),
            TimelineEntry("work", "复习", TimelineKind.TASK, day, 570, 660, taskId = "task-one"),
        ))
        rule.onNodeWithTag("timeline-group-course").assertExists()
        rule.onNodeWithText("2 件同时进行").assertExists()
        rule.onNodeWithText("09:00–11:00").assertExists()
        listOf("course", "work").forEach {
            rule.onNodeWithTag("timeline-$it").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun empty_day_offers_the_event_library_and_says_so_when_nothing_waits() {
        show(emptyList())
        rule.onNodeWithTag("schedule-arrange-task").performClick()
        rule.onNodeWithText("没有待安排的事项。").assertExists()
    }
}

@Composable
private fun TimelineTestTheme(content: @Composable () -> Unit) {
    ReplandTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
    }
}
