package com.swan1127.repland.domain.team.agents

import com.swan1127.repland.domain.team.ObjectionResolution
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamLedger
import com.swan1127.repland.domain.team.TeamPlanDraft
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 三席的确定性工具实现（§6）。
 * LLM 只做文秘；T1 离线模式下口语解析走确定性规则（§15 降级矩阵：没有 AI 也能用）。
 */

enum class UpdateKind(val label: String) {
    BUSY("有事请假"),
    DELAY("申请延后"),
    DONE("报告完成"),
    EARLY_DONE("提前完成"),
    TRANSFER_REQUEST("请求转派"),
    OBJECTION("一键异议"),
    NOTE("纯记录"),
}

/** 结构化请求：携带原话原样（AI 不写句子）。 */
data class StructuredRequest(
    val kind: UpdateKind,
    val memberId: String,
    val taskId: String?,
    val rawText: String,
    val detail: String,
    val busyDates: List<LocalDate> = emptyList(),
    val toMemberId: String? = null,
    val at: LocalDateTime,
)

object Liaison {

    private val DONE_WORDS = listOf("提前做完了", "提前完成", "做完了", "已完成", "完成了", "搞定")
    private val BUSY_WORDS = listOf("有事", "没空", "没时间", "冲突", "来不了", "占用")
    private val DELAY_WORDS = listOf("来不及", "做不完", "延期", "延后", "拖", "赶不上")
    private val TRANSFER_WORDS = listOf("转给", "换人", "转派", "帮我转")
    private val OBJECTION_WORDS = listOf("异议", "不同意", "不接受")

    /** 确定性口语解析：关键词 → 结构化请求；任务名 longest-match。 */
    fun parse(raw: String, memberId: String, tasks: List<TeamTask>, at: LocalDateTime, today: LocalDate): StructuredRequest {
        val text = raw.trim()
        val kind = when {
            OBJECTION_WORDS.any { it in text } -> UpdateKind.OBJECTION
            TRANSFER_WORDS.any { it in text } -> UpdateKind.TRANSFER_REQUEST
            DONE_WORDS.any { it in text } ->
                if ("提前" in text) UpdateKind.EARLY_DONE else UpdateKind.DONE
            BUSY_WORDS.any { it in text } -> UpdateKind.BUSY
            DELAY_WORDS.any { it in text } -> UpdateKind.DELAY
            else -> UpdateKind.NOTE
        }
        val task = tasks.filter { it.active && text.contains(it.title) }
            .maxByOrNull { it.title.length }
        return StructuredRequest(
            kind = kind,
            memberId = memberId,
            taskId = task?.taskId ?: fallbackTask(memberId, tasks),
            rawText = raw,
            detail = detailFor(kind),
            busyDates = parseBusyDates(text, today),
            at = at,
        )
    }

    /** 没点名任务 → 默认按发起人手头唯一的进行中任务处理（说事多半关于自己手头的活）。 */
    private fun fallbackTask(memberId: String, tasks: List<TeamTask>): String? {
        val mine = tasks.filter { it.assignedTo == memberId && it.active }
        return mine.singleOrNull()?.taskId
    }

    private fun detailFor(kind: UpdateKind): String = when (kind) {
        UpdateKind.BUSY -> "队员报备有事，申请把名下任务切走"
        UpdateKind.DELAY -> "队员预计来不及，申请延后"
        UpdateKind.DONE -> "队员报告任务完成"
        UpdateKind.EARLY_DONE -> "队员提前完成，释放的容量可补排他人任务"
        UpdateKind.TRANSFER_REQUEST -> "队员请求把任务转给他人"
        UpdateKind.OBJECTION -> "队员对转派一键异议"
        UpdateKind.NOTE -> "队员口语登记，仅入台账"
    }

    /** 「有事」落在哪几天：今天 / 明天 / 周X（确定性抽取）。 */
    fun parseBusyDates(text: String, today: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        if ("今天" in text) dates += today
        if ("明天" in text) dates += today.plusDays(1)
        if ("后天" in text) dates += today.plusDays(2)
        val weekdayNames = mapOf(
            "一" to DayOfWeek.MONDAY, "二" to DayOfWeek.TUESDAY, "三" to DayOfWeek.WEDNESDAY,
            "四" to DayOfWeek.THURSDAY, "五" to DayOfWeek.FRIDAY, "六" to DayOfWeek.SATURDAY,
            "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY,
        )
        Regex("周([一二三四五六日天])").findAll(text).forEach { match ->
            weekdayNames[match.groupValues[1]]?.let { dow ->
                var date = today
                while (date.dayOfWeek != dow) date = date.plusDays(1)
                dates += date
            }
        }
        return dates.distinct()
    }
}

