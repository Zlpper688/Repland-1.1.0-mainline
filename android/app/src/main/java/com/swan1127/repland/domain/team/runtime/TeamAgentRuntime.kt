package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.team.ScheduleConfidence
import com.swan1127.repland.domain.team.TeamLedger
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamScheduler
import com.swan1127.repland.domain.team.agents.LedgerOps
import com.swan1127.repland.domain.team.agents.Liaison
import com.swan1127.repland.domain.team.agents.SchedulerOps
import com.swan1127.repland.domain.team.agents.StructuredRequest
import com.swan1127.repland.domain.team.agents.UpdateKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * 团队协作会话（§6 数据流）：队员说事 → LIAISON → SCHEDULER → TEAM_DRAFT → 确认/异议 → 落库+通知。
 * runtime 只读计算 + 两级验收；对状态的一切写入都由调用方通过 TeamCollabState 方法完成（草案→确认纪律）。
 */
class TeamAgentRuntime(
    private val memoryBank: TeamMemoryBank = TeamMemoryBank(),
    private val runIdFactory: () -> String = { "run-" + UUID.randomUUID().toString().take(8) },
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    fun runSession(
        ctx: TeamSessionContext,
        state: com.swan1127.repland.domain.team.TeamCollabState,
        onEvent: (TeamEvent) -> Unit = {},
    ): TeamOutcome {
        var seq = 0
        fun emit(event: (Int) -> TeamEvent) {
            onEvent(event(seq))
            seq += 1
        }

        val runId = runIdFactory()
        val workspace = TeamWorkspace(runId, state)
        val selected = TeamAgentRegistry.route(ctx.requestKind)
        emit { TeamRunStarted(it, runId, ctx.requestKind, ctx.actorId, selected.toList()) }
        workspace.appendTrace("调度：请求=${ctx.requestKind.label}，选中 [${selected.joinToString("、") { it.displayName }}]")

        var structured: StructuredRequest? = null
        var draft: com.swan1127.repland.domain.team.TeamPlanDraft? = null
        var exceptions: TeamExceptions? = null
        var ledgerEntries = emptyList<com.swan1127.repland.domain.team.LedgerEntry>()
        var weeklyReport: com.swan1127.repland.domain.team.WeeklyReportDraft? = null

        val order = selected.toList()
        try {
            order.forEachIndexed { index, agent ->
                val spec = TeamAgentRegistry.spec(agent)
                val memory = memoryBank.seed(agent, runId)
                workspace.markRan(agent)
                emit { TeamAgentStarted(it, agent, index + 1, order.size) }

                val outcomeLine = when (agent) {
                    TeamAgentId.LIAISON -> {
                        val request = Liaison.parse(ctx.rawText, ctx.actorId, state.tasks, ctx.at, today())
                        structured = request
                        emit { s ->
                            TeamToolCalled(
                                s, TeamToolRecord(
                                    TeamToolId.REQ_AI, agent, TeamToolOutcome.SKIPPED,
                                    inputDigest = ctx.rawText.take(24), outputDigest = "确定性规则解析",
                                    detail = "T1 离线：未配置 AI 提供方，口语解析走确定性规则（§15 降级矩阵）",
                                ),
                            )
                        }
                        val verdict = permissionVerdict(ctx, request, state)
                        val brief = TeamBrief(
                            requestKind = ctx.requestKind,
                            actorId = ctx.actorId,
                            rawText = ctx.rawText,
                            structuredRequest = request,
                            baselineSummary = "${state.phases.size} 个阶段 / ${state.tasks.count { it.active }} 项在办任务",
                            profileCardSummary = state.profileCard?.competitionName ?: "档案卡未建",
                            permissionVerdict = verdict,
                        )
                        workspace.putBrief(agent, brief, "原话 ${ctx.rawText.length} 字 → ${request.kind.label}")
                        val gated = runCatching { state.member(ctx.actorId).hasPermission(TeamPermission.MEMORY_WRITE) }.getOrDefault(false)
                        emit { s ->
                            TeamToolCalled(
                                s, TeamToolRecord(
                                    TeamToolId.MEM_WRITE, agent,
                                    if (gated) TeamToolOutcome.OK else TeamToolOutcome.SKIPPED,
                                    inputDigest = request.kind.label,
                                    outputDigest = "会话摘要入记忆",
                                    detail = if (gated) null else "发起人无「记忆库写入」权限，留痕仅入台账（§6 门控）",
                                ),
                            )
                        }
                        if (verdict.allowed) {
                            memory.note("lastKind", request.kind.label)
                            request.taskId?.let { memory.note("lastTaskId", it) }
                        }
                        memory.note("requestCount", (memory.recallInt("requestCount") ?: 0) + 1)
                        workspace.appendTrace("对接：原话解析为「${request.kind.label}」${if (verdict.allowed) "" else "（越权拒绝）"}")
                        "${request.kind.label}，权限${if (verdict.allowed) "通过" else "拒绝"}"
                    }

                    TeamAgentId.SCHEDULER -> {
                        val brief = workspace.brief
                        if (brief != null) check(brief.permissionVerdict.allowed) { brief.permissionVerdict.reason }
                        val roster = TeamRosterSnapshot(
                            state.members,
                            notes = state.members.filter { it.lowConfidence }
                                .map { "${it.name} 未导入课表，照排但标低置信度" },
                        )
                        workspace.putRoster(agent, roster, "${roster.members.size} 名成员")
                        val adjustment = SchedulerOps.simulate(brief?.structuredRequest, state, today())
                        val scheduled = SchedulerOps.schedule(state, today(), adjustment, ctx.personalTime, ctx.actorId)
                        draft = scheduled
                        val load = TeamScheduler.loadByMember(
                            state.members, scheduled.placements,
                            state.blocks.filter { it.sourceTeamId == state.teamId },
                            state.teamId,
                        )
                        workspace.putLoadSnapshot(
                            agent,
                            TeamLoadSnapshot(
                                loadHoursByMember = load,
                                freeWindowCountByMember = state.members.associate {
                                    it.memberId to it.weeklyAvailability.values.sumOf { windows -> windows.size }
                                },
                                lowConfidenceMembers = state.members
                                    .filter { it.lowConfidence }
                                    .map { it.memberId },
                            ),
                            "负载 " + load.entries.joinToString("、") { "${it.key}=${it.value}h" },
                        )
                        workspace.putDraft(agent, scheduled, "${scheduled.placements.size} 段安排，${scheduled.unassigned.size} 项待安排")
                        emit { s ->
                            TeamToolCalled(
                                s, TeamToolRecord(
                                    TeamToolId.CAL_CONFLICT, agent, TeamToolOutcome.OK,
                                    inputDigest = "任务 ${state.tasks.size} 项",
                                    outputDigest = "草案 ${scheduled.placements.size} 段 / 冲突 ${scheduled.placements.count { it.conflicts.isNotEmpty() }} 处",
                                ),
                            )
                        }
                        memory.note("placementCount", scheduled.placements.size)
                        memory.note("unassignedCount", scheduled.unassigned.size)
                        memory.note("conflictCount", scheduled.placements.count { it.conflicts.isNotEmpty() })
                        workspace.appendTrace("排期：${scheduled.placements.size} 段安排落进成员空闲，${scheduled.unassigned.size} 项待安排。")
                        "${scheduled.placements.size} 段安排，${scheduled.unassigned.size} 项待安排"
                    }

                    TeamAgentId.LEDGER -> {
                        val request = workspace.brief?.structuredRequest
                        ledgerEntries = if (request != null) LedgerOps.entriesForRequest(request, state) else emptyList()
                        exceptions = LedgerOps.collectExceptions(state, draft)
                        val weekStart = ctx.weekStart ?: ctx.at.toLocalDate().with(DayOfWeek.MONDAY)
                        weeklyReport = if (ctx.requestKind == TeamRequestKind.WEEKLY_REPORT || ctx.requestKind == TeamRequestKind.PLAN_WEEK) {
                            TeamLedger.weeklyReport(
                                tasks = state.tasks,
                                entries = state.ledger,
                                weekStart = weekStart,
                                weekEnd = weekStart.plusDays(6),
                                openObjections = state.openObjections(),
                                overdueTaskIds = state.tasks
                                    .filter { it.active && it.computedDeadline?.isBefore(weekStart) == true }
                                    .map { it.taskId },
                            )
                        } else {
                            null
                        }
                        workspace.putLedgerSnapshot(
                            agent,
                            TeamLedgerSnapshot(
                                newEntries = ledgerEntries,
                                weeklyReport = weeklyReport,
                                transferApplied = null,
                                objectionRaised = null,
                            ),
                            "台账 +${ledgerEntries.size} 条" + (weeklyReport?.let { "，周报草稿就绪" } ?: ""),
                        )
                        workspace.putExceptions(
                            agent, exceptions,
                            "未排进 ${exceptions.unassignedTaskIds.size}，冲突标红 ${exceptions.conflictPlacements.size}，待裁决异议 ${exceptions.openObjections.size}",
                        )
                        emit { s ->
                            TeamToolCalled(
                                s, TeamToolRecord(
                                    TeamToolId.MEM_WRITE, agent, TeamToolOutcome.OK,
                                    inputDigest = "本轮产出", outputDigest = "台账快照入记忆",
                                ),
                            )
                        }
                        memory.note("entryCount", ledgerEntries.size)
                        memory.note("exceptionCount", exceptions.unassignedTaskIds.size + exceptions.conflictPlacements.size)
                        workspace.appendTrace(
                            "台账：拟新增 ${ledgerEntries.size} 条留证；异常区 ${exceptions.unassignedTaskIds.size} 项未排进、${exceptions.conflictPlacements.size} 处冲突。",
                        )
                        "台账 +${ledgerEntries.size} 条，异常 ${exceptions.unassignedTaskIds.size + exceptions.conflictPlacements.size} 处"
                    }
                }

                val criteria = spec.criteria.map { it.evaluate(TeamAcceptanceContext(workspace, ctx.actorId)) }
                emit { TeamAgentFinished(it, agent, outcomeLine, criteria, memory.written().map { "${it.key}=${it.value}" }) }
                memoryBank.commit(memory, outcomeLine)
            }

            // 最终两级验收：TEAM 标准 + 各席位标准
            val context = TeamAcceptanceContext(workspace, ctx.actorId)
            val finalResults = TeamAgentRegistry.TEAM_CRITERIA.map { it.evaluate(context) }
            val inherited = order.flatMap { agent -> TeamAgentRegistry.spec(agent).criteria.map { it.evaluate(context) } }
            val all = finalResults + inherited
            val passed = all.none { it.blocking }
            emit { TeamAcceptanceFinished(it, all, passed) }
            if (!passed) {
                val reason = all.filter { it.blocking }.joinToString("；") { "${it.statement}（${it.detail}）" }
                emit { TeamRunFailed(it, "两级验收未通过：$reason") }
                error("两级验收未通过：$reason")
            }

            val outcome = TeamOutcome(
                requestKind = ctx.requestKind,
                structuredRequest = structured,
                draft = draft,
                proposedLedgerEntries = ledgerEntries,
                exceptions = exceptions,
                weeklyReport = weeklyReport,
                suggestedTransfers = emptyList(),
                trace = workspace.trace(),
            )
            emit { TeamOutcomeReady(it, outcome) }
            return outcome
        } catch (error: Throwable) {
            emit { TeamRunFailed(it, error.message ?: "未知错误（${error::class.simpleName}）") }
            throw error
        }
    }

    /** 权限与可见性二级验收（§6/§8/§10）：越权请求直接 FAIL。 */
    private fun permissionVerdict(ctx: TeamSessionContext, request: StructuredRequest, state: com.swan1127.repland.domain.team.TeamCollabState): PermissionVerdict {
        val actor = runCatching { state.member(ctx.actorId) }.getOrElse {
            return PermissionVerdict(false, "发起人不在花名册：${ctx.actorId}")
        }
        if (actor.isCaptain) return PermissionVerdict(true, "队长全权限")
        val task = request.taskId?.let { id -> runCatching { state.task(id) }.getOrNull() }
        return when (request.kind) {
            UpdateKind.DONE, UpdateKind.EARLY_DONE ->
                when {
                    task == null -> PermissionVerdict(false, "找不到要报告的任务")
                    task.assignedTo == ctx.actorId -> PermissionVerdict(true, "报告自己名下任务")
                    task.assignedTo == null -> PermissionVerdict(true, "任务未分配，报告完成即认领")
                    else -> PermissionVerdict(false, "「${task.title}」不在你名下，不能替他人报告完成")
                }
            UpdateKind.BUSY, UpdateKind.DELAY ->
                when {
                    task == null -> PermissionVerdict(true, "无名下任务可切，仅留痕")
                    task.assignedTo == ctx.actorId -> PermissionVerdict(true, "调整自己名下任务")
                    else -> PermissionVerdict(false, "「${task.title}」不在你名下，不能替他人说事")
                }
            UpdateKind.TRANSFER_REQUEST ->
                if (actor.hasPermission(TeamPermission.SCHEDULE_TRANSFER)) PermissionVerdict(true, "有排期与转派权限")
                else PermissionVerdict(false, "${actor.name} 没有「排期与转派」权限：转派请由队长或有权限成员执行")
            UpdateKind.OBJECTION -> {
                val transferable = state.transfers.lastOrNull { it.taskId == request.taskId && it.toMemberId == ctx.actorId }
                if (transferable != null) PermissionVerdict(true, "被转派人一键异议")
                else PermissionVerdict(false, "你只能对转派给你的任务提异议")
            }
            UpdateKind.NOTE -> PermissionVerdict(true, "口语登记仅入台账")
        }
    }
}
