package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.team.BlockVisibility

/**
 * 三席岗位说明书（§6）：名称、使命、声明工具、两级验收标准、记忆键。
 * 中心化登记：新席位只加这里；未声明工具在调用点即抛错；缺标准会在最终验收暴露为「0 条标准」。
 */
data class TeamAgentSpec(
    val agentId: TeamAgentId,
    val tools: List<TeamToolId>,
    val memoryKeys: List<String>,
    val criteria: List<TeamAcceptanceCriterion>,
    val owns: List<TeamSlot>,
)

object TeamAgentRegistry {

    val LIAISON = TeamAgentSpec(
        agentId = TeamAgentId.LIAISON,
        tools = listOf(TeamToolId.REQ_AI, TeamToolId.MEM_WRITE),
        memoryKeys = listOf("lastKind", "lastTaskId", "requestCount"),
        owns = listOf(TeamSlot.TEAM_BRIEF),
        criteria = listOf(
            TeamAcceptanceCriterion(
                "liaison.raw-verbatim",
                "队员原话必须原样上浮，AI 不替队员写句子",
                TeamCriterionLevel.PERMISSION_VISIBILITY,
            ) { c ->
                when (val brief = c.workspace.brief) {
                    null -> TeamVerdict.Skip("本轮未运行对接官")
                    else ->
                        if (brief.structuredRequest?.rawText == brief.rawText || brief.rawText.isEmpty()) TeamVerdict.Pass
                        else TeamVerdict.Fail("结构化请求携带的原话与输入不一致")
                }
            },
            TeamAcceptanceCriterion(
                "liaison.permission-checked",
                "越权请求直接 FAIL：动作所需权限不得超出发起人权限",
                TeamCriterionLevel.PERMISSION_VISIBILITY,
            ) { c ->
                when (val brief = c.workspace.brief) {
                    null -> TeamVerdict.Skip("本轮未运行对接官")
                    else ->
                        if (brief.permissionVerdict.allowed) TeamVerdict.Pass
                        else TeamVerdict.Fail(brief.permissionVerdict.reason)
                }
            },
        ),
    )

    val SCHEDULER = TeamAgentSpec(
        agentId = TeamAgentId.SCHEDULER,
        tools = listOf(TeamToolId.CAL_CONFLICT),
        memoryKeys = listOf("placementCount", "unassignedCount", "conflictCount"),
        owns = listOf(TeamSlot.ROSTER, TeamSlot.LOAD_SNAPSHOT, TeamSlot.TEAM_DRAFT),
        criteria = listOf(
            TeamAcceptanceCriterion(
                "sched.deadlines-respected",
                "一级硬约束：所有安排不晚于任务倒算期限",
                TeamCriterionLevel.HARD_CONSTRAINT,
            ) { c ->
                when (val draft = c.workspace.draft) {
                    null -> TeamVerdict.Skip("本轮未运行排期官")
                    else -> {
                        val state = c.workspace.state
                        val violations = draft.placements.count { placement ->
                            val task = state.tasks.firstOrNull { it.taskId == placement.taskId }
                            val deadline = task?.effectiveDeadline(state.phases, state.competitionDeadline)
                            deadline != null && placement.date > deadline
                        }
                        if (violations == 0) TeamVerdict.Pass else TeamVerdict.fail("$violations 段安排越过任务期限")
                    }
                }
            },
            TeamAcceptanceCriterion(
                "sched.dependencies-ordered",
                "一级硬约束：上游任务先于下游开工",
                TeamCriterionLevel.HARD_CONSTRAINT,
            ) { c ->
                when (val draft = c.workspace.draft) {
                    null -> TeamVerdict.Skip("本轮未运行排期官")
                    else -> {
                        val state = c.workspace.state
                        val firstDate = draft.placements.groupBy { it.taskId }.mapValues { (_, v) -> v.minOf { it.date } }
                        val violations = state.tasks.count { task ->
                            task.dependsOn.isNotEmpty() && firstDate.containsKey(task.taskId) &&
                                task.dependsOn.any { dep -> firstDate[dep]?.let { it >= firstDate.getValue(task.taskId) } == true }
                        }
                        if (violations == 0) TeamVerdict.Pass else TeamVerdict.fail("$violations 个任务早于其上游开工")
                    }
                }
            },
            TeamAcceptanceCriterion(
                "sched.capacity-respected",
                "一级硬约束：每人每天投入不超过个人容量",
                TeamCriterionLevel.HARD_CONSTRAINT,
            ) { c ->
                when (val draft = c.workspace.draft) {
                    null -> TeamVerdict.Skip("本轮未运行排期官")
                    else -> {
                        val state = c.workspace.state
                        val byMemberDay = draft.placements.groupBy { it.memberId to it.date }
                        val violations = byMemberDay.count { (key, placements) ->
                            val member = state.members.firstOrNull { it.memberId == key.first }
                            val capacity = (member?.dailyCapacityHours ?: 3.0) * 60
                            placements.sumOf { it.minutes } > capacity + 0.5
                        }
                        if (violations == 0) TeamVerdict.Pass else TeamVerdict.fail("$violations 个成员日超出容量")
                    }
                }
            },
            TeamAcceptanceCriterion(
                "sched.assignees-known",
                "二级可见性：草案只引用花名册上的成员，不泄露私有块内容",
                TeamCriterionLevel.PERMISSION_VISIBILITY,
            ) { c ->
                when (val draft = c.workspace.draft) {
                    null -> TeamVerdict.Skip("本轮未运行排期官")
                    else -> {
                        val known = c.workspace.state.members.map { it.memberId }.toSet()
                        val unknown = draft.placements.map { it.memberId }.toSet() - known
                        if (unknown.isEmpty()) TeamVerdict.Pass else TeamVerdict.fail("草案引用了花名册之外的成员：$unknown")
                    }
                }
            },
        ),
    )

