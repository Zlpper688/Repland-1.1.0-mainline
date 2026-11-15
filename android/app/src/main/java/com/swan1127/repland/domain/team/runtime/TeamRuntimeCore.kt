package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.team.agents.StructuredRequest
import java.time.LocalDateTime

/**
 * 团队 Agent 层运行时核心（TEAM_COLLAB_DESIGN.md §6）。
 * 复用 domain/planteam 的 runtime 模式：AgentRegistry 注册、槽位工作区、两级验收、per-agent 记忆。
 */

enum class TeamAgentId(val displayName: String, val mission: String) {
    SCHEDULER("排期官", "负载定人 + 空闲定时段 + 依赖倒算，产出分配草案"),
    LIAISON("对接官", "队员原话 → 结构化请求 → 触发重排 → 通知"),
    LEDGER("台账官", "执行块 / 状态流转 / 外链 / 财务流水 → 贡献证据 → 周报草稿"),
}

enum class TeamSlot(val label: String) {
    TEAM_BRIEF("团队简报(档案卡+基线+原话)"),
    ROSTER("花名册(成员+负责说明+画像)"),
    LOAD_SNAPSHOT("负载与空闲"),
    TEAM_DRAFT("分配草案"),
    EXCEPTIONS("异常区"),
    LEDGER_SNAP("台账快照"),
}

/** 三个声明工具（§6）：REQ_AI 走 BYOK 可选；MEM_WRITE 权限门控；CAL_CONFLICT 确定性。 */
enum class TeamToolId(val label: String, val purpose: String) {
    REQ_AI("AI 口语解析", "可选：请已配置的模型解析口语/提取档案卡；未配置则降级为确定性规则"),
    MEM_WRITE("记忆库写入", "把本轮结构化结论写入 per-agent 记忆；受「记忆库写入」权限门控"),
    CAL_CONFLICT("硬约束校验", "确定性：截止 / 依赖 / 容量 / 跨队冲突标红"),
}

enum class TeamRequestKind(val label: String) {
    PLAN_WEEK("整周排期"),
    MEMBER_UPDATE("队员说事"),
    STATUS_REPORT("报告完成"),
    TRANSFER("转派"),
    OBJECTION("异议"),
    WEEKLY_REPORT("周报"),
}

/** 两级验收（§6）：一级 = 截止/依赖/容量硬约束；二级 = 权限与可见性（越权请求直接 FAIL）。 */
enum class TeamCriterionLevel { HARD_CONSTRAINT, PERMISSION_VISIBILITY, ADVISORY }

enum class TeamCriterionStatus { PASS, FAIL, SKIPPED }

data class TeamCriterionResult(
    val criterionId: String,
    val statement: String,
    val level: TeamCriterionLevel,
    val status: TeamCriterionStatus,
    val detail: String = "",
) {
    val blocking: Boolean get() = status == TeamCriterionStatus.FAIL && level != TeamCriterionLevel.ADVISORY
}

sealed interface TeamVerdict {
    data object Pass : TeamVerdict
    data class Fail(val detail: String) : TeamVerdict
    data class Skip(val detail: String) : TeamVerdict

    companion object {
        fun fail(detail: String): TeamVerdict = Fail(detail)
        fun skip(detail: String): TeamVerdict = Skip(detail)
    }
}

class TeamAcceptanceCriterion(
    val id: String,
    val statement: String,
    val level: TeamCriterionLevel,
    val check: (TeamAcceptanceContext) -> TeamVerdict,
) {
    fun evaluate(ctx: TeamAcceptanceContext): TeamCriterionResult {
        val verdict = try {
            check(ctx)
        } catch (error: Exception) {
            TeamVerdict.Fail("检查本身出错：${error.message ?: error::class.simpleName}")
        }
        return when (verdict) {
            is TeamVerdict.Pass -> TeamCriterionResult(id, statement, level, TeamCriterionStatus.PASS)
            is TeamVerdict.Fail -> TeamCriterionResult(id, statement, level, TeamCriterionStatus.FAIL, verdict.detail)
            is TeamVerdict.Skip -> TeamCriterionResult(id, statement, level, TeamCriterionStatus.SKIPPED, verdict.detail)
        }
    }
}

