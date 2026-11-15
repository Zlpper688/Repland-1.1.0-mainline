package com.swan1127.repland

import com.swan1127.repland.domain.model.RuleAction
import com.swan1127.repland.domain.model.RuleIntentKind
import com.swan1127.repland.domain.model.RuleIntentParser
import com.swan1127.repland.domain.model.RuleQueryKind
import com.swan1127.repland.domain.model.RuleTask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIntentParserTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val tasks = listOf(
        RuleTask("t1", "背单词", 30, today),
        RuleTask("t2", "吃饭", 60, today),
        RuleTask("t3", "高数", 90, null),
    )

    @Test
    fun review_keeps_the_original_block_and_adds_extra_time() {
        val plan = RuleIntentParser.parse("背单词没背完，后天补四十分钟", today, tasks)

        assertFalse(plan.mustAsk)
        assertTrue(plan.review)
        assertEquals(RuleAction.PROGRESS, plan.draftItems[0].action)
        assertNull(plan.draftItems[0].progress)
        assertEquals("背单词", plan.draftItems[0].name)
        assertEquals(RuleAction.CREATE, plan.draftItems[1].action)
        assertEquals(LocalDate.of(2026, 11, 17), plan.draftItems[1].date)
        assertEquals(40, plan.draftItems[1].durationMinutes)
    }

    @Test
    fun review_reads_a_spoken_half_as_progress() {
        val plan = RuleIntentParser.parse("高数只写了一半，明天再给我四十分钟", today, tasks)

        assertTrue(plan.review)
        assertEquals(50, plan.reviewProgress)
        assertEquals(LocalDate.of(2026, 11, 16), plan.draftItems[1].date)
        assertEquals(40, plan.draftItems[1].durationMinutes)
    }

    @Test
    fun move_uses_the_sentence_date_instead_of_the_old_plan() {
        val plan = RuleIntentParser.parse("吃饭改到明天下午三点", today, tasks)

        assertFalse(plan.mustAsk)
        val item = plan.draftItems.single()
        assertEquals(RuleAction.SCHEDULE, item.action)
        assertTrue(item.move)
        assertEquals("吃饭", item.name)
        assertEquals(LocalDate.of(2026, 11, 16), item.date)
        assertEquals(15 * 60, item.startMinute)
    }

    @Test
    fun postpone_drags_the_day_from_the_planned_date() {
        val plan = RuleIntentParser.parse("吃饭往后推一天", today, tasks)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.POSTPONE, item.action)
        assertEquals(LocalDate.of(2026, 11, 16), item.date)
    }

    @Test
    fun a_deadline_is_not_a_placed_block() {
        val plan = RuleIntentParser.parse("高数周五前交", today, tasks)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.CREATE, item.action)
        assertNull(item.date)
        assertEquals(LocalDate.of(2026, 11, 20), item.dueDate)
        assertNull(item.dueMinute)
    }

    @Test
    fun a_clock_deadline_keeps_the_minute_but_still_no_block() {
        val plan = RuleIntentParser.parse("高数明天下午三点前交", today, tasks)

        val item = plan.draftItems.single()
        assertNull(item.date)
        assertEquals(LocalDate.of(2026, 11, 16), item.dueDate)
        assertEquals(15 * 60, item.dueMinute)
    }

    @Test
    fun spoken_weekday_and_clock_become_absolute_slots() {
        val plan = RuleIntentParser.parse("下周一上午十点组会", today)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.CREATE, item.action)
        assertEquals(LocalDate.of(2026, 11, 16), item.date)
        assertEquals(10 * 60, item.startMinute)
        assertEquals("组会", item.name)
        assertTrue(plan.dateExplicit)
    }

    @Test
    fun a_past_month_day_rolls_to_the_next_year() {
        val plan = RuleIntentParser.parse("10月20日下午两点答辩", today)

        assertEquals(LocalDate.of(2027, 10, 20), plan.draftItems.single().date)
    }

    @Test
    fun daily_wording_never_collapses_into_one_day() {
        val plan = RuleIntentParser.parse("每天下午四点玩王者荣耀两个小时", today)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.ROUTINE, item.action)
        assertEquals((1..7).toList(), item.weekdays)
        assertEquals(16 * 60, item.startMinute)
        assertEquals(120, item.durationMinutes)
        assertEquals("玩王者荣耀", item.name)
    }

    @Test
    fun a_weekday_pair_keeps_both_days() {
        val plan = RuleIntentParser.parse("周二和周四晚上八点练琴", today, tasks)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.ROUTINE, item.action)
        assertEquals(listOf(2, 4), item.weekdays)
        assertEquals(20 * 60, item.startMinute)
    }

    @Test
    fun a_busy_window_needs_no_task_name() {
        val plan = RuleIntentParser.parse("明天下午三点到五点别排事", today)

        assertFalse(plan.mustAsk)
        val item = plan.draftItems.single()
        assertEquals(RuleAction.BUSY, item.action)
        assertEquals(LocalDate.of(2026, 11, 16), item.date)
        assertEquals(15 * 60, item.startMinute)
        assertEquals(17 * 60, item.endMinute)
    }

    @Test
    fun a_weekly_busy_window_keeps_its_days() {
        val plan = RuleIntentParser.parse("每周二下午三点到五点别排事", today)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.BUSY, item.action)
        assertEquals(listOf(2), item.weekdays)
        assertEquals(15 * 60, item.startMinute)
        assertEquals(17 * 60, item.endMinute)
    }

    @Test
    fun a_busy_day_without_a_window_asks_for_one() {
        val plan = RuleIntentParser.parse("明天下午别排事", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("busy-window-missing"))
    }

    @Test
    fun two_independent_actions_ask_instead_of_collapsing() {
        val plan = RuleIntentParser.parse("明天写报告，后天交作业", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.items.isEmpty())
        assertEquals(RuleIntentKind.CLARIFY, plan.intent)
        assertTrue(plan.reasons.contains("multi-intent"))
    }

    @Test
    fun a_pronoun_asks_which_task() {
        val plan = RuleIntentParser.parse("那个往后推一天", today, tasks)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("pronoun"))
    }

    @Test
    fun a_conditional_plan_asks_first() {
        val plan = RuleIntentParser.parse("如果明天有空就写报告", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("conditional"))
    }

    @Test
    fun an_unsupported_repeat_asks_instead_of_guessing_days() {
        val plan = RuleIntentParser.parse("隔天跑步半小时", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("unsupported-recurrence"))
    }

    @Test
    fun next_weekend_asks_which_day() {
        val plan = RuleIntentParser.parse("下周末去爬山", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("ambiguous-weekend"))
    }

    @Test
    fun next_month_asks_for_a_real_day() {
        val plan = RuleIntentParser.parse("下个月交房租", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("relative-month"))
    }

    @Test
    fun a_cross_midnight_window_asks_to_split() {
        val plan = RuleIntentParser.parse("晚上十一点到凌晨一点写代码", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("cross-midnight"))
    }

    @Test
    fun english_without_a_date_stops_at_the_ai_boundary() {
        val plan = RuleIntentParser.parse("tomorrow 3pm meeting", today)

        assertTrue(plan.mustAsk)
        assertTrue(plan.reasons.contains("english-intent"))
    }

    @Test
    fun a_spoken_fraction_is_arithmetic() {
        val plan = RuleIntentParser.parse("高数完成三分之二了", today, tasks)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.PROGRESS, item.action)
        assertEquals(67, item.progress)
    }

    @Test
    fun a_move_with_only_a_clock_keeps_the_same_day() {
        val plan = RuleIntentParser.parse("吃饭改到下午三点", today, tasks)

        assertFalse(plan.mustAsk)
        val item = plan.draftItems.single()
        assertEquals(RuleAction.SCHEDULE, item.action)
        assertNull(item.date)
        assertEquals(15 * 60, item.startMinute)
        assertEquals("吃饭", item.name)
    }

    @Test
    fun an_explicit_range_keeps_its_end() {
        val plan = RuleIntentParser.parse("明天下午三点到五点写报告", today)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.CREATE, item.action)
        assertEquals(LocalDate.of(2026, 11, 16), item.date)
        assertEquals(15 * 60, item.startMinute)
        assertEquals(17 * 60, item.endMinute)
        assertEquals(120, item.durationMinutes)
    }

    @Test
    fun a_postpone_to_a_weekday_lands_on_that_day() {
        val plan = RuleIntentParser.parse("高数推迟到周五", today, tasks)

        val item = plan.draftItems.single()
        assertEquals(RuleAction.POSTPONE, item.action)
        assertEquals(LocalDate.of(2026, 11, 20), item.date)
        assertNull(item.startMinute)
    }

    @Test
    fun status_questions_are_queries_not_captures() {
        val plan = RuleIntentParser.parse("今天还有什么没做", today)

        assertEquals(RuleIntentKind.QUERY, plan.intent)
        assertEquals(RuleQueryKind.ADVICE, plan.queryKind)
        assertTrue(plan.items.isEmpty())
    }
}
