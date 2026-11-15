package com.swan1127.repland

import com.swan1127.repland.domain.model.ClassPeriod
import com.swan1127.repland.domain.model.ClassPeriodClock
import com.swan1127.repland.domain.model.ImportedCourse
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek

class ClassPeriodClockTest {
    private fun course(start: Int, end: Int) =
        ImportedCourse("c", "高等数学", DayOfWeek.MONDAY, start, end, "1-16周")

    @Test fun default_clock_has_eight_periods_and_converts_the_full_span() {
        assertEquals(8, ClassPeriodClock.DEFAULT.periods.size)
        val draft = ClassPeriodClock.DEFAULT.toWeeklyTimeBlockDraft(course(1, 2))!!
        assertEquals(8 * 60, draft.startMinute)
        assertEquals(9 * 60 + 40, draft.endMinute)
    }

    @Test fun custom_clock_times_win_over_the_default_and_period_gaps_are_ignored() {
        val clock = ClassPeriodClock(listOf(
            ClassPeriod(1, 9 * 60, 9 * 60 + 50),
            ClassPeriod(2, 10 * 60 + 10, 11 * 60),
        ))
        val draft = clock.toWeeklyTimeBlockDraft(course(1, 2))!!
        assertEquals(9 * 60, draft.startMinute)
        assertEquals(11 * 60, draft.endMinute)
    }

    @Test fun courses_beyond_the_clock_or_with_bad_ranges_cannot_be_converted() {
        assertNull(ClassPeriodClock.DEFAULT.toWeeklyTimeBlockDraft(course(2, 1)))
        assertNull(ClassPeriodClock.DEFAULT.toWeeklyTimeBlockDraft(course(8, 9)))
        assertNull(ClassPeriodClock.DEFAULT.toWeeklyTimeBlockDraft(course(0, 2)))
        val backwards = ClassPeriodClock(listOf(ClassPeriod(1, 10 * 60, 10 * 60)))
        assertNull(backwards.toWeeklyTimeBlockDraft(course(1, 1)))
    }

    @Test fun encoding_round_trips_and_damaged_storage_falls_back_to_default() {
        val clock = ClassPeriodClock(listOf(
            ClassPeriod(1, 8 * 60, 8 * 60 + 50),
            ClassPeriod(2, 9 * 60, 9 * 60 + 50),
        ))
        assertEquals(clock, ClassPeriodClock.decode(ClassPeriodClock.encode(clock)))
        assertEquals(ClassPeriodClock.DEFAULT, ClassPeriodClock.decode(null))
        assertEquals(ClassPeriodClock.DEFAULT, ClassPeriodClock.decode(""))
        assertEquals(ClassPeriodClock.DEFAULT, ClassPeriodClock.decode("480-525,oops"))
        assertEquals(ClassPeriodClock.DEFAULT, ClassPeriodClock.decode("525-480"))
    }

    @Test fun editing_a_period_keeps_numbers_sequential() {
        val clock = ClassPeriodClock(listOf(
            ClassPeriod(1, 8 * 60, 8 * 60 + 45),
            ClassPeriod(3, 10 * 60, 10 * 60 + 45),
        )).renumbered()
        assertEquals(listOf(1, 2), clock.periods.map(ClassPeriod::number))
        assertTrue(ClassPeriodClock.isValid(clock))
    }

    @Test fun course_blocks_on_old_period_boundaries_map_to_the_new_clock() {
        val old = ClassPeriodClock.DEFAULT
        val span = old.periodSpan(8 * 60, 9 * 60 + 40)
        assertEquals(1..2, span)
        val new = ClassPeriodClock(listOf(
            ClassPeriod(1, 9 * 60, 9 * 60 + 50),
            ClassPeriod(2, 10 * 60 + 10, 11 * 60),
        ))
        assertEquals(9 * 60 to 11 * 60, new.windowFor(span!!))
        assertNull(new.windowFor(1..3))
        assertNull(old.periodSpan(8 * 60 + 5, 9 * 60 + 40))
        assertNull(old.periodSpan(9 * 60 + 40, 8 * 60))
    }

    @Test fun empty_or_overlong_clocks_are_invalid() {
        assertFalse(ClassPeriodClock.isValid(ClassPeriodClock(emptyList())))
        val tooMany = ClassPeriodClock((1..13).map { ClassPeriod(it, 8 * 60, 8 * 60 + 45) })
        assertFalse(ClassPeriodClock.isValid(tooMany))
    }
}