object SchedulerOps {

    data class SimulatedTaskAdjustment(
        val excludeTaskIds: Set<String> = emptySet(),
        val extraRigidBlocks: List<PersonalBlock> = emptyList(),
        val voidedTaskIds: Set<String> = emptySet(),
    )

    /** 把结构化请求翻译成模拟调整（草案→确认纪律：runtime 不直接落库）。
     *  BUSY/DELAY：该任务旧计划作废；有事日期整天设为刚性忙，任务重新落到其余空闲。 */
    fun simulate(request: StructuredRequest?, state: TeamCollabState, today: LocalDate): SimulatedTaskAdjustment {
        if (request == null) return SimulatedTaskAdjustment()
        return when (request.kind) {
            UpdateKind.DONE, UpdateKind.EARLY_DONE ->
                SimulatedTaskAdjustment(excludeTaskIds = setOfNotNull(request.taskId))
            UpdateKind.BUSY, UpdateKind.DELAY -> {
                val voidedTaskIds = setOfNotNull(request.taskId)
                val busyRigid = request.busyDates.map { date ->
                    PersonalBlock(
                        blockId = "busy-" + UUID.randomUUID().toString().take(6),
                        memberId = request.memberId,
                        date = date,
                        startMinute = 0,
                        endMinute = 24 * 60,
                        sourceTeamId = null,
                        taskId = null,
                        visibility = com.swan1127.repland.domain.team.BlockVisibility.PRIVATE_BUSY,
                    )
                }
                SimulatedTaskAdjustment(
                    excludeTaskIds = emptySet(),
                    extraRigidBlocks = busyRigid,
                    voidedTaskIds = voidedTaskIds,
                )
            }
            else -> SimulatedTaskAdjustment()
        }
    }

    /** 确定性排期：负载定人 + 空闲定时段 + 依赖倒算 + 跨队冲突标红。 */
    fun scheduleOutput(
        state: TeamCollabState,
        today: LocalDate,
        adjustment: SimulatedTaskAdjustment,
        personalTime: com.swan1127.repland.domain.team.PersonalTimeInput? = null,
        actorId: String? = null,
    ): com.swan1127.repland.domain.team.TeamScheduler.ScheduleOutput {
        val tasks = state.tasks.filterNot { it.taskId in adjustment.excludeTaskIds }
        val voidedBlocks = state.blocks.filter { it.taskId in adjustment.voidedTaskIds && !it.date.isBefore(today) }
        val input = TeamSchedulerInputFactory.of(
            state, tasks, today, adjustment.extraRigidBlocks, voidedBlocks, personalTime, actorId,
        )
        val output = com.swan1127.repland.domain.team.TeamScheduler.schedule(input)
        val conflicts = output.draft.placements.filter { it.conflicts.isNotEmpty() }
        val notes = buildList {
            addAll(output.draft.notes)
            output.issues.filter { it.kind == com.swan1127.repland.domain.team.DeadlineEngine.IssueKind.OVER_COMPETITION_DEADLINE }
                .forEach { add(it.message) }
            addAll(com.swan1127.repland.domain.team.StagePlanner.notes(output.stageLoads))
            addAll(com.swan1127.repland.domain.team.GatePlanner.notes(output.gateReadiness))
            if (conflicts.isNotEmpty()) add("有 ${conflicts.size} 段安排与跨队/既有安排重叠，已标红待本人裁决")
        }
        return output.copy(draft = output.draft.copy(notes = notes))
    }

    fun schedule(
        state: TeamCollabState,
        today: LocalDate,
        adjustment: SimulatedTaskAdjustment,
        personalTime: com.swan1127.repland.domain.team.PersonalTimeInput? = null,
        actorId: String? = null,
    ): TeamPlanDraft = scheduleOutput(state, today, adjustment, personalTime, actorId).draft

