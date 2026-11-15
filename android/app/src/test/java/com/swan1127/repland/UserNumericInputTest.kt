package com.swan1127.repland

import com.swan1127.repland.domain.model.UserNumericInput
import com.swan1127.repland.domain.model.TaskPlacementPolicy
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UserNumericInputTest {
    @Test fun raw_input_is_never_filtered_trimmed_clamped_or_overflowed() {
        listOf("", "-3", "+3", "3.5", " 3", "3 ", "三", "３", "999999999999", "0", "13").forEach {
            assertNull(it, UserNumericInput.integerIn(it, 1..12))
        }
        assertEquals(3, UserNumericInput.integerIn("03", 1..12))
        assertEquals(12, UserNumericInput.integerIn("12", 1..12))
    }

    @Test fun clock_parts_cannot_carry_or_borrow() {
        listOf("99", "60", "-1", "+1", "1.5", "999999999999").forEach {
            assertNull(UserNumericInput.clockMinute("08", it))
        }
        assertNull(UserNumericInput.clockMinute("24", "00"))
        assertNull(UserNumericInput.clockMinute("-1", "59"))
        assertEquals(0, UserNumericInput.clockMinute("00", "00"))
        assertEquals(1439, UserNumericInput.clockMinute("23", "59"))
    }

    @Test fun placement_chunk_keeps_existing_policy_and_rejects_invalid_task_facts() {
        assertEquals(30, TaskPlacementPolicy.durationForTask(null))
        assertEquals(15, TaskPlacementPolicy.durationForTask(1))
        assertEquals(15, TaskPlacementPolicy.durationForTask(15))
        assertEquals(45, TaskPlacementPolicy.durationForTask(45))
        assertEquals(240, TaskPlacementPolicy.durationForTask(240))
        assertEquals(240, TaskPlacementPolicy.durationForTask(1440))
        listOf(-30, 0, 1441, Int.MAX_VALUE).forEach { assertNull(TaskPlacementPolicy.durationForTask(it)) }
    }

    /** 一键排入的时间是工具替用户挑的，不是用户点名的——得和对话助手一样把 23 点以后留给睡觉。 */
    @Test fun suggested_slot_never_runs_into_the_sleep_window() {
        val day = LocalDate.of(2026, 10, 27)
        fun fixed(start: Int, end: Int) = TimelineEntry("f$start", "固定事项", TimelineKind.COMMITMENT, day, start, end)

        assertEquals(8 * 60, TaskPlacementPolicy.nextFreeMinute(emptyList(), 8 * 60, 60))
        assertEquals(22 * 60, TaskPlacementPolicy.nextFreeMinute(emptyList(), 22 * 60, 60))
        assertNull(TaskPlacementPolicy.nextFreeMinute(emptyList(), 22 * 60 + 15, 60))
        assertNull(TaskPlacementPolicy.nextFreeMinute(listOf(fixed(8 * 60, 23 * 60)), 8 * 60, 60))
    }
}
