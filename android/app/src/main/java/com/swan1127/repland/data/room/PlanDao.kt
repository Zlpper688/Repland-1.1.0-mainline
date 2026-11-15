package com.swan1127.repland.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Transaction
    @Query("SELECT * FROM plans WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrentPlan(): Flow<PlanWithSegments?>

    @Transaction
    @Query("SELECT * FROM plans WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrentPlanWithSegments(): PlanWithSegments?

    @Transaction
    @Query("SELECT * FROM plans ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observePlanHistory(): Flow<List<PlanWithSegments>>

    @Transaction
    @Query("SELECT * FROM plans ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun getAllPlansWithSegments(): List<PlanWithSegments>

    @Transaction
    @Query("SELECT * FROM plans WHERE id = :id LIMIT 1")
    suspend fun getPlanWithSegments(id: String): PlanWithSegments?

    @Query("SELECT MAX(createdAtEpochMillis) FROM plans")
    suspend fun latestCreatedAt(): Long?

    @Query("UPDATE plans SET isCurrent = 0 WHERE isCurrent = 1")
    suspend fun archiveCurrentPlan()

    @Query(
        """
        UPDATE plan_segments
        SET isLocked = :isLocked
        WHERE id = :segmentId
            AND planId IN (SELECT id FROM plans WHERE isCurrent = 1)
        """,
    )
    suspend fun setSegmentLocked(segmentId: String, isLocked: Boolean)

    @Query(
        """
        UPDATE plan_segments
        SET reminderEnabled = :enabled, leadMinutes = coalesce(:leadMinutes, leadMinutes)
        WHERE id = :segmentId
            AND planId IN (SELECT id FROM plans WHERE isCurrent = 1)
        """,
    )
    suspend fun setSegmentReminder(segmentId: String, enabled: Boolean, leadMinutes: Int? = null)

    /** Restore path for [com.swan1127.repland.data.room.AgentWriteCaretaker]: old plan rows are never deleted, so undoing a batch is a flag flip. */
    @Query("UPDATE plans SET isCurrent = 1 WHERE id = :planId")
    suspend fun setCurrentPlan(planId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlan(plan: PlanEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(segments: List<PlanSegmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskOrder(taskOrder: List<PlanTaskOrderEntity>)

    @Transaction
    suspend fun replaceCurrentPlan(
        plan: PlanEntity,
        segments: List<PlanSegmentEntity>,
        taskOrder: List<PlanTaskOrderEntity>,
    ) {
        archiveCurrentPlan()
        insertPlan(plan)
        if (segments.isNotEmpty()) insertSegments(segments)
        if (taskOrder.isNotEmpty()) insertTaskOrder(taskOrder)
    }
}
