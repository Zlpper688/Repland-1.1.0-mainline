package com.swan1127.repland.domain.team

import com.swan1127.repland.domain.model.CourseWeekPattern
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.SegmentAuthority
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskInputSources
import com.swan1127.repland.domain.model.TaskOrigin
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TeamTaskMirror
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException

data class TeamPersonalSyncReport(
    val mirroredTasks: Int = 0,
    val scheduledSegments: Int = 0,
    val skippedConflicts: Int = 0,
    val cancelledTasks: Int = 0,
) {
    val changed: Boolean get() = mirroredTasks + scheduledSegments + cancelledTasks > 0 || skippedConflicts > 0

    fun note(): String? = if (skippedConflicts > 0) {
        "有 $skippedConflicts 段团队安排与个人课表/个人安排冲突，暂未写入个人日程；请先调整个人时间再重排"
    } else {
        null
    }
}

/**
 * 团队域 → 个人域的单一桥。团队排期确认后，由它把团队任务和团队块写进个人任务库与个人日程，
 * 让「团队任务到个人展示」不是拷贝一份 UI，而是个人侧真的能看见、能完成。
 *
 * 纪律：唯一真相仍在团队域（任务状态、排期块）；个人侧只是镜像 + 完成回传。
 */
interface TeamPersonalBridge {
    suspend fun sync(
        state: TeamCollabState,
        actorId: String,
        today: LocalDate = LocalDate.now(),
    ): TeamPersonalSyncReport

    /**
     * 个人侧把镜像任务标记完成后回传团队（团队状态仍是真相）。
     * 未配置团队库、队伍已归档或任务已结束时返回 false，个人侧照常保留完成记录。
     */
    suspend fun reportCompletion(
        teamId: String,
        teamTaskId: String,
        summary: String?,
        at: LocalDateTime = LocalDateTime.now(),
    ): Boolean = false
}

/**
 * 个人侧的任一完成入口（界面、助手对话、专注会话）共用这一句回传。
 * 只对镜像任务生效；回传失败不阻断个人写入——个人完成已落库，团队页下一次同步会补齐。
 */
