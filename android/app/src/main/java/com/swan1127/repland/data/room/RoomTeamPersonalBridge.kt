package com.swan1127.repland.data.room

import androidx.room.withTransaction
import com.swan1127.repland.data.team.TeamSessionStore
import com.swan1127.repland.domain.model.CourseWeekPattern
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.team.LedgerEntry
import com.swan1127.repland.domain.team.LedgerKind
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamLedger
import com.swan1127.repland.domain.team.TeamPersonalBridge
import com.swan1127.repland.domain.team.TeamPersonalMirror
import com.swan1127.repland.domain.team.TeamPersonalSyncReport
import com.swan1127.repland.domain.team.TeamTaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 团队 → 个人的落库实现。任务镜像与个人日程在同一条写闸门里完成：
 * 手动排期、对话写入、团队同步共用一个写序列，避免互相覆盖。
 */
class RoomTeamPersonalBridge(
    private val database: ReplandDatabase,
    private val writeGate: PlanWriteGate,
    private val teamStore: TeamSessionStore? = null,
) : TeamPersonalBridge {

    override suspend fun sync(
        state: TeamCollabState,
        actorId: String,
        today: LocalDate,
    ): TeamPersonalSyncReport = writeGate.serialize { syncLocked(state, actorId, today) }

    override suspend fun reportCompletion(
        teamId: String,
        teamTaskId: String,
        summary: String?,
        at: LocalDateTime,
    ): Boolean = writeGate.serialize {
        val state = teamStore?.read(teamId) ?: return@serialize false
        val task = state.tasks.firstOrNull { it.taskId == teamTaskId } ?: return@serialize false
        if (task.status == TeamTaskStatus.DONE) return@serialize true
        if (state.archived || task.status == TeamTaskStatus.WONT) return@serialize false
        val actorId = task.assignedTo?.takeIf { id -> state.members.any { it.memberId == id } }
            ?: state.members.firstOrNull()?.memberId
        if (actorId != null) {
            state.updateTask(task.copy(status = TeamTaskStatus.DONE))
            state.ledger += TeamLedger.recordStatusChange(
                { "led-" + UUID.randomUUID().toString().take(8) },
                task,
                actorId,
                task.status,
                TeamTaskStatus.DONE,
                at,
            )
            if (summary?.isNotBlank() == true) {
                state.ledger += LedgerEntry(
                    entryId = "led-" + UUID.randomUUID().toString().take(8),
                    kind = LedgerKind.EXECUTION_BLOCK,
                    taskId = task.taskId,
                    memberId = actorId,
                    at = at,
                    evidence = summary.trim(),
                    isBaseline = task.isBaseline,
                )
            }
            teamStore.save(state)
            syncLocked(state, actorId, at.toLocalDate())
            true
        } else {
            state.updateTask(task.copy(status = TeamTaskStatus.DONE))
            teamStore.save(state)
            false
        }
    }

    private suspend fun syncLocked(
        state: TeamCollabState,
        actorId: String,
        today: LocalDate,
    ): TeamPersonalSyncReport {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val taskDao = database.taskDao()
            val existing = taskDao.getAll()
            val existingById = existing.associateBy { it.id }
            val desired = TeamPersonalMirror.tasks(state, actorId, now)
            val desiredIds = desired.map { it.id }.toSet()

            var mirrored = 0
            desired.forEach { wanted ->
                val old = existingById[wanted.id]?.toDomain()
                val merged = merge(wanted, old, now)
                if (old == null || merged != old) {
                    taskDao.insert(merged.toEntity())
                    mirrored += 1
                }
            }

            var cancelled = 0
            existing
                .filter { it.origin == com.swan1127.repland.domain.model.TaskOrigin.TEAM.name && it.originRef == state.teamId }
                .filter { it.id !in desiredIds }
                .forEach { stale ->
                    val domain = stale.toDomain()
                    if (domain.status != TaskStatus.COMPLETED && domain.status != TaskStatus.CANCELLED) {
                        taskDao.insert(domain.copy(status = TaskStatus.CANCELLED, updatedAtEpochMillis = now).toEntity())
                        cancelled += 1
                    }
                }

            val source = database.planDao().getCurrentPlanWithSegments()
            val currentPlan = source?.toDomain()
            val currentSegments = currentPlan?.segments.orEmpty()
            val ownPrefix = "team:${state.teamId}:"
            val preserved = currentSegments.filterNot { segment ->
                !segment.date.isBefore(today) && (segment.taskId.startsWith(ownPrefix) ||
                    existingById[segment.taskId]?.originRef == state.teamId)
            }

            val weekly = database.timeDao().getAllWeeklyBlocks().map { it.toDomain() }
            val overrides = database.timeDao().getAllDateOverrides().map { it.toDomain() }
            val firstMonday = database.timeDao().getSemesterSettings()?.firstWeekMondayEpochDay?.let(LocalDate::ofEpochDay)
            val desiredSegments = TeamPersonalMirror.segments(state, actorId, today)
            val occupied = preserved.toMutableList()
            val accepted = mutableListOf<PlannedSegment>()
            var skipped = 0
            desiredSegments
                .sortedWith(compareBy(PlannedSegment::date, PlannedSegment::startMinute, PlannedSegment::stableKey))
                .forEach { segment ->
                    if (blockedByPersonalTime(segment, weekly, overrides, firstMonday) ||
                        occupied.any { it.date == segment.date && overlaps(it, segment) }
                    ) {
                        skipped += 1
                    } else {
                        occupied += segment
                        accepted += segment
                    }
                }

            val removed = currentSegments.size - preserved.size
            if (removed > 0 || accepted.isNotEmpty()) {
                val nextId = UUID.randomUUID().toString()
                val createdAt = maxOf(now, (database.planDao().latestCreatedAt() ?: now) + 1)
                val order = (currentPlan?.orderedTaskIds.orEmpty() +
                    preserved.map { it.taskId } + accepted.map { it.taskId } + desiredIds).distinct()
                val priorManual = source?.taskOrder?.associate { it.taskId to it.isManual }.orEmpty()
                database.planDao().replaceCurrentPlan(
                    PlanEntity(nextId, createdAt, true),
                    (preserved + accepted).map { it.toSegmentEntity(nextId) },
                    order.mapIndexed { index, taskId ->
                        PlanTaskOrderEntity(nextId, taskId, index, priorManual[taskId] ?: false)
                    },
                )
            }

            TeamPersonalSyncReport(
                mirroredTasks = mirrored,
                scheduledSegments = accepted.size,
                skippedConflicts = skipped,
                cancelledTasks = cancelled,
            )
        }
    }

    /**
     * 团队状态是真相，个人状态只能往前推：个人已开始/已完成不会被团队旧状态倒灌；
     * 个人侧手动改过的完成记录、实际时长和计划日都保留。
     */
    private fun merge(wanted: Task, old: Task?, now: Long): Task {
        if (old == null) return wanted
        val status = when {
            wanted.status == TaskStatus.COMPLETED || old.status == TaskStatus.COMPLETED -> TaskStatus.COMPLETED
            old.status == TaskStatus.IN_PROGRESS && wanted.status == TaskStatus.NOT_STARTED -> TaskStatus.IN_PROGRESS
            else -> wanted.status
        }
        // 先按原时间戳比较内容：团队没变时不能每轮同步都刷新 updatedAt 假装改过一次。
        val candidate = wanted.copy(
            status = status,
            completionSummary = old.completionSummary,
            actualDurationMinutes = old.actualDurationMinutes,
            progressPercent = old.progressPercent,
            postponeCount = old.postponeCount,
            createdAtEpochMillis = old.createdAtEpochMillis,
            updatedAtEpochMillis = old.updatedAtEpochMillis,
            completionResult = old.completionResult,
            scheduledForDate = old.scheduledForDate,
            inputSources = old.inputSources,
        )
        return if (candidate == old) old else candidate.copy(updatedAtEpochMillis = now)
    }

    private fun blockedByPersonalTime(
        segment: PlannedSegment,
        weekly: List<WeeklyTimeBlock>,
        overrides: List<DateOverride>,
        firstMonday: LocalDate?,
    ): Boolean {
        val blockedByWeek = weekly.any { block ->
            block.kind != TimeBlockKind.AVAILABLE && block.dayOfWeek == segment.date.dayOfWeek &&
                CourseWeekPattern.appliesOn(block.weekPattern, segment.date, firstMonday) &&
                overlaps(block.startMinute, block.endMinute, segment.startMinute, segment.endMinute)
        }
        val blockedByOverride = overrides.any { override ->
            override.type == DateOverrideType.BLOCKED && override.date == segment.date &&
                overlaps(override.startMinute, override.endMinute, segment.startMinute, segment.endMinute)
        }
        return blockedByWeek || blockedByOverride
    }

    private fun overlaps(a: PlannedSegment, b: PlannedSegment): Boolean =
        a.startMinute < b.endMinute && b.startMinute < a.endMinute

    private fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart < bEnd && bStart < aEnd
}