class TeamAcceptanceContext(
    val workspace: TeamWorkspace,
    val actorId: String,
) {
    val state get() = workspace.state
}

// ---------- 工具调用记录 ----------

enum class TeamToolOutcome { OK, DEGRADED, SKIPPED, FAILED }

data class TeamToolRecord(
    val toolId: TeamToolId,
    val agent: TeamAgentId,
    val outcome: TeamToolOutcome,
    val inputDigest: String,
    val outputDigest: String,
    val detail: String? = null,
)

// ---------- 事件流 ----------

sealed interface TeamEvent {
    val seq: Int
    val summary: String
}

data class TeamRunStarted(
    override val seq: Int,
    val runId: String,
    val requestKind: TeamRequestKind,
    val actorId: String,
    val agents: List<TeamAgentId>,
) : TeamEvent {
    override val summary: String =
        "开始协作：${requestKind.label} · 发起人 $actorId · 调度 ${agents.joinToString("、") { it.displayName }}"
}

data class TeamAgentStarted(
    override val seq: Int,
    val agentId: TeamAgentId,
    val index: Int,
    val total: Int,
) : TeamEvent {
    override val summary: String = "${agentId.displayName} 就位（$index/$total）：${agentId.mission}"
}

data class TeamToolCalled(
    override val seq: Int,
    val record: TeamToolRecord,
) : TeamEvent {
    override val summary: String = buildString {
        append(record.toolId.label)
        append(" · ")
        append(
            when (record.outcome) {
                TeamToolOutcome.OK -> record.outputDigest
                TeamToolOutcome.DEGRADED -> "降级：${record.detail ?: record.outputDigest}"
                TeamToolOutcome.SKIPPED -> "跳过：${record.detail ?: "本轮不需要"}"
                TeamToolOutcome.FAILED -> "失败：${record.detail ?: record.outputDigest}"
            },
        )
    }
}

data class TeamAgentFinished(
    override val seq: Int,
    val agentId: TeamAgentId,
    val outcomeLine: String,
    val criteria: List<TeamCriterionResult>,
    val memoryWrites: List<String>,
) : TeamEvent {
    val blockingFailures: List<TeamCriterionResult> = criteria.filter { it.blocking }
    override val summary: String =
        "${agentId.displayName} 完成：$outcomeLine（验收 ${criteria.count { it.status == TeamCriterionStatus.PASS }}/${criteria.size}）"
}

data class TeamAcceptanceFinished(
    override val seq: Int,
    val results: List<TeamCriterionResult>,
    val passed: Boolean,
) : TeamEvent {
    val blockingFailures: List<TeamCriterionResult> = results.filter { it.blocking }
    override val summary: String = buildString {
        append(if (passed) "两级验收通过" else "两级验收未通过")
        append("：${results.count { it.status == TeamCriterionStatus.PASS }}/${results.size} 条标准通过")
        if (blockingFailures.isNotEmpty()) append("，阻断项：${blockingFailures.joinToString("；") { it.statement }}")
    }
}

data class TeamOutcomeReady(
    override val seq: Int,
    val outcome: TeamOutcome,
) : TeamEvent {
    override val summary: String = "协作产出就绪：${outcome.summaryLine}"
}

data class TeamRunFailed(
    override val seq: Int,
    val message: String,
) : TeamEvent {
    override val summary: String = "协作中断：$message"
}

// ---------- 工作区：六槽位 + 归属校验 ----------

data class TeamWorkspaceEntry(
    val agent: TeamAgentId,
    val slot: TeamSlot,
    val summary: String,
    val revision: Int,
)