    /** 会议候选：全员空闲交集；当天已过的时间段自动跳过。 */
    fun meetingCandidates(state: TeamCollabState, date: LocalDate, durationMinutes: Int): List<com.swan1127.repland.domain.team.MeetingSlot> =
        com.swan1127.repland.domain.team.TeamScheduler.meetingCandidates(
            members = state.members,
            existingBlocks = state.blocks,
            date = date,
            durationMinutes = durationMinutes,
            teamId = state.teamId,
            now = java.time.LocalDateTime.now(),
        )

    /** 多天会议建议：不用先指定哪天，直接给一周里约得上的时段（按接受度排序）。 */
    fun meetingSuggestions(
        state: TeamCollabState,
        from: LocalDate,
        durationMinutes: Int,
        days: Int = 7,
    ): List<com.swan1127.repland.domain.team.MeetingSuggestion> =
        com.swan1127.repland.domain.team.TeamScheduler.meetingSuggestions(
            members = state.members,
            existingBlocks = state.blocks,
            fromDate = from,
            days = days,
            durationMinutes = durationMinutes,
            now = java.time.LocalDateTime.now(),
        )
}

internal object TeamSchedulerInputFactory {
    fun of(
        state: TeamCollabState,
        tasks: List<TeamTask>,
        today: LocalDate,
        extraRigidBlocks: List<PersonalBlock>,
        voidedBlocks: List<PersonalBlock>,
        personalTime: com.swan1127.repland.domain.team.PersonalTimeInput? = null,
        actorId: String? = null,
        now: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ): com.swan1127.repland.domain.team.TeamScheduler.ScheduleInput {
        // 本地成员用真实课表与个人安排覆盖团队卡上的自报空闲；别人仍用各自的自报窗口。
        val members = if (personalTime != null && actorId != null) {
            state.members.map { member ->
                if (member.memberId != actorId) member
                else member.copy(
                    weeklyAvailability = personalTime.availability,
                    scheduleConfidence = if (personalTime.hasPersonalData) {
                        com.swan1127.repland.domain.team.ScheduleConfidence.HIGH
                    } else {
                        member.scheduleConfidence
                    },
                )
            }
        } else {
            state.members
        }
        return com.swan1127.repland.domain.team.TeamScheduler.ScheduleInput(
            teamId = state.teamId,
            tasks = tasks,
            members = members,
            phases = state.phases,
            existingBlocks = (state.blocks - voidedBlocks.toSet()) + extraRigidBlocks,
            startDate = today,
            competitionDeadline = state.competitionDeadline,
            now = if (today == now.toLocalDate()) now else null,
            personalBusy = personalTime?.busy.orEmpty(),
            personalLoadHours = if (personalTime != null && actorId != null) {
                mapOf(actorId to personalTime.loadHours)
            } else {
                emptyMap()
            },
        )
    }
}

object LedgerOps {

    /** 报告完成 → 拟议台账条目（口语留痕 + 状态流转），不直接落库。 */
    fun entriesForRequest(request: StructuredRequest, state: TeamCollabState): List<com.swan1127.repland.domain.team.LedgerEntry> {
        val task = request.taskId?.let { runCatching { state.task(it) }.getOrNull() } ?: return emptyList()
        return buildList {
            add(
                TeamLedger.recordSaying(
                    { "led-" + UUID.randomUUID().toString().take(8) },
                    task, request.memberId, request.rawText, request.at,
                ),
            )
            if (request.kind in listOf(UpdateKind.DONE, UpdateKind.EARLY_DONE)) {
                add(
                    TeamLedger.recordStatusChange(
                        { "led-" + UUID.randomUUID().toString().take(8) },
                        task, request.memberId, task.status, TeamTaskStatus.DONE, request.at,
                    ),
                )
            }
        }
    }

    /** 异常区：未排进 / 冲突标红 / 待裁决异议。 */
    fun collectExceptions(state: TeamCollabState, draft: TeamPlanDraft?): com.swan1127.repland.domain.team.runtime.TeamExceptions =
        com.swan1127.repland.domain.team.runtime.TeamExceptions(
            unassignedTaskIds = draft?.unassigned.orEmpty(),
            conflictPlacements = draft?.placements?.filter { it.conflicts.isNotEmpty() }.orEmpty(),
            openObjections = state.openObjections(),
            notes = draft?.notes.orEmpty(),
        )
}
