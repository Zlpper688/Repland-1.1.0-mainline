package com.swan1127.repland.domain.model

import java.time.LocalDate

/** Explicitly confirmed minimal input; an empty schedule is never assumed to be free. */
data class QuickAvailability(
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val repeatWeekly: Boolean = false,
    /** false 表示这段时间锁死（固定安排），不许往这儿排事。 */
    val free: Boolean = true,
    /** 用户给这段起的名字；没起就用界面一直在用的那个默认名。 */
    val title: String? = null,
) {
    fun isValid(today: LocalDate = LocalDate.now()) = !date.isBefore(today) &&
        startMinute in 0 until 1440 && endMinute in 1..1440 && startMinute < endMinute
}
