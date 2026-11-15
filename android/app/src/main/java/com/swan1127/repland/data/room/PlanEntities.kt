package com.swan1127.repland.data.room

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.swan1127.repland.domain.model.ConfirmedPlan
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.SegmentAuthority
import java.time.LocalDate
import java.util.UUID

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMillis: Long,
    val isCurrent: Boolean,
)

@Entity(
    tableName = "plan_segments",
    indices = [Index(value = ["planId"])],
)
data class PlanSegmentEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val taskId: String,
    val dateEpochDay: Long,
    val startMinute: Int,
    val endMinute: Int,
    val isLocked: Boolean = false,
    val trackId: String = "focus",
    val reminderEnabled: Boolean = false,
    val leadMinutes: Int? = null,
    val authority: String = SegmentAuthority.AUTO.name,
    val stableKey: String = "",
)

/** The explicit user-reviewed task order that belongs to one immutable plan version. */
@Entity(
    tableName = "plan_task_order",
    primaryKeys = ["planId", "taskId"],
    indices = [Index(value = ["planId"])],
)
data class PlanTaskOrderEntity(
    val planId: String,
    val taskId: String,
    val position: Int,
    val isManual: Boolean = false,
)

data class PlanWithSegments(
    @Embedded val plan: PlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId")
    val segments: List<PlanSegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "planId")
    val taskOrder: List<PlanTaskOrderEntity>,
)

/**
 * 行 id 每次重写都换新（旧行留给历史版本）；stableKey 跨重写延续，自动调整的对比靠它认出「同一段」。
 */
internal fun PlannedSegment.toSegmentEntity(planId: String, rowId: String = UUID.randomUUID().toString()): PlanSegmentEntity =
    PlanSegmentEntity(
        id = rowId,
        planId = planId,
        taskId = taskId,
        dateEpochDay = date.toEpochDay(),
        startMinute = startMinute,
        endMinute = endMinute,
        isLocked = isLocked,
        trackId = trackId,
        reminderEnabled = reminderEnabled,
        leadMinutes = leadMinutes,
        authority = authority.name,
        stableKey = stableKey.ifBlank { id.ifBlank { rowId } },
    )

fun PlanWithSegments.toDomain(): ConfirmedPlan = ConfirmedPlan(
    id = plan.id,
    createdAtEpochMillis = plan.createdAtEpochMillis,
    isCurrent = plan.isCurrent,
    segments = segments.map { segment ->
        PlannedSegment(
            id = segment.id,
            taskId = segment.taskId,
            date = LocalDate.ofEpochDay(segment.dateEpochDay),
            startMinute = segment.startMinute,
            endMinute = segment.endMinute,
            isLocked = segment.isLocked,
            trackId = segment.trackId,
            reminderEnabled = segment.reminderEnabled,
            leadMinutes = segment.leadMinutes,
            authority = segment.authority.let { raw ->
                SegmentAuthority.entries.firstOrNull { it.name == raw } ?: SegmentAuthority.AUTO
            },
            stableKey = segment.stableKey,
        )
    }.sortedWith(compareBy(PlannedSegment::date, PlannedSegment::startMinute)),
    orderedTaskIds = taskOrder
        .sortedBy(PlanTaskOrderEntity::position)
        .map(PlanTaskOrderEntity::taskId)
        .ifEmpty { segments.map(PlanSegmentEntity::taskId).distinct() },
    hasManualTaskOrder = taskOrder.any(PlanTaskOrderEntity::isManual),
)