class TeamWorkspace(
    val runId: String,
    val state: com.swan1127.repland.domain.team.TeamCollabState,
) {
    private val ledger = mutableListOf<TeamWorkspaceEntry>()
    private var revision = 0
    private val ran = LinkedHashSet<TeamAgentId>()
    private val traceLines = mutableListOf<String>()

    var brief: TeamBrief? = null
        private set
    var roster: TeamRosterSnapshot? = null
        private set
    var loadSnapshot: TeamLoadSnapshot? = null
        private set
    var draft: com.swan1127.repland.domain.team.TeamPlanDraft? = null
        private set
    var exceptions: TeamExceptions? = null
        private set
    var ledgerSnap: TeamLedgerSnapshot? = null
        private set

    fun putBrief(agent: TeamAgentId, value: TeamBrief, summary: String) =
        put(agent, TeamSlot.TEAM_BRIEF, summary) { brief = value }

    fun putRoster(agent: TeamAgentId, value: TeamRosterSnapshot, summary: String) =
        put(agent, TeamSlot.ROSTER, summary) { roster = value }

    fun putLoadSnapshot(agent: TeamAgentId, value: TeamLoadSnapshot, summary: String) =
        put(agent, TeamSlot.LOAD_SNAPSHOT, summary) { loadSnapshot = value }

    fun putDraft(agent: TeamAgentId, value: com.swan1127.repland.domain.team.TeamPlanDraft, summary: String) =
        put(agent, TeamSlot.TEAM_DRAFT, summary) { draft = value }

    fun putExceptions(agent: TeamAgentId, value: TeamExceptions, summary: String) =
        put(agent, TeamSlot.EXCEPTIONS, summary) { exceptions = value }

    fun putLedgerSnapshot(agent: TeamAgentId, value: TeamLedgerSnapshot, summary: String) =
        put(agent, TeamSlot.LEDGER_SNAP, summary) { ledgerSnap = value }

    fun requireBrief(reader: TeamAgentId): TeamBrief =
        brief ?: missing(reader, TeamSlot.TEAM_BRIEF, TeamAgentId.LIAISON)

    fun requireLoad(reader: TeamAgentId): TeamLoadSnapshot =
        loadSnapshot ?: missing(reader, TeamSlot.LOAD_SNAPSHOT, TeamAgentId.SCHEDULER)

    fun entries(): List<TeamWorkspaceEntry> = ledger.toList()
    fun filledSlots(): Set<TeamSlot> = ledger.map { it.slot }.toSet()
    fun markRan(agent: TeamAgentId) {
        ran += agent
    }
    fun agentsRan(): Set<TeamAgentId> = ran.toSet()
    fun appendTrace(line: String) {
        traceLines += line
    }
    fun trace(): List<String> = traceLines.toList()
    fun ownerOf(slot: TeamSlot): TeamAgentId = OWNER.getValue(slot)

    private inline fun put(agent: TeamAgentId, slot: TeamSlot, summary: String, write: () -> Unit) {
        val owner = OWNER.getValue(slot)
        require(owner == agent) {
            "工作区格子「${slot.label}」属于 ${owner.displayName}，${agent.displayName} 不能写入。"
        }
        write()
        revision += 1
        ledger += TeamWorkspaceEntry(agent, slot, summary, revision)
    }

    private fun <T> missing(reader: TeamAgentId, slot: TeamSlot, producer: TeamAgentId): T =
        error("${reader.displayName} 想读「${slot.label}」，但 ${producer.displayName} 还没有把它放进工作区。")

    companion object {
        val OWNER: Map<TeamSlot, TeamAgentId> = mapOf(
            TeamSlot.TEAM_BRIEF to TeamAgentId.LIAISON,
            TeamSlot.ROSTER to TeamAgentId.SCHEDULER,
            TeamSlot.LOAD_SNAPSHOT to TeamAgentId.SCHEDULER,
            TeamSlot.TEAM_DRAFT to TeamAgentId.SCHEDULER,
            TeamSlot.LEDGER_SNAP to TeamAgentId.LEDGER,
            TeamSlot.EXCEPTIONS to TeamAgentId.LEDGER,
        )
    }
}

// ---------- 槽位载荷 ----------

/** TEAM_BRIEF：档案卡+基线摘要 + 队员原话 + 结构化请求（AI 不写句子：原话原样上浮）。 */
data class TeamBrief(
    val requestKind: TeamRequestKind,
    val actorId: String,
    val rawText: String,
    val structuredRequest: StructuredRequest?,
    val baselineSummary: String,
    val profileCardSummary: String,
    val permissionVerdict: PermissionVerdict,
)

data class PermissionVerdict(
    val allowed: Boolean,
    val reason: String,
)

data class TeamRosterSnapshot(
    val members: List<com.swan1127.repland.domain.team.MemberCard>,
    val notes: List<String> = emptyList(),
)

data class TeamLoadSnapshot(
    val loadHoursByMember: Map<String, Double>,
    val freeWindowCountByMember: Map<String, Int>,
    val lowConfidenceMembers: List<String>,
)

