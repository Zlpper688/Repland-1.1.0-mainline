package com.swan1127.repland.domain.ports

import com.swan1127.repland.domain.model.ClassPeriodClock
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideDraft
import com.swan1127.repland.domain.model.TimeConstraintSettings
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface TimeRepository {
    fun observeWeeklyBlocks(): Flow<List<WeeklyTimeBlock>>

    fun observeDateOverrides(): Flow<List<DateOverride>>

    /** Advances for every user-confirmed change to planning constraints, including deletes. */
    fun observeTimeConstraintSettings(): Flow<TimeConstraintSettings>

    /** 本校节次时间表；未设置时返回默认八节作息。 */
    fun observeClassPeriodClock(): Flow<ClassPeriodClock>

    /** [retimeCourses] 为真时，仍与旧节次边界完全一致的课程会一起改到新节次时间。 */
    suspend fun saveClassPeriodClock(clock: ClassPeriodClock, retimeCourses: Boolean = true)

    suspend fun saveWeeklyBlock(draft: WeeklyTimeBlockDraft)

    suspend fun saveWeeklyBlocks(drafts: List<WeeklyTimeBlockDraft>)

    /**
     * 一次写入里替换掉旧周模板再写新的；默认实现留给轻量 fake，Room 侧覆盖为单事务。
     */
    suspend fun replaceWeeklyBlocks(removeIds: List<String>, drafts: List<WeeklyTimeBlockDraft>) {
        removeIds.forEach { deleteWeeklyBlock(it) }
        if (drafts.isNotEmpty()) saveWeeklyBlocks(drafts)
    }

    /** Confirmed import: deduplicate against the current store and advance constraints atomically. */
    suspend fun importWeeklyBlocks(drafts: List<WeeklyTimeBlockDraft>): Int

    suspend fun deleteWeeklyBlock(id: String)

    suspend fun saveDateOverride(draft: DateOverrideDraft)

    suspend fun deleteDateOverride(id: String)

    suspend fun saveSemesterFirstWeekMonday(date: LocalDate?)

    /** Per-event alarm switches; defaults keep lightweight fakes compiling. */
    suspend fun setWeeklyBlockReminder(id: String, enabled: Boolean) = Unit

    suspend fun setDateOverrideReminder(id: String, enabled: Boolean) = Unit
}
