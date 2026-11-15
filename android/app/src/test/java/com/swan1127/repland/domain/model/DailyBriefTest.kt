package com.swan1127.repland.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日简报是确定性本地整理：同一份日程永远生成同一句话，空档口径与规划师实况一致
 * （课程/固定/休息硬占用，补集夹在 8:00–22:00、至少 30 分钟）。这些用例把口径钉死，防漂移。
 */
class DailyBriefTest {
    private val day = LocalDate.of(2026, 10, 23)

    private fun entry(id: String, title: String, kind: TimelineKind, start: Int, end: Int, date: LocalDate = day) =
        TimelineEntry(id, title, kind, date, start, end)

    @Test fun emptyDayInvitesSchedulingAndShowsTheWholeDayOpen() {
        val brief = DailyBrief.of(day, emptyList())
        assertEquals("今天还没有安排，说一句话我帮你排。", brief.headline)
        assertTrue(brief.evidence.isEmpty())
        assertEquals(listOf("08:00-22:00"), brief.freeWindows)
        assertEquals("按本地日程整理 · 未调用云端", brief.footer)
    }

    @Test fun courseAndTaskProduceHonestCountsAndWindows() {
        val occupied = listOf(
            entry("c", "高数", TimelineKind.COURSE, 9 * 60, 10 * 60),
            entry("t", "复习", TimelineKind.TASK, 10 * 60 + 30, 11 * 60 + 30),
        )
        val brief = DailyBrief.of(day, occupied)
        assertEquals("今天有 2 段可用空档，1 项固定占用、1 件已排任务。", brief.headline)
        // 空档是硬占用的补集：已排任务落在空档里，但不把空档"吃掉"（与规划师同口径）。
        assertEquals(listOf("08:00-09:00", "10:00-22:00"), brief.freeWindows)
        assertEquals(
            listOf("09:00-10:00 高数（课程）", "10:30-11:30 复习（任务）"),
            brief.evidence,
        )
    }

    @Test fun fullyBookedDaySaysThereIsNoWholeFreeWindow() {
        val brief = DailyBrief.of(day, listOf(entry("c", "满课", TimelineKind.COURSE, 8 * 60, 22 * 60)))
        assertEquals("今天排得比较满，没有 30 分钟以上的整块空档。", brief.headline)
        assertTrue(brief.freeWindows.isEmpty())
    }

    @Test fun exactlyThirtyMinuteGapCountsButShorterDoesNot() {
        val thirty = DailyBrief.of(
            day,
            listOf(
                entry("a", "早课", TimelineKind.COURSE, 8 * 60, 8 * 60 + 30),
                entry("b", "午课", TimelineKind.COURSE, 9 * 60, 22 * 60),
            ),
        )
        assertEquals(listOf("08:30-09:00"), thirty.freeWindows)

        val twentyNine = DailyBrief.of(
            day,
            listOf(
                entry("a", "早课", TimelineKind.COURSE, 8 * 60, 8 * 60 + 31),
                entry("b", "午课", TimelineKind.COURSE, 9 * 60, 22 * 60),
            ),
        )
        assertTrue(twentyNine.freeWindows.isEmpty())
    }

    @Test fun restAndCommitmentAreHardBusyLikeCourse() {
        val brief = DailyBrief.of(
            day,
            listOf(
                entry("r", "午休", TimelineKind.REST, 12 * 60, 13 * 60),
                entry("k", "社团固定", TimelineKind.COMMITMENT, 18 * 60, 19 * 60),
            ),
        )
        assertEquals("今天有 3 段可用空档，2 项固定占用、0 件已排任务。", brief.headline)
        assertEquals(listOf("08:00-12:00", "13:00-18:00", "19:00-22:00"), brief.freeWindows)
    }

    @Test fun otherDatesAreIgnored() {
        val brief = DailyBrief.of(day, listOf(entry("c", "明天的课", TimelineKind.COURSE, 9 * 60, 10 * 60, day.plusDays(1))))
        assertEquals("今天还没有安排，说一句话我帮你排。", brief.headline)
        assertTrue(brief.evidence.isEmpty())
    }

    @Test fun generationIsDeterministicRegardlessOfInputOrder() {
        val occupied = listOf(
            entry("c", "高数", TimelineKind.COURSE, 9 * 60, 10 * 60),
            entry("t", "复习", TimelineKind.TASK, 10 * 60 + 30, 11 * 60 + 30),
            entry("r", "午休", TimelineKind.REST, 12 * 60, 13 * 60),
        )
        assertEquals(DailyBrief.of(day, occupied), DailyBrief.of(day, occupied))
        assertEquals(DailyBrief.of(day, occupied), DailyBrief.of(day, occupied.reversed()))
    }
}