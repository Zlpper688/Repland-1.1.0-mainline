package com.swan1127.repland.domain.model

import java.time.LocalDate

/** A user-owned task. Status changes require explicit user evidence, including a clear language command. */
data class Task(
    val id: String,
    val description: String,
    val displayName: String,
    val category: TaskCategory,
    val userPriority: TaskPriority,
    val estimatedDays: Int?,
    val totalDurationMinutes: Int?,
    val dueDate: LocalDate?,
    val status: TaskStatus,
    val completionSummary: String?,
    val actualDurationMinutes: Int?,
    val progressPercent: Int?,
    val postponeCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completionResult: String? = null,
    /** The day the user intends to work on it; unlike [dueDate], this is not a deadline. */
    val scheduledForDate: LocalDate? = null,
    val inputSources: TaskInputSources = TaskInputSources.forInput(category, userPriority, estimatedDays, totalDurationMinutes, dueDate, scheduledForDate),
    /**
     * 团队任务在个人库里是一面镜子：唯一真相在团队域，个人侧只读展示与完成回传。
     * [originRef] 是 teamId，[originLabel] 是队名，用来在个人列表里标明来源。
     */
    val origin: TaskOrigin = TaskOrigin.PERSONAL,
    val originRef: String? = null,
    val originLabel: String? = null,
    /** 时间形态：决定这件事怎么算做完，以及要不要闹钟。 */
    val horizon: TaskHorizon = TaskHorizon.UNSPECIFIED,
    /** 短期立刻的时间点（当天第几分钟），与 [scheduledForDate] 合起来才是那一刻。 */
    val atMinute: Int? = null,
    /** 长期任务每天打算投多少分钟。 */
    val dailyInvestMinutes: Int? = null,
    val milestones: List<TaskMilestone> = emptyList(),
    /** 绿→红点尺的原始值 0..100；null 表示用户没选。 */
    val importance: Int? = null,
    val tags: List<String> = emptyList(),
) {
    val isTeamMirror: Boolean get() = origin == TaskOrigin.TEAM
}

enum class TaskOrigin { PERSONAL, TEAM }

/** 镜像 id 必须可反推、可重复生成：同一队同一条团队任务永远得到同一个个人任务 id。 */
object TeamTaskMirror {
    private const val PREFIX = "team"

    fun taskId(teamId: String, teamTaskId: String): String = "$PREFIX:$teamId:$teamTaskId"

    fun teamIdOf(mirrorTaskId: String): String? = mirrorTaskId.split(':').takeIf { it.size >= 3 && it[0] == PREFIX }?.get(1)

    fun teamTaskIdOf(mirrorTaskId: String): String? =
        mirrorTaskId.split(':', limit = 3).takeIf { it.size == 3 && it[0] == PREFIX }?.get(2)

    fun isMirror(taskId: String): Boolean = taskId.startsWith("$PREFIX:")
}

data class TaskDraft(
    val id: String? = null,
    val displayName: String = "",
    val description: String = "",
    val category: TaskCategory = TaskCategory.UNSPECIFIED,
    val userPriority: TaskPriority = TaskPriority.UNSPECIFIED,
    val estimatedDays: Int? = null,
    val totalDurationMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    /** Optional planned day, kept independent from a final deadline. */
    val scheduledForDate: LocalDate? = null,
    val inputSources: TaskInputSources = TaskInputSources.forInput(category, userPriority, estimatedDays, totalDurationMinutes, dueDate, scheduledForDate),
    val horizon: TaskHorizon = TaskHorizon.UNSPECIFIED,
    val atMinute: Int? = null,
    val dailyInvestMinutes: Int? = null,
    val milestones: List<TaskMilestone> = emptyList(),
    val importance: Int? = null,
    val tags: List<String> = emptyList(),
)

enum class TaskStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    POSTPONED,
    CANCELLED,
    REPLACED,
    ;

    val isActive: Boolean
        get() = this == NOT_STARTED || this == IN_PROGRESS || this == POSTPONED
}

enum class TaskPriority(val score: Int) {
    UNSPECIFIED(50),
    REQUIRED(100),
    HIGH(80),
    MEDIUM(50),
    LOW(20),
}

enum class TaskCategory(val defaultWeight: Int) {
    UNSPECIFIED(0),
    COURSE(30),
    EXTRACURRICULAR(25),
    OFFICE(25),
    LEISURE(20),
    ;

    companion object {
        val knownEntries: List<TaskCategory> = entries.filter { it != UNSPECIFIED }
    }
}

object TaskName {
    private const val MaxLength = 24

    fun fromDescription(description: String): String {
        val firstLine = description.trim().lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length <= MaxLength) firstLine else firstLine.take(MaxLength) + "…"
    }
}

object TaskDraftValidator {
    fun isValid(draft: TaskDraft): Boolean =
        (draft.displayName.isNotBlank() || draft.description.isNotBlank()) &&
            (draft.estimatedDays == null || draft.estimatedDays in 1..30) &&
            (draft.totalDurationMinutes == null || draft.totalDurationMinutes in 1..1_440) &&
            (draft.atMinute == null || draft.atMinute in 0..1_439) &&
            (draft.dailyInvestMinutes == null || draft.dailyInvestMinutes in 1..1_440) &&
            (draft.importance == null || draft.importance in TaskImportance.MIN..TaskImportance.MAX) &&
            // 说了"立刻"就得有那一刻，说了"长期"就得有每天投多久；否则验收标准是空的。
            (!TaskHorizonPolicy.needsMoment(draft.horizon) || (draft.scheduledForDate != null && draft.atMinute != null)) &&
            (!TaskHorizonPolicy.needsDailyInvest(draft.horizon) || draft.dailyInvestMinutes != null)
}