data class TeamExceptions(
    val unassignedTaskIds: List<String>,
    val conflictPlacements: List<com.swan1127.repland.domain.team.Placement>,
    val openObjections: List<com.swan1127.repland.domain.team.Objection>,
    val notes: List<String>,
)

data class TeamLedgerSnapshot(
    val newEntries: List<com.swan1127.repland.domain.team.LedgerEntry>,
    val weeklyReport: com.swan1127.repland.domain.team.WeeklyReportDraft?,
    val transferApplied: com.swan1127.repland.domain.team.TransferRecord?,
    val objectionRaised: com.swan1127.repland.domain.team.Objection?,
)

/** 最终产出：runtime 只读计算，落库一律走 TeamCollabState 方法（草案→确认纪律）。 */
data class TeamOutcome(
    val requestKind: TeamRequestKind,
    val structuredRequest: StructuredRequest?,
    val draft: com.swan1127.repland.domain.team.TeamPlanDraft?,
    val proposedLedgerEntries: List<com.swan1127.repland.domain.team.LedgerEntry>,
    val exceptions: TeamExceptions?,
    val weeklyReport: com.swan1127.repland.domain.team.WeeklyReportDraft?,
    val suggestedTransfers: List<com.swan1127.repland.domain.team.TransferRecord>,
    val trace: List<String>,
) {
    val summaryLine: String = buildString {
        append(requestKind.label)
        if (draft != null) append("，草案 ${draft.placements.size} 段")
        if (proposedLedgerEntries.isNotEmpty()) append("，台账 +${proposedLedgerEntries.size} 条")
        if (weeklyReport != null) append("，周报草稿就绪")
    }
}

// ---------- per-agent 记忆（照搬 AgentMemory 模式） ----------

data class TeamMemoryWrite(val key: String, val value: String)

class TeamAgentMemory(
    val agentId: TeamAgentId,
    val runId: String,
    seed: Map<String, String> = emptyMap(),
) {
    private val facts = LinkedHashMap<String, String>(seed)
    private val writes = mutableListOf<TeamMemoryWrite>()

    fun recall(key: String): String? = facts[key]
    fun recallInt(key: String): Int? = facts[key]?.toIntOrNull()
    fun note(key: String, value: String) {
        if (facts[key] == value) return
        facts[key] = value
        writes += TeamMemoryWrite(key, value)
    }
    fun note(key: String, value: Int) = note(key, value.toString())
    fun written(): List<TeamMemoryWrite> = writes.toList()
    fun snapshot(): Map<String, String> = LinkedHashMap(facts)
}

class TeamMemoryBank(
    private val maxFactsPerAgent: Int = 24,
    private val maxEpisodesPerAgent: Int = 6,
) {
    private val facts = HashMap<TeamAgentId, MutableMap<String, String>>()
    private val episodes = HashMap<TeamAgentId, MutableList<String>>()

    @Synchronized
    fun seed(agentId: TeamAgentId, runId: String): TeamAgentMemory =
        TeamAgentMemory(agentId, runId, facts[agentId]?.toMap().orEmpty())

    @Synchronized
    fun episodesOf(agentId: TeamAgentId): List<String> = episodes[agentId].orEmpty().toList()

    @Synchronized
    fun commit(memory: TeamAgentMemory, summary: String) {
        val store = facts.getOrPut(memory.agentId) { LinkedHashMap() }
        store.putAll(memory.snapshot())
        while (store.size > maxFactsPerAgent) store.remove(store.keys.firstOrNull() ?: break)
        val list = episodes.getOrPut(memory.agentId) { mutableListOf() }
        list += summary
        while (list.size > maxEpisodesPerAgent) list.removeAt(0)
    }
}

/** 会话上下文：谁、要干什么、原话是什么。 */
data class TeamSessionContext(
    val requestKind: TeamRequestKind,
    val actorId: String,
    val rawText: String = "",
    val targetTaskId: String? = null,
    val toMemberId: String? = null,
    val weekStart: java.time.LocalDate? = null,
    val at: LocalDateTime,
    /** 本地成员的真实时间（只含分钟区间与负载）；没有个人数据时为 null。 */
    val personalTime: com.swan1127.repland.domain.team.PersonalTimeInput? = null,
)
