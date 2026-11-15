package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.PersonCardBook
import com.swan1127.repland.domain.team.TeamCharter
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamRosterBook
import com.swan1127.repland.domain.team.TeamTaskStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 团队域的系统提示词与确定性实况（§6）。
 * 提示词只写机制纪律；所有数字来自 [briefing] 与工具结果，模型不自己算、不自己编。
 */
object TeamPrompt {

    fun system(actorId: String, actorName: String, isCaptain: Boolean, charter: TeamCharter = TeamCharter()): String = buildString {
        appendLine("【队伍大局与队规】这是全队协作的最高约束，优先于下面所有机制；先服从大局，再谈具体操作。")
        if (charter.goal.isNotBlank()) appendLine("队伍目标：${charter.goal}")
        appendLine("当前生效的队规（${charter.activeRules.size} 条）：")
        charter.activeRules.forEachIndexed { index, rule -> appendLine("${index + 1}. ${rule.text}") }
        appendLine("队长可随时调整大局与队规；调整后会通知全员，你须按最新生效版本执行。")
        appendLine()
        appendLine("你是 Repland「团队协作」页里的队伍助手。当前身份：$actorName（${if (isCaptain) "队长" else "队员"}）。")
        appendLine("你以这个身份说话和写入：不能代替别人操作，也不能假装成另一个成员；说话用「我」，涉及团队时用队伍名。")
        appendLine("机制纪律（必须遵守）：")
        appendLine("1. 一切实际操作通过工具完成。回答任务、截止、排期、成员、台账的现状前，先读工具现查；对话历史里的数字只是当时的快照。")
        appendLine("2. 写入不会立即执行：会先进入「待确认动作」，用户确认后才生效。收到确认结果前，不要说「已完成 / 已安排 / 已通知」。")
        appendLine("3. 排期落点不由你决定：用 plan_team_week 交给确定性调度器（按负载定人、按空闲定时段、依赖倒排、跨队冲突标红），" +
            "生成草案后再由用户确认生效。用户指定「谁做 / 哪天做」时，用 transfer_team_task 或 set_task_deadline 表达，不要自己发明时段。")
        appendLine("4. 权限先判：没有权限的写入会被拒绝并说明该找谁。如实转述拒绝理由，不要绕道改数据。")
        appendLine("5. 基线是硬约束：MUST 任务不能取消（只能改截止、转派或说明原因）；改基线、改截止、加阶段需要「改基线与截止」权限，通常由队长操作。")
        appendLine("6. 台账只客观留证，不打分、不排名、不评价个人。周报只写项目进展、风险与下周重点。")
        appendLine("7. 隐私：队员的私有时间只显示为「在忙」，不要猜测或追问内容；不要把推测说成事实，不要编造成员或任务。")
        appendLine("8. 说事（做完 / 请假 / 来不及 / 转派 / 异议）要落到对应工具；只登记用户给出的事实，不替用户编造理由。")
        appendLine("9. 资料卡与履历：竞赛履历卡只来自真实加入过的队伍，不能帮用户虚构或手填；找队友用 match_teammates，" +
            "理由必须引用工具结果里的资料，不要凭名字猜能力；公开卡才允许写进邀请文案。")
        appendLine("10. 竞赛资讯两层机制：先用 competition_knowledge 查内部知识打底（加分目录、按年份版本化的口径、" +
            "我的追踪与获奖），需要最新或官方细节再用 competition_web 抓网页核对——有入库官网的优先抓登记官网，" +
            "学校政策以官方页面最新内容为准；引用网络内容必须报工具给出的来源与抓取时间，抓不到就明说，不编造。" +
            "比赛数据仍来自种子快照，口径只作参考，不承诺制度结果；查比赛用 competition_search，我的截止用 competition_deadlines；" +
            "关注比赛与登记获奖是待确认写入，加分数字只报工具结果里给出的参考值，不自己另算。")
        appendLine("回答先说结论，再给下一步；一次只推进一件需要用户确认的事。")
        append("身份编号：$actorId。")
    }

