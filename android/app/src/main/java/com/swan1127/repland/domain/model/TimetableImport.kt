package com.swan1127.repland.domain.model

import java.time.DayOfWeek

/** A course recognized from a timetable PDF before the user confirms the import. */
data class ImportedCourse(
    val id: String,
    val title: String,
    val dayOfWeek: DayOfWeek,
    val startPeriod: Int,
    val endPeriod: Int,
    val weekPattern: String?,
)

/** 一节课在本校的真实钟点；[number] 是用户课表上的节次序号。 */
data class ClassPeriod(
    val number: Int,
    val startMinute: Int,
    val endMinute: Int,
) {
    fun isValid(): Boolean = number > 0 &&
        startMinute in 0 until MINUTES_PER_DAY &&
        endMinute in 1..MINUTES_PER_DAY &&
        startMinute < endMinute
}

/**
 * 用户学校的节次时间表。课表 PDF 只写"第几节"，导入必须按这套钟点换算；
 * 各校上课时间不同，所以它是持久化设置，而不是代码里的常量。
 */
data class ClassPeriodClock(val periods: List<ClassPeriod>) {
    fun period(number: Int): ClassPeriod? = periods.firstOrNull { it.number == number }

    fun toWeeklyTimeBlockDraft(course: ImportedCourse): WeeklyTimeBlockDraft? {
        val startMinute = period(course.startPeriod)?.startMinute ?: return null
        val endMinute = period(course.endPeriod)?.endMinute ?: return null
        if (startMinute >= endMinute) return null
        return WeeklyTimeBlockDraft(
            title = course.title,
            kind = TimeBlockKind.COURSE,
            dayOfWeek = course.dayOfWeek,
            startMinute = startMinute,
            endMinute = endMinute,
            weekPattern = course.weekPattern,
        )
    }

    /** 删除或插入节次后重新编号，保证序号始终从 1 连续。 */
    fun renumbered(): ClassPeriodClock =
        ClassPeriodClock(periods.mapIndexed { index, period -> period.copy(number = index + 1) })

    /** 已存课程是否正好落在本钟点的节次边界上；是则返回它占用的节次范围。 */
    fun periodSpan(startMinute: Int, endMinute: Int): IntRange? {
        val start = periods.firstOrNull { it.startMinute == startMinute } ?: return null
        val end = periods.firstOrNull { it.endMinute == endMinute } ?: return null
        return if (end.number >= start.number) start.number..end.number else null
    }

    /** 按节次范围取本钟点下的起止；节次不存在时返回 null。 */
    fun windowFor(span: IntRange): Pair<Int, Int>? {
        val startMinute = period(span.first)?.startMinute ?: return null
        val endMinute = period(span.last)?.endMinute ?: return null
        return if (startMinute < endMinute) startMinute to endMinute else null
    }

    companion object {
        const val MAX_PERIODS = 12

        /** 常见八节作息；用户可改每一节的起止，也可在末尾加到第十二节。 */
        val DEFAULT = ClassPeriodClock(
            listOf(
                ClassPeriod(1, 8 * 60, 8 * 60 + 45),
                ClassPeriod(2, 8 * 60 + 55, 9 * 60 + 40),
                ClassPeriod(3, 10 * 60, 10 * 60 + 45),
                ClassPeriod(4, 10 * 60 + 55, 11 * 60 + 40),
                ClassPeriod(5, 14 * 60, 14 * 60 + 45),
                ClassPeriod(6, 14 * 60 + 55, 15 * 60 + 40),
                ClassPeriod(7, 16 * 60, 16 * 60 + 45),
                ClassPeriod(8, 16 * 60 + 55, 17 * 60 + 40),
            ),
        )

        fun isValid(clock: ClassPeriodClock): Boolean =
            clock.periods.isNotEmpty() &&
                clock.periods.size <= MAX_PERIODS &&
                clock.periods.withIndex().all { (index, period) -> period.number == index + 1 && period.isValid() }

        fun encode(clock: ClassPeriodClock): String =
            clock.periods.joinToString(",") { "${it.startMinute}-${it.endMinute}" }

        /** Unknown or damaged storage must not block imports; fall back to the default clock. */
        fun decode(raw: String?): ClassPeriodClock {
            val tokens = raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            if (tokens.isEmpty()) return DEFAULT
            val windows = tokens.map { token ->
                val parts = token.split('-')
                if (parts.size != 2) return DEFAULT
                val start = parts[0].trim().toIntOrNull() ?: return DEFAULT
                val end = parts[1].trim().toIntOrNull() ?: return DEFAULT
                start to end
            }
            val clock = ClassPeriodClock(windows.mapIndexed { index, (start, end) ->
                ClassPeriod(index + 1, start, end)
            })
            return if (isValid(clock)) clock else DEFAULT
        }
    }
}

private const val MINUTES_PER_DAY = 24 * 60
