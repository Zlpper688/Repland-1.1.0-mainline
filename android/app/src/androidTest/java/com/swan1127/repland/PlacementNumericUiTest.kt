package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.components.taskDateLabel
import com.swan1127.repland.ui.schedule.TimelineDashboard
import com.swan1127.repland.ui.schedule.TimelineEventObject
import com.swan1127.repland.ui.theme.ReplandTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 时间只能由轮盘档位产生：不会有打进去的字符串，也不会被改写。
 *
 * 结束轮盘已经拆掉（改成锁时长），所以这里守的是两件新事：建议档位互不相同、
 * 点一行即排一行；以及改开始时间不会再生出非法区间。
 */
class PlacementNumericUiTest {
    @get:Rule val rule = createComposeRule()

    private val day: LocalDate = LocalDate.now()
    private val midnight: LocalDateTime = LocalDateTime.of(day, LocalTime.of(0, 0))

    private fun arrangeSheet(
        events: List<TimelineEventObject>,
        entries: List<TimelineEntry> = emptyList(),
        onPlace: (Int, Int) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            ReplandTheme {
                TimelineDashboard(
                    entries = entries,
                    mode = EngagementMode.GUIDED,
                    onOpenEntry = {},
                    eventObjects = events,
                    onPlaceEvent = { _, _, startMinute, endMinute -> onPlace(startMinute, endMinute) },
                    scheduleDate = day,
                    clockNow = midnight,
                )
            }
        }
        rule.onNodeWithTag("schedule-arrange-task").performClick()
    }

    @Test fun one_tap_places_the_committed_chunk_and_skips_the_wheel_dialog() {
        var writes = 0
        var placed = -1 to -1
        arrangeSheet(
            events = listOf(TimelineEventObject("chunk30", "长任务", 300)),
            entries = listOf(TimelineEntry("busy30", "固定课程", TimelineKind.COURSE, day, 721, 780)),
            onPlace = { start, end -> writes++; placed = start to end },
        )
        // 300 分钟的任务只承诺一个 240 分钟的块：08:00 起排到 12:00，正好避开 12:01 的课程。
        rule.onNodeWithText("08:00–12:00 · 点一下排入").assertExists()
        rule.onNodeWithTag("event-object-chunk30").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, writes); assertEquals(480 to 720, placed) }
        rule.onNodeWithTag("timeline-time-save-chunk30").assertDoesNotExist()
    }

    @Test fun every_row_gets_its_own_slot_instead_of_the_same_suggestion() {
        arrangeSheet(events = listOf(
            TimelineEventObject("a30", "第一项", 30),
            TimelineEventObject("b60", "第二项", 60),
        ))
        // 改造前两行都报 08:00，连点两下会把两件事塞进同一个时段。
        rule.onNodeWithText("08:00–08:30 · 点一下排入").assertExists()
        rule.onNodeWithText("08:30–09:30 · 点一下排入").assertExists()
    }

    @Test fun moving_the_start_keeps_the_duration_and_always_produces_a_valid_range() {
        var writes = 0
        var saved = -1 to -1
        rule.setContent {
            ReplandTheme {
                TimelineDashboard(
                    entries = listOf(TimelineEntry("segment:lock30", "原任务", TimelineKind.TASK, day, 540, 600, taskId = "lock30")),
                    mode = EngagementMode.GUIDED,
                    onOpenEntry = {},
                    onUpdateEntryTime = { _, startMinute, endMinute, date, onSaved ->
                        writes++; saved = startMinute to endMinute; assertEquals(day, date); onSaved()
                    },
                    scheduleDate = day,
                    clockNow = midnight,
                )
            }
        }
        val tag = "segment:lock30"
        rule.onNodeWithTag("timeline-$tag").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-$tag").performScrollTo().performClick()
        rule.pickWheelTime("timeline-start-$tag", "09:15")
        rule.assertRangePreview("09:15–10:15", tag)
        // 步进胶囊挪开始时间，不需要甩轮盘（实测 84px 才挪 1 小时）。
        rule.onNodeWithTag("time-wheel-step-timeline-start-$tag-p15", useUnmergedTree = true).performClick()
        rule.assertRangePreview("09:30–10:30", tag)
        rule.onNodeWithTag("timeline-duration-plus-$tag", useUnmergedTree = true).performClick()
        rule.assertRangePreview("09:30–10:45", tag)
        rule.onNodeWithTag("timeline-time-save-$tag").performClick()
        rule.runOnIdle { assertEquals(1, writes); assertEquals(570 to 645, saved) }
    }

    @Test fun wheel_pick_and_locked_duration_survive_saved_state_restore() {
        var writes = 0
        val tag = "segment:restore30"
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            ReplandTheme {
                TimelineDashboard(
                    entries = listOf(TimelineEntry(tag, "原任务", TimelineKind.TASK, day, 540, 600, taskId = tag)),
                    mode = EngagementMode.GUIDED,
                    onOpenEntry = {},
                    onUpdateEntryTime = { _, _, _, _, _ -> writes++ },
                    scheduleDate = day,
                    clockNow = midnight,
                )
            }
        }
        rule.onNodeWithTag("timeline-$tag").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-$tag").performScrollTo().performClick()
        rule.pickWheelTime("timeline-start-$tag", "09:15")
        rule.onNodeWithTag("timeline-duration-minus-$tag", useUnmergedTree = true).performClick()
        // 60 分钟缩一档是 45 分钟：09:15 + 45 分钟 = 10:00。
        rule.assertRangePreview("09:15–10:00", tag)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("time-wheel-timeline-start-$tag-hour", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.assertWheelTime("timeline-start-$tag", "09:15")
        rule.assertRangePreview("09:15–10:00", tag)
        rule.onNodeWithTag("timeline-time-save-$tag").performClick()
        rule.runOnIdle { assertEquals(1, writes) }
    }
}

private fun ComposeTestRule.assertRangePreview(text: String, tag: String, day: LocalDate = LocalDate.now()) {
    // 时间编辑器支持跨天挪动后，预览行会带上日期前缀（ui/schedule/TimelineDashboard.kt:1475）。
    onNodeWithTag("timeline-range-preview-$tag", useUnmergedTree = true)
        .assertTextEquals("${taskDateLabel(day)} · $text")
}
