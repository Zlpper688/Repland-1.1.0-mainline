package com.swan1127.repland.data.room

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskHorizon
import com.swan1127.repland.domain.model.TaskMilestones
import com.swan1127.repland.domain.model.TaskOrigin
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TaskTags
import com.swan1127.repland.domain.model.TaskInputSources
import java.time.LocalDate

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val description: String,
    val displayName: String,
    val category: String,
    val userPriority: String,
    val estimatedDays: Int?,
    val totalDurationMinutes: Int?,
    val dueDateEpochDay: Long?,
    val status: String,
    val completionSummary: String?,
    val actualDurationMinutes: Int?,
    val progressPercent: Int?,
    val postponeCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completionResult: String? = null,
    val scheduledForEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "''") val inputSources: String = "",
    @ColumnInfo(defaultValue = "PERSONAL") val origin: String = "PERSONAL",
    val originRef: String? = null,
    val originLabel: String? = null,
    @ColumnInfo(defaultValue = "UNSPECIFIED") val horizon: String = "UNSPECIFIED",
    val atMinute: Int? = null,
    val dailyInvestMinutes: Int? = null,
    @ColumnInfo(defaultValue = "''") val milestones: String = "",
    val importance: Int? = null,
    @ColumnInfo(defaultValue = "''") val tags: String = "",
)

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    description = description,
    displayName = displayName,
    category = TaskCategory.valueOf(category),
    userPriority = TaskPriority.valueOf(userPriority),
    estimatedDays = estimatedDays,
    totalDurationMinutes = totalDurationMinutes,
    dueDate = dueDateEpochDay?.let(LocalDate::ofEpochDay),
    status = TaskStatus.valueOf(status),
    completionSummary = completionSummary,
    actualDurationMinutes = actualDurationMinutes,
    progressPercent = progressPercent,
    postponeCount = postponeCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    completionResult = completionResult,
    scheduledForDate = scheduledForEpochDay?.let(LocalDate::ofEpochDay),
    inputSources = TaskInputSources.decode(inputSources),
    origin = TaskOrigin.entries.firstOrNull { it.name == origin } ?: TaskOrigin.PERSONAL,
    originRef = originRef,
    originLabel = originLabel,
    horizon = TaskHorizon.entries.firstOrNull { it.name == horizon } ?: TaskHorizon.UNSPECIFIED,
    atMinute = atMinute,
    dailyInvestMinutes = dailyInvestMinutes,
    milestones = TaskMilestones.decode(milestones),
    importance = importance,
    tags = TaskTags.decode(tags),
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    description = description,
    displayName = displayName,
    category = category.name,
    userPriority = userPriority.name,
    estimatedDays = estimatedDays,
    totalDurationMinutes = totalDurationMinutes,
    dueDateEpochDay = dueDate?.toEpochDay(),
    status = status.name,
    completionSummary = completionSummary,
    actualDurationMinutes = actualDurationMinutes,
    progressPercent = progressPercent,
    postponeCount = postponeCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    completionResult = completionResult,
    scheduledForEpochDay = scheduledForDate?.toEpochDay(),
    inputSources = inputSources.encode(),
    origin = origin.name,
    originRef = originRef,
    originLabel = originLabel,
    horizon = horizon.name,
    atMinute = atMinute,
    dailyInvestMinutes = dailyInvestMinutes,
    milestones = TaskMilestones.encode(milestones),
    importance = importance,
    tags = TaskTags.encode(tags),
)
