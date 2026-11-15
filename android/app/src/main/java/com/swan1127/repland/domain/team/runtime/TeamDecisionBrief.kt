package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPlanDraft
import java.time.LocalDate

/**
 * 把草案/待确认动作里「用户做决定需要的信息」算出来强调：
 * 改什么（任务名+工时+负责人前后）、影响谁（每人负载）、风险（冲突/低置信度/未排进）、后果分级。
 * 纯只读计算，UI 直接消费，可单测。
 */
object TeamDecisionBrief {

    data class MemberLoad(
        val memberId: String,
        val name: String,
        val addedHours: Double,
        val lowConfidence: Boolean,
    )

    data class PlacementLine(
        val taskTitle: String,
        val memberName: String,
        val date: LocalDate,
        val startMinute: Int,
        val endMinute: Int,
        val hours: Double,
        val conflict: Boolean,
        val lowConfidence: Boolean,
        /** 负责人变化，如「张三→李四」；不变为 null。确认草案会写回分工，必须明示。 */
        val assigneeChange: String?,
    )

    data class DraftBrief(
        val totalHours: Double,
        val memberLoads: List<MemberLoad>,
        val lines: List<PlacementLine>,
        val unassignedTitles: List<String>,
        val conflictCount: Int,
        val reassignCount: Int,
    )

    fun of(draft: TeamPlanDraft, state: TeamCollabState): DraftBrief {
        val taskById = state.tasks.associateBy { it.taskId }
        val memberById = state.members.associateBy { it.memberId }
        val lines = draft.placements.map { placement ->
            val task = taskById[placement.taskId]
            val member = memberById[placement.memberId]
            val from = task?.assignedTo
            val change = when {
                from == placement.memberId -> null
                from == null -> "待认领→${member?.name ?: placement.memberId}"
                else -> "${memberById[from]?.name ?: from}→${member?.name ?: placement.memberId}"
            }
            PlacementLine(
                taskTitle = task?.title ?: placement.taskId,
                memberName = member?.name ?: placement.memberId,
                date = placement.date,
                startMinute = placement.startMinute,
                endMinute = placement.endMinute,
                hours = placement.minutes / 60.0,
                conflict = placement.conflicts.isNotEmpty(),
                lowConfidence = placement.lowConfidence,
                assigneeChange = change,
            )
        }
        val loads = draft.placements.groupBy { it.memberId }.map { (memberId, placements) ->
            val member = memberById[memberId]
            MemberLoad(
                memberId = memberId,
                name = member?.name ?: memberId,
                addedHours = placements.sumOf { it.minutes } / 60.0,
                lowConfidence = member?.lowConfidence == true,
            )
        }.sortedByDescending { it.addedHours }
        return DraftBrief(
            totalHours = draft.placements.sumOf { it.minutes } / 60.0,
            memberLoads = loads,
            lines = lines,
            unassignedTitles = draft.unassigned.map { taskById[it]?.title ?: it },
            conflictCount = lines.count { it.conflict },
            reassignCount = lines.count { it.assigneeChange != null },
        )
    }

    enum class Risk { HIGH, NORMAL }

    private val highRiskTools = setOf(
        "cancel_team_task", "remove_team_member", "transfer_team_task",
        "plan_team_week", "set_team_permission", "set_task_deadline",
    )

    fun riskOf(action: AgentAction): Risk =
        if (action.toolName in highRiskTools) Risk.HIGH else Risk.NORMAL

    /** 高风险动作的后果提示，确认前必须让用户看见。 */
    fun consequenceOf(action: AgentAction): String? = when (action.toolName) {
        "cancel_team_task" -> "任务将取消；可用「撤销取消」恢复。"
        "remove_team_member" -> "该成员移出队伍，其任务需重新认领。"
        "transfer_team_task" -> "负责人变更，会通知当事人；可提异议。"
        "plan_team_week" -> "生成整周草案，确认后重排涉及任务并写回分工。"
        "set_team_permission" -> "成员权限即时变化，影响其后可做的操作。"
        "set_task_deadline" -> "截止变化会重算依赖与逾期提醒。"
        else -> null
    }

    fun hoursText(hours: Double): String =
        if (hours < 1) "${(hours * 60).toInt()}分钟" else "${"%.1f".format(hours)}小时"
}