suspend fun TeamPersonalBridge?.reportMirrorCompletion(mirrorTaskId: String, summary: String?) {
    val bridge = this ?: return
    val teamId = TeamTaskMirror.teamIdOf(mirrorTaskId) ?: return
    val teamTaskId = TeamTaskMirror.teamTaskIdOf(mirrorTaskId) ?: return
    try {
        bridge.reportCompletion(teamId, teamTaskId, summary)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}

/** 本地成员真实时间的快照；只保留分钟区间与负载，内容不进入任何团队数据。 */
data class PersonalTimeInput(
    val availability: Map<DayOfWeek, List<FreeWindow>>,
    val busy: List<PersonalBlock>,
    val loadHours: Double,
    val hasPersonalData: Boolean,
)

/** 纯映射，便于单测；不读写数据库。 */
object TeamPersonalMirror {

    fun shouldMirror(state: TeamCollabState, task: TeamTask, actorId: String): Boolean =
        task.status != TeamTaskStatus.WONT && (task.assignedTo.isNullOrBlank() || task.assignedTo == actorId)

    fun tasks(state: TeamCollabState, actorId: String, nowMillis: Long): List<Task> =
        state.tasks
            .filter { shouldMirror(state, it, actorId) }
            .map { task -> task(state, task, nowMillis) }

    fun segments(state: TeamCollabState, actorId: String, today: LocalDate): List<PlannedSegment> {
        val mirrored = state.tasks
            .filter { shouldMirror(state, it, actorId) }
            .associateBy { it.taskId }
        return state.blocks.mapNotNull { block ->
            if (block.sourceTeamId != state.teamId || block.memberId != actorId) return@mapNotNull null
            if (block.date.isBefore(today)) return@mapNotNull null
            val taskId = block.taskId ?: return@mapNotNull null
            val task = mirrored[taskId]?.takeIf { it.active } ?: return@mapNotNull null
            PlannedSegment(
                id = "team-seg-${block.blockId}",
                taskId = TeamTaskMirror.taskId(state.teamId, task.taskId),
                date = block.date,
                startMinute = block.startMinute,
                endMinute = block.endMinute,
                isLocked = true,
                trackId = TEAM_TRACK_ID,
                // 团队排好的时间对成员是硬约定：镜像进个人日程默认带提醒，提前量与飞书日历侧一致。
                reminderEnabled = true,
                leadMinutes = TeamReminderDefaults.leadMinutes,
                authority = SegmentAuthority.USER_PINNED,
                stableKey = "team:${state.teamId}:${task.taskId}:${block.date}:${block.startMinute}:${block.endMinute}",
            )
        }.distinctBy { it.stableKey }
    }

    /**
     * 把个人课表、个人已确认安排折算成团队排期能用的「在忙」。
     * 本队的团队块由 state.blocks 提供，这里剔除，避免同一段时间被算两遍。
     */
    fun busyTime(
        actorId: String,
        teamId: String,
        today: LocalDate,
        horizonDays: Int,
        weeklyBlocks: List<WeeklyTimeBlock>,
        dateOverrides: List<DateOverride>,
        segments: List<PlannedSegment>,
        semesterFirstWeekMonday: LocalDate?,
    ): PersonalTimeInput {
        val busy = mutableListOf<PersonalBlock>()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            weeklyBlocks
                .filter {
                    it.kind != TimeBlockKind.AVAILABLE && it.dayOfWeek == date.dayOfWeek &&
                        CourseWeekPattern.appliesOn(it.weekPattern, date, semesterFirstWeekMonday)
                }
                .forEach { block ->
                    busy += PersonalBlock(
                        blockId = "personal-course-${block.id}-$date",
                        memberId = actorId,
                        date = date,
                        startMinute = block.startMinute,
                        endMinute = block.endMinute,
                        sourceTeamId = null,
                        taskId = null,
                        visibility = BlockVisibility.PRIVATE_BUSY,
                        countsTowardCapacity = false,
                    )
                }
            dateOverrides
                .filter { it.date == date && it.type == DateOverrideType.BLOCKED }
                .forEach { override ->
                    busy += PersonalBlock(
                        blockId = "personal-blocked-${override.id}-$date",
                        memberId = actorId,
                        date = date,
                        startMinute = override.startMinute,
                        endMinute = override.endMinute,
                        sourceTeamId = null,
                        taskId = null,
                        visibility = BlockVisibility.PRIVATE_BUSY,
                        countsTowardCapacity = false,
                    )
                }
            segments
                .filter { it.date == date && TeamTaskMirror.teamIdOf(it.taskId) != teamId }
                .forEach { segment ->
                    busy += PersonalBlock(
                        blockId = "personal-seg-${segment.id.ifBlank { segment.stableKey }}-$date",
                        memberId = actorId,
                        date = date,
                        startMinute = segment.startMinute,
                        endMinute = segment.endMinute,
                        sourceTeamId = null,
                        taskId = null,
                        visibility = BlockVisibility.PRIVATE_BUSY,
                        countsTowardCapacity = true,
                    )
                }
        }
        val weekEnd = today.plusDays(6)
        val loadHours = busy
            .filter { it.countsTowardCapacity && !it.date.isAfter(weekEnd) }
            .sumOf { (it.endMinute - it.startMinute).toDouble() } / 60.0
        return PersonalTimeInput(
            availability = DayOfWeek.values().associateWith { listOf(FreeWindow(8 * 60, 22 * 60)) },
            busy = busy,
            loadHours = loadHours,
            hasPersonalData = weeklyBlocks.isNotEmpty() || dateOverrides.isNotEmpty() || segments.isNotEmpty(),
        )
    }

    private fun task(state: TeamCollabState, source: TeamTask, nowMillis: Long): Task {
        val phase = source.phaseId?.let { id -> state.phases.firstOrNull { it.phaseId == id }?.name }
        val assignee = source.assignedTo?.let { id -> state.members.firstOrNull { it.memberId == id }?.name }
        val description = buildString {
            append("第二课堂 · ").append(state.teamName)
            phase?.let { append(" · ").append(it) }
            append(" · ").append(assignee?.let { "负责：$it" } ?: "待认领")
        }
        val status = when (source.status) {
            TeamTaskStatus.NOT_STARTED -> TaskStatus.NOT_STARTED
            TeamTaskStatus.IN_PROGRESS -> TaskStatus.IN_PROGRESS
            TeamTaskStatus.DONE -> TaskStatus.COMPLETED
            TeamTaskStatus.WONT -> TaskStatus.CANCELLED
        }
        val priority = when (source.rank) {
            TaskRank.MUST -> TaskPriority.REQUIRED
            TaskRank.SHOULD -> TaskPriority.HIGH
            TaskRank.COULD -> TaskPriority.MEDIUM
            TaskRank.WONT -> TaskPriority.LOW
        }
        val minutes = (source.estimatedHours * 60).toInt().takeIf { it > 0 }
        val due = source.effectiveDeadline(state.phases, state.competitionDeadline)
        return Task(
            id = TeamTaskMirror.taskId(state.teamId, source.taskId),
            description = description,
            displayName = source.title,
            category = TaskCategory.EXTRACURRICULAR,
            userPriority = priority,
            estimatedDays = null,
            totalDurationMinutes = minutes,
            dueDate = due,
            status = status,
            completionSummary = null,
            actualDurationMinutes = null,
            progressPercent = null,
            postponeCount = 0,
            createdAtEpochMillis = nowMillis,
            updatedAtEpochMillis = nowMillis,
            completionResult = null,
            scheduledForDate = null,
            inputSources = TaskInputSources.forInput(
                TaskCategory.EXTRACURRICULAR, priority, null, minutes, due, null,
            ),
            origin = TaskOrigin.TEAM,
            originRef = state.teamId,
            originLabel = state.teamName,
        )
    }

    const val TEAM_TRACK_ID = "team"
}

/** 团队侧提醒统一口径：镜像段的响铃提前量与飞书日历的提醒分钟数保持一致。 */
object TeamReminderDefaults {
    const val leadMinutes = 10
}