    /** 一次生成的实况：队伍、阶段、队员、任务、本周排期、异常、记忆、我的资料卡与队友名册。只读，不改状态。 */
    fun briefing(
        state: TeamCollabState,
        actorId: String,
        today: LocalDate,
        roster: TeamRosterBook = TeamRosterBook(),
        cards: PersonCardBook? = null,
    ): String = buildString {
        val actor = state.members.firstOrNull { it.memberId == actorId }
        appendLine("【当前身份】${actor?.name ?: actorId}（${actor?.let { describeIdentity(it) } ?: "身份不在队内"}）")
        val deadlineText = state.competitionDeadline?.let { deadline ->
            val days = ChronoUnit.DAYS.between(today, deadline).toInt()
            val whenText = when {
                days > 0 -> "还有 $days 天"
                days == 0 -> "就是今天"
                else -> "已过 ${-days} 天"
            }
            "$deadline（$whenText）"
        } ?: "未设置"
        appendLine("【队伍】${state.teamName} · 比赛截止 $deadlineText · ${if (state.archived) "已归档只读" else "进行中"}")
        if (state.phases.isNotEmpty()) {
            val phases = state.phases.sortedBy { it.start }.joinToString("｜") { phase ->
                val mark = when {
                    today.isAfter(phase.end) -> "已结束"
                    today.isBefore(phase.start) -> "未开始"
                    else -> "进行中"
                }
                "${phase.name} ${phase.start}~${phase.end}（$mark）"
            }
            appendLine("【阶段】$phases")
        } else {
            appendLine("【阶段】未建阶段")
        }
        appendLine("【队员 ${state.members.size} 人】")
        state.members.forEach { member ->
            appendLine("- ${member.memberId} ${member.name}（${describeIdentity(member)}）" +
                member.responsibility.takeIf(String::isNotBlank)?.let { "：${it.take(24)}" }.orEmpty() +
                if (member.lowConfidence) "；课表未导入（低置信度）" else "")
        }
        val active = state.tasks.filter { it.active }
        val done = state.tasks.count { it.status == TeamTaskStatus.DONE }
        val wont = state.tasks.count { it.status == TeamTaskStatus.WONT }
        val overdue = active.filter { task ->
            task.effectiveDeadline(state.phases, state.competitionDeadline)?.isBefore(today) == true
        }
        appendLine(
            "【任务 ${state.tasks.size} 项】进行中 ${active.count { it.status == TeamTaskStatus.IN_PROGRESS }} · " +
                "待办 ${active.count { it.status == TeamTaskStatus.NOT_STARTED }} · 已完成 $done · 已取消 $wont · " +
                "逾期 ${overdue.size} · 未分配 ${active.count { it.assignedTo == null }}",
        )
        active
            .sortedWith(
                compareBy(
                    { it.effectiveDeadline(state.phases, state.competitionDeadline) ?: LocalDate.MAX },
                    { it.taskId },
                ),
            )
            .take(12)
            .forEach { task ->
                val deadline = task.effectiveDeadline(state.phases, state.competitionDeadline)?.toString() ?: "无截止"
                val owner = task.assignedTo?.let { id -> state.members.firstOrNull { it.memberId == id }?.name } ?: "待认领"
                val phase = task.phaseId?.let { id -> state.phases.firstOrNull { it.phaseId == id }?.name }
                appendLine(
                    "- ${task.taskId}「${task.title}」${task.status.label}｜$owner｜截止 $deadline｜" +
                        "${formatHours(task.estimatedHours)}｜${task.rank.label}" + phase?.let { "｜$it" }.orEmpty(),
                )
            }
        val weekEnd = today.plusDays(6)
        val weekBlocks = state.blocks
            .filter { it.sourceTeamId == state.teamId && !it.date.isBefore(today) && !it.date.isAfter(weekEnd) }
            .sortedWith(compareBy({ it.date }, { it.startMinute }))
        if (weekBlocks.isEmpty()) {
            appendLine("【本周排期】暂无安排")
        } else {
            val hours = weekBlocks.sumOf { it.endMinute - it.startMinute } / 60.0
            appendLine("【本周排期 ${weekBlocks.size} 段 / ${formatHours(hours)}】")
            weekBlocks.take(10).forEach { block ->
                val name = state.members.firstOrNull { it.memberId == block.memberId }?.name ?: block.memberId
                val label = if (block.visibility == BlockVisibility.PRIVATE_BUSY) "在忙" else block.publicLabel() ?: "团队安排"
                val taskId = block.taskId?.let { " $it" }.orEmpty()
                appendLine("- ${block.date} $name ${minutes(block.startMinute)}-${minutes(block.endMinute)}「$label」$taskId")
            }
        }
        val anomalies = buildList {
            if (overdue.isNotEmpty()) add("逾期 ${overdue.size} 项")
            if (active.any { it.assignedTo == null }) add("未分配 ${active.count { it.assignedTo == null }} 项")
            if (state.openObjections().isNotEmpty()) add("待裁决异议 ${state.openObjections().size} 条")
        }
        if (anomalies.isNotEmpty()) appendLine("【异常】${anomalies.joinToString("；")}")
        if (state.memories.isNotEmpty()) {
            val memories = state.memories.sortedByDescending { it.at }.take(4)
                .joinToString("；") { "「${it.kind.label}」${it.content.take(30)}" }
            appendLine("【团队记忆】$memories")
        }
        cards?.let { book ->
            val default = book.defaultCard()
            appendLine(
                "【我的资料卡】${default.name}（${if (default.isPublic) "公开可邀" else "仅自己"}）" +
                    default.abilities.takeIf(String::isNotBlank)?.let { "｜能力：$it" }.orEmpty() +
                    default.expectation.takeIf(String::isNotBlank)?.let { "｜团队预期：$it" }.orEmpty() +
                    "｜每日可投入 ${formatHours(default.dailyCapacityHours)}｜空闲：${default.availabilitySummary}",
            )
            val competitions = book.competitionCards()
            if (competitions.isEmpty()) {
                appendLine("【竞赛履历】暂无：只有真实加入过的队伍才会生成履历卡，不能手填。")
            } else {
                appendLine("【竞赛履历 ${competitions.size} 张（来自真实队伍）】")
                competitions.take(6).forEach { card ->
                    appendLine(
                        "- ${card.title}" + card.teamName?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty() +
                            card.headline.takeIf(String::isNotBlank)?.let { "｜$it" }.orEmpty() +
                            card.result.takeIf(String::isNotBlank)?.let { "｜$it" }.orEmpty() +
                            "｜${if (card.isPublic) "公开" else "仅自己"}",
                    )
                }
            }
        }
        if (roster.people.isNotEmpty()) {
            val teammates = roster.teammates()
            appendLine("【我的队友名册】一起参赛过 ${teammates.size} 人 · 想了解 ${roster.candidates().size} 人")
            teammates.take(8).forEach { person ->
                val freeHours = person.weeklyAvailability.values.sumOf { windows -> windows.sumOf { it.minutes } } / 60.0
                appendLine(
                    "- ${person.name}" + person.roleTag?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty() +
                        "｜一起 ${person.teamIds.size} 场｜" +
                        (if (freeHours <= 0) "空闲未登记" else "每周空 ${formatHours(freeHours)}"),
                )
            }
            roster.candidates().take(4).forEach { person ->
                appendLine(
                    "- ${person.name}（想了解）" +
                        person.note.takeIf(String::isNotBlank)?.let { "｜$it" }.orEmpty(),
                )
            }
        }
    }.trimEnd()

    private fun describeIdentity(member: MemberCard): String =
        if (member.isCaptain) {
            "队长，全权限"
        } else {
            "权限：" + (if (member.permissions.isEmpty()) {
                "无（只读参与）"
            } else {
                member.permissions.sortedBy { it.ordinal }.joinToString("、") { it.label }
            })
        }

    private fun formatHours(hours: Double): String =
        if (hours < 1) "${(hours * 60).toInt()}分钟" else "${"%.1f".format(hours)}小时"

    private fun minutes(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
