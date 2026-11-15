package com.swan1127.repland

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.schedule.TimelineDashboard
import com.swan1127.repland.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

internal object PriorityScheduleFixture {
    val day = LocalDate.of(2026, 10, 15)
    val entries = listOf(
        TimelineEntry("high", "完成阅读报告", TimelineKind.TASK, day, 540, 600, taskId = "high", note = "图书馆 · 先整理核心观点"),
        TimelineEntry("middle", "整理课堂笔记", TimelineKind.TASK, day, 570, 630, taskId = "middle", lane = 1, note = "主校区 3 号楼 405"),
        TimelineEntry("low", "复习英语词汇", TimelineKind.TASK, day, 570, 600, taskId = "low", lane = 2),
    )
}

class PriorityScheduleUiTest {
    @get:Rule val rule = createComposeRule()
    private val fixture = PriorityScheduleFixture

    private fun content(
        entries: List<TimelineEntry> = fixture.entries,
        dark: Boolean = false, large: Boolean = false, reduced: Boolean = false,
        onInspect: (String) -> Unit = {},
        onUpdate: (TimelineEntry, Int, Int, LocalDate?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                ReplandTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalReducedMotion provides reduced) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            TimelineDashboard(
                                entries = entries,
                                mode = EngagementMode.GUIDED,
                                onOpenEntry = onInspect,
                                onUpdateEntryTime = onUpdate,
                                clockNow = fixture.day.atTime(12, 0),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun overlapping_entries_all_stay_reachable() {
        content()
        listOf("high", "middle", "low").forEach {
            rule.onNodeWithTag("timeline-$it").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun detail_sheet_opens_the_clicked_entry_with_its_own_window() {
        var inspected = ""
        content(onInspect = { inspected = it })
        rule.onNodeWithTag("timeline-middle").performScrollTo().performClick()
        val sheet = rule.onNodeWithTag("timeline-entry-detail-middle", useUnmergedTree = true)
        sheet.assertExists()
        // 卡片上也写着 09:30–10:30：光「有这段字」说明不了弹层里也写了，得限定在弹层里面找。
        sheet.assert(hasAnyDescendant(hasText("09:30–10:30", substring = true)))
        assertEquals("middle", inspected)
    }

    @Test fun time_editing_from_the_detail_sheet_keeps_the_original_entry() {
        var start = -1; var end = -1; var editedId: String? = null
        content(onUpdate = { entry, newStart, newEnd, _, saved ->
            editedId = entry.id; start = newStart; end = newEnd; saved()
        })
        // 三张卡在 09:30–10:30 这段是叠着的：点最上面那张才真的打开它的详情，
        // 点被压住的卡只会命中盖在它上面的那张。
        rule.onNodeWithTag("timeline-middle").performScrollTo().performClick()
        rule.onNodeWithTag("timeline-time-editor-middle", useUnmergedTree = true).performScrollTo().performClick()
        rule.pickWheelTime("timeline-start-middle", "11:00")
        // 原窗口 09:30–10:30（60 分钟）；缩一档时长 → 11:00–11:45。
        rule.onNodeWithTag("timeline-duration-minus-middle", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("timeline-time-save-middle", useUnmergedTree = true).performClick()
        rule.runOnIdle {
            assertEquals("middle", editedId); assertEquals(660, start); assertEquals(705, end)
        }
    }

    @Test fun dark_large_reduced_motion_keeps_every_entry_reachable() {
        content(dark = true, large = true, reduced = true)
        listOf("high", "middle", "low").forEach {
            rule.onNodeWithTag("timeline-$it").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun fixed_course_is_rendered_like_any_other_event() {
        content(entries = listOf(TimelineEntry("course", "课程", TimelineKind.COURSE, fixture.day, 780, 840)))
        rule.onNodeWithTag("timeline-course").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("课程").assertExists()
    }
}