    val LEDGER = TeamAgentSpec(
        agentId = TeamAgentId.LEDGER,
        tools = listOf(TeamToolId.MEM_WRITE),
        memoryKeys = listOf("entryCount", "exceptionCount"),
        owns = listOf(TeamSlot.LEDGER_SNAP, TeamSlot.EXCEPTIONS),
        criteria = listOf(
            TeamAcceptanceCriterion(
                "ledger.entries-objective",
                "台账条目只客观留证：都有时间戳、成员与证据，无评分字段",
                TeamCriterionLevel.HARD_CONSTRAINT,
            ) { c ->
                val snap = c.workspace.ledgerSnap
                if (snap == null) TeamVerdict.Skip("本轮未运行台账官")
                else {
                    val bad = snap.newEntries.count { it.evidence.isBlank() || it.memberId.isBlank() }
                    if (bad == 0) TeamVerdict.Pass else TeamVerdict.fail("$bad 条台账条目缺客观证据")
                }
            },
            TeamAcceptanceCriterion(
                "ledger.report-no-personal-rating",
                "周报不对个人作评价：草稿不含评分、排名字样",
                TeamCriterionLevel.PERMISSION_VISIBILITY,
            ) { c ->
                val report = c.workspace.ledgerSnap?.weeklyReport
                if (report == null) TeamVerdict.Skip("本轮没有周报草稿")
                else {
                    val banned = listOf("评分", "排名", "效率低", "最差")
                    val hit = (report.progressSummary + report.riskRadar + report.nextWeekFocus)
                        .filter { line -> banned.any { it in line } }
                    if (hit.isEmpty()) TeamVerdict.Pass else TeamVerdict.fail("周报含评价性表述：${hit.first()}")
                }
            },
        ),
    )

    val TEAM_CRITERIA = listOf(
        TeamAcceptanceCriterion(
            "team.slots-filled",
            "每个执行过的席位都把产物放进了工作区",
            TeamCriterionLevel.HARD_CONSTRAINT,
        ) { c ->
            val slots = c.workspace.filledSlots()
            val ran = c.workspace.agentsRan()
            val expected = buildList {
                if (TeamAgentId.LIAISON in ran) add(TeamSlot.TEAM_BRIEF)
                if (TeamAgentId.SCHEDULER in ran) {
                    add(TeamSlot.ROSTER); add(TeamSlot.LOAD_SNAPSHOT); add(TeamSlot.TEAM_DRAFT)
                }
                if (TeamAgentId.LEDGER in ran) add(TeamSlot.EXCEPTIONS)
            }
            val missing = expected.filter { it !in slots }
            if (missing.isEmpty()) TeamVerdict.Pass
            else TeamVerdict.fail("缺少产物：${missing.joinToString("、") { it.label }}")
        },
        TeamAcceptanceCriterion(
            "team.workspace-ownership",
            "工作区每个格子都由它的归属席位写入",
            TeamCriterionLevel.HARD_CONSTRAINT,
        ) { c ->
            val violated = c.workspace.entries().filter { c.workspace.ownerOf(it.slot) != it.agent }
            if (violated.isEmpty()) TeamVerdict.Pass
            else TeamVerdict.fail("${violated.size} 处越权写入：${violated.joinToString { "${it.agent}→${it.slot.label}" }}")
        },
        TeamAcceptanceCriterion(
            "team.private-blocks-invisible",
            "私有块内容不出现在任何产出里（时间可见、内容不可见）",
            TeamCriterionLevel.PERMISSION_VISIBILITY,
        ) { c ->
            val privateLabels = c.workspace.state.blocks
                .filter { it.visibility == BlockVisibility.PRIVATE_BUSY }
                .mapNotNull { it.label }
            if (privateLabels.isEmpty()) TeamVerdict.Pass
            else {
                val leaked = c.workspace.trace() + (c.workspace.draft?.notes.orEmpty())
                val hit = leaked.filter { line -> privateLabels.any { it in line } }
                if (hit.isEmpty()) TeamVerdict.Pass else TeamVerdict.fail("私有块内容泄漏：${hit.first()}")
            }
        },
    )

    fun spec(agentId: TeamAgentId): TeamAgentSpec = when (agentId) {
        TeamAgentId.LIAISON -> LIAISON
        TeamAgentId.SCHEDULER -> SCHEDULER
        TeamAgentId.LEDGER -> LEDGER
    }

    /** 路由：说事 → 对接官 → 排期官 → 台账官；周报只走台账官。 */
    fun route(kind: TeamRequestKind): LinkedHashSet<TeamAgentId> {
        val set = LinkedHashSet<TeamAgentId>()
        when (kind) {
            TeamRequestKind.PLAN_WEEK -> set += listOf(TeamAgentId.SCHEDULER, TeamAgentId.LEDGER)
            TeamRequestKind.WEEKLY_REPORT -> set += listOf(TeamAgentId.LEDGER)
            TeamRequestKind.STATUS_REPORT -> set += listOf(TeamAgentId.LIAISON, TeamAgentId.LEDGER)
            TeamRequestKind.MEMBER_UPDATE, TeamRequestKind.TRANSFER, TeamRequestKind.OBJECTION ->
                set += listOf(TeamAgentId.LIAISON, TeamAgentId.SCHEDULER, TeamAgentId.LEDGER)
        }
        return set
    }
}
