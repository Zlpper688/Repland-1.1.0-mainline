package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentActionResult
import com.swan1127.repland.domain.model.AgentToolKind
import com.swan1127.repland.domain.model.AgentToolParam
import com.swan1127.repland.domain.model.AgentToolResult
import com.swan1127.repland.domain.model.AgentToolSpec
import com.swan1127.repland.domain.model.AgentToolbox
import com.swan1127.repland.domain.competition.AwardGrade
import com.swan1127.repland.domain.competition.AwardInput
import com.swan1127.repland.domain.competition.AwardLevel
import com.swan1127.repland.domain.competition.CompetitionAward
import com.swan1127.repland.domain.competition.CompetitionCatalogEntry
import com.swan1127.repland.domain.competition.CompetitionInstance
import com.swan1127.repland.domain.competition.CompetitionTracking
import com.swan1127.repland.domain.competition.CompetitionWebFetcher
import com.swan1127.repland.domain.competition.PointsCalculator
import com.swan1127.repland.domain.competition.SchoolCompetitionSeed
import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.CompetitionProfileCard
import com.swan1127.repland.domain.team.CompetitionStages
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.PersonalTimeInput
import com.swan1127.repland.domain.team.PersonCardBook
import com.swan1127.repland.domain.team.Phase
import com.swan1127.repland.domain.team.Placement
import com.swan1127.repland.domain.team.ProfileSlotGroup
import com.swan1127.repland.domain.team.TaskRank
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamMemoryKind
import com.swan1127.repland.domain.team.TeamNeed
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamPlanDraft
import com.swan1127.repland.domain.team.TeamRosterBook
import com.swan1127.repland.domain.team.TeamScheduler
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.domain.team.TeammateMatchEngine
import com.swan1127.repland.domain.team.agents.SchedulerOps
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 团队域的助手工具面（§6）：当前身份能读的读、能写的写，全部走 [TeamCollabState] 的权限入口。
 *
 * 两条纪律：
 * - 写入不直接落库，先作为 [AgentAction] 排队，用户确认后才在 [runWrite] 里执行；
 * - 排期不由模型定落点：plan_team_week 调确定性调度器生成草案，再作为派生动作请用户确认。
 */
class TeamToolbox(
    private val state: TeamCollabState,
    private val actorId: String,
    private val today: LocalDate = LocalDate.now(),
    /** 我的队友名册与资料卡（个人域只读）：按需求匹配、自我介绍都用它。 */
    private val roster: TeamRosterBook = TeamRosterBook(),
    private val cards: PersonCardBook? = null,
    /** 真实课表/个人安排；只在排期时取，读不到就退回成员自报空闲。 */
    private val personalTime: suspend () -> PersonalTimeInput? = { null },
    /** 内置竞赛库（个人域，不经过队伍状态）：种子快照 + 本机追踪快照。 */
    private val competitionSeed: SchoolCompetitionSeed? = null,
    private val competitionTracking: List<CompetitionTracking> = emptyList(),
    /** 助手确认路径的写回调：关注比赛 / 登记获奖，落库由 ViewModel 直写仓库。 */
    private val followCompetition: suspend (CompetitionInstance) -> Unit = {},
    private val registerAward: suspend (CompetitionAward) -> Unit = {},
    /** 获奖履历快照：与种子/追踪一起构成竞赛的「内部知识库」。 */
    private val competitionAwards: List<CompetitionAward> = emptyList(),
    /** 联网抓取能力（src/remote 反射装载）；正式版没有，工具如实报没有联网能力。 */
    private val competitionWebFetcher: CompetitionWebFetcher? = null,
) : AgentToolbox {

    private fun actorCard(): MemberCard? = state.members.firstOrNull { it.memberId == actorId }
    private fun has(permission: TeamPermission): Boolean = actorCard()?.hasPermission(permission) == true
    private fun isCaptain(): Boolean = actorCard()?.isCaptain == true

    // ------------------------------------------------------------------ specs

    override fun specs(): List<AgentToolSpec> = listOf(
        AgentToolSpec(
            name = "team_overview", label = "队伍概况",
            description = "读取队伍、比赛截止、阶段、成员、任务统计、本周排期、异常与团队记忆。回答任何现状类问题先调它。",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            name = "team_tasks", label = "任务清单",
            description = "读取任务清单（可按状态/成员过滤），含任务编号、负责人、截止、工时、等级、阶段。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("filter", "active 只看进行中/待办（默认）、done 只看已完成、all 全部", enumValues = listOf("active", "done", "all")),
                AgentToolParam("member", "只看某人的任务：成员编号或名字"),
            ),
        ),
        AgentToolSpec(
            name = "team_schedule", label = "团队排期",
            description = "读取未来若干天的团队排期（谁、哪天、几点到几点、做什么）；私有时间只显示为「在忙」。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("days", "往后看几天，默认 7，最多 30"),
                AgentToolParam("member", "只看某人的排期：成员编号或名字"),
            ),
        ),
        AgentToolSpec(
            name = "team_member_load", label = "成员负载",
            description = "读取每位成员的进行中任务数、本周已排工时、课表置信度与权限；用于判断该把任务交给谁。",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            name = "team_memory", label = "团队记忆",
            description = "读取团队记忆条目（档案要点/负责说明/会议结论），全队可读。",
            kind = AgentToolKind.READ,
            params = listOf(AgentToolParam("kind", "只看一种：PROFILE/RESPONSIBILITY/MEETING", enumValues = listOf("PROFILE", "RESPONSIBILITY", "MEETING"))),
        ),
        AgentToolSpec(
            name = "team_weekly_report", label = "周报数据",
            description = "按台账与任务状态生成周报数据（进展、风险、下周重点），只写项目不评价个人。",
            kind = AgentToolKind.READ,
            params = listOf(AgentToolParam("weekStart", "周一起始日 yyyy-MM-dd，默认本周")),
        ),
        AgentToolSpec(
            name = "team_profile", label = "档案卡",
            description = "读取比赛档案卡（报名截止、提交物、评审规则、补充区）与缺失的关键槽位。",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            name = "team_meeting_slots", label = "会议候选",
            description = "计算全员都空闲的会议候选时段：给了日期就列当天，没给就按接受度给未来一周的推荐时段。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("date", "日期 yyyy-MM-dd；不填则推荐未来一周"),
                AgentToolParam("minutes", "会议时长（分钟），默认 60"),
            ),
        ),
        AgentToolSpec(
            name = "match_teammates", label = "按需求找队友",
            description = "从我的队友名册里按需求找候选人：只按角色、分工、备注、空闲与共同参赛次数排序，并给出理由；不编造资料。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("need", "队伍缺什么：任务或角色关键词", required = true),
                AgentToolParam("hours", "每周需要投入的小时数，可选"),
            ),
        ),
        AgentToolSpec(
            name = "competition_search", label = "查比赛",
            description = "查内置竞赛库里本校的比赛：进行中/即将开始的届次窗口与截止，或按关键词查加分目录（A/B 类、主办、官网、目录内外）。回答「有什么比赛」「XX 比赛什么时候截止/加不加分」先调它。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("query", "按名称关键词查，如 数学建模；不填列进行中/即将开始"),
                AgentToolParam("scope", "ongoing 进行中 / upcoming 即将开始 / catalogue 只查加分目录（默认全部届次）", enumValues = listOf("ongoing", "upcoming", "catalogue")),
            ),
        ),
        AgentToolSpec(
            name = "competition_deadlines", label = "我的比赛截止",
            description = "读取我关注的比赛与截止：状态、提醒开关、还剩几天，以及最近一个月内开始但还没关注的比赛。回答「我最近有什么比赛」「快到什么截止了」先调它。",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            name = "competition_knowledge", label = "竞赛知识检索",
            description = "检索项目内部知识库：本校加分目录、按生效年份版本化的加分口径（学校政策）、我关注的比赛与已登记的获奖。回答「XX 加不加分」「口径是什么」先查它；需要最新信息再用 competition_web 抓官网核对。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("query", "按名称关键词过滤目录/届次，如 数学建模"),
                AgentToolParam("kind", "catalogue 只查加分目录 / rule 只查口径版本 / track 只查我的追踪 / award 只查获奖（默认全部）", enumValues = listOf("catalogue", "rule", "track", "award")),
            ),
        ),
        AgentToolSpec(
            name = "competition_web", label = "抓官网资讯",
            description = "抓取一个公开网页的标题与正文：按比赛名/编号优先用内置库登记的官网，或直接给网址；报名通知、学校政策等以官网最新内容为准。引用抓取结果必须带上工具给出的来源与抓取时间；抓不到就如实说，不编造。",
            kind = AgentToolKind.READ,
            params = listOf(
                AgentToolParam("query", "比赛名或编号：用内置库登记的官网"),
                AgentToolParam("url", "直接要抓的网址 http(s)://…"),
            ),
        ),

        AgentToolSpec(
            name = "create_team_task", label = "新建任务",
            description = "在基线里新建一项任务；不指定负责人时由排期按负载定人。需要「创建任务」或「改基线」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("title", "任务名", required = true),
                AgentToolParam("hours", "预估工时（小时），默认 2"),
                AgentToolParam("tier", "LONG_TERM 长期 / MID_TERM 中期 / SHORT_TERM 短期（会议类）"),
                AgentToolParam("rank", "MUST 必须 / SHOULD 应该 / COULD 可以"),
                AgentToolParam("phaseId", "挂到哪个阶段"),
                AgentToolParam("assignee", "指定负责人：成员编号或名字；不填=待认领"),
            ),
            summarize = { args ->
                "新建任务「${args.optString("title")}」" +
                    args.optString("assignee").takeIf { it.isNotBlank() }?.let { "，负责人 $it" }.orEmpty()
            },
            validate = { args ->
                guard(TeamPermission.CREATE_TASK.takeIf { !has(TeamPermission.EDIT_BASELINE) })
                    ?: when {
                        args.optString("title").isBlank() -> "缺少任务名"
                        args.has("phaseId") && args.optString("phaseId").isNotBlank() &&
                            state.phases.none { it.phaseId == args.optString("phaseId") } -> "没有这个阶段：${args.optString("phaseId")}"
                        args.has("assignee") && args.optString("assignee").isNotBlank() &&
                            resolveMember(args.optString("assignee")) == null -> "队里没有这个人：${args.optString("assignee")}"
                        args.has("rank") && rankOf(args.optString("rank")) == TaskRank.WONT -> "取消任务请用 cancel_team_task"
                        else -> null
                    }
            },
        ),
        AgentToolSpec(
            name = "update_team_task", label = "修改任务",
            description = "改已有任务的名称、工时、等级或所属阶段（只写要改的字段）。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("title", "新任务名"),
                AgentToolParam("hours", "新工时（小时）"),
                AgentToolParam("rank", "MUST/SHOULD/COULD"),
                AgentToolParam("phaseId", "新阶段编号；留空表示不挂阶段"),
            ),
            summarize = { args -> "修改任务「${args.optString("taskId")}」" },
            validate = { args ->
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    resolveTask(args.optString("taskId")) == null -> "找不到任务：${args.optString("taskId")}"
                    args.has("rank") && rankOf(args.optString("rank")) == TaskRank.WONT -> "取消任务请用 cancel_team_task"
                    args.has("phaseId") && args.optString("phaseId").isNotBlank() &&
                        state.phases.none { it.phaseId == args.optString("phaseId") } -> "没有这个阶段：${args.optString("phaseId")}"
                    args.has("hours") && args.optDouble("hours", 0.0) <= 0.0 -> "工时必须大于 0"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "set_task_deadline", label = "改截止",
            description = "设定任务截止日（可带小时）；超过比赛截止会被拒绝。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("date", "截止日 yyyy-MM-dd", required = true),
                AgentToolParam("hour", "截止小时 0-23，可选"),
            ),
            summarize = { args -> "把「${args.optString("taskId")}」截止改为 ${args.optString("date")}" },
            validate = { args ->
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    resolveTask(args.optString("taskId")) == null -> "找不到任务：${args.optString("taskId")}"
                    dateOf(args.optString("date")) == null -> "日期格式不对：${args.optString("date")}（要 yyyy-MM-dd）"
                    dateOf(args.optString("date"))?.let { date ->
                        state.competitionDeadline?.let { end -> date.isAfter(end) } == true
                    } == true -> "截止超过比赛截止 ${state.competitionDeadline}，请重新选择"
                    args.has("hour") && args.optInt("hour", 0) !in 0..23 -> "小时要在 0-23"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "cancel_team_task", label = "取消任务",
            description = "把任务降为「不做」（可撤销）；MUST 级任务不能取消。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(AgentToolParam("taskId", "任务编号或名字", required = true)),
            summarize = { args -> "取消任务「${args.optString("taskId")}」" },
            validate = { args ->
                val task = resolveTask(args.optString("taskId"))
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    task == null -> "找不到任务：${args.optString("taskId")}"
                    !task.cancellable -> "「${task.title}」是 MUST 级任务，不能取消；只能调整截止或转派"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "restore_team_task", label = "撤销取消",
            description = "把已取消的任务恢复为待办。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(AgentToolParam("taskId", "任务编号或名字", required = true)),
            summarize = { args -> "恢复任务「${args.optString("taskId")}」" },
            validate = { args ->
                val task = resolveTask(args.optString("taskId"))
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    task == null -> "找不到任务：${args.optString("taskId")}"
                    task.status != TeamTaskStatus.WONT -> "「${task.title}」没有被取消，无需恢复"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "transfer_team_task", label = "转派任务",
            description = "把任务的执行人转给另一位成员（基线责任不变，自动留痕，本人可提异议）。需要「排期与转派」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("toMember", "转给谁：成员编号或名字", required = true),
            ),
            summarize = { args -> "把「${args.optString("taskId")}」转派给 ${args.optString("toMember")}" },
            validate = { args ->
                val task = resolveTask(args.optString("taskId"))
                guard(TeamPermission.SCHEDULE_TRANSFER) ?: when {
                    task == null -> "找不到任务：${args.optString("taskId")}"
                    resolveMember(args.optString("toMember")) == null -> "队里没有这个人：${args.optString("toMember")}"
                    !task.active -> "「${task.title}」已经结束，不能转派"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "report_task_done", label = "报告完成",
            description = "报告任务完成（本人、认领未分配任务、队长或有排期权限者可用）；台账自动留痕，未来排期块作废。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("note", "用户原话或完成说明，可选"),
            ),
            summarize = { args -> "报告「${args.optString("taskId")}」完成" },
            validate = { args ->
                val task = resolveTask(args.optString("taskId"))
                when {
                    requireWritable() != null -> requireWritable()
                    task == null -> "找不到任务：${args.optString("taskId")}"
                    task.status == TeamTaskStatus.DONE -> "「${task.title}」已经完成了"
                    !task.active -> "「${task.title}」状态是 ${task.status.label}，不能报告完成"
                    !(isCaptain() || task.assignedTo == null || task.assignedTo == actorId ||
                        has(TeamPermission.SCHEDULE_TRANSFER)) -> "「${task.title}」不在你名下，请本人报告或让队长操作"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "raise_team_objection", label = "提异议",
            description = "对转派给自己的任务提异议，进入队长裁决队列。只能对转派给当前身份的任务提。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("reason", "异议理由（用户原话）", required = true),
            ),
            summarize = { args -> "对「${args.optString("taskId")}」提异议" },
            validate = { args ->
                val task = resolveTask(args.optString("taskId"))
                val transfer = task?.let { t -> state.transfers.lastOrNull { it.taskId == t.taskId && it.toMemberId == actorId } }
                when {
                    requireWritable() != null -> requireWritable()
                    task == null -> "找不到任务：${args.optString("taskId")}"
                    args.optString("reason").isBlank() -> "缺少异议理由"
                    transfer == null -> "只能对转派给你的任务提异议"
                    state.objections.any { it.taskId == task.taskId && it.resolution == com.swan1127.repland.domain.team.ObjectionResolution.PENDING } ->
                        "这条转派已有待裁决的异议"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "resolve_team_objection", label = "裁决异议",
            description = "队长或排期权限者裁决异议：维持转派或收回转派。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("objectionId", "异议编号", required = true),
                AgentToolParam("keepTransfer", "true 维持转派 / false 收回转派", required = true),
            ),
            summarize = { args -> "裁决异议「${args.optString("objectionId")}」" },
            validate = { args ->
                val objection = state.objections.firstOrNull { it.objectionId == args.optString("objectionId") }
                guard(TeamPermission.SCHEDULE_TRANSFER) ?: when {
                    objection == null -> "找不到异议：${args.optString("objectionId")}"
                    objection.resolution != com.swan1127.repland.domain.team.ObjectionResolution.PENDING -> "该异议已裁决"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "add_team_phase", label = "新增阶段",
            description = "新增一个比赛阶段（起止日期）。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("name", "阶段名", required = true),
                AgentToolParam("start", "开始日 yyyy-MM-dd", required = true),
                AgentToolParam("end", "结束日 yyyy-MM-dd", required = true),
            ),
            summarize = { args -> "新增阶段「${args.optString("name")}」${args.optString("start")}~${args.optString("end")}" },
            validate = { args ->
                val start = dateOf(args.optString("start"))
                val end = dateOf(args.optString("end"))
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    args.optString("name").isBlank() -> "缺少阶段名"
                    start == null || end == null -> "起止日期要 yyyy-MM-dd"
                    end.isBefore(start) -> "结束日不能早于开始日"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "apply_team_template", label = "套用比赛环节模板",
            description = "把比赛拆成标准环节与基线任务：math_modeling（数模 3 天赛）/ innovation（创新类）/ generic（通用三阶段）。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("template", "math_modeling / innovation / generic", required = true),
                AgentToolParam("start", "开始日 yyyy-MM-dd，默认今天"),
            ),
            summarize = { args ->
                val template = CompetitionStages.byId(args.optString("template").trim())
                "套用「${template?.label ?: args.optString("template")}」环节模板"
            },
            validate = { args ->
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    CompetitionStages.byId(args.optString("template").trim()) == null ->
                        "模板只有 math_modeling / innovation / generic"
                    args.optString("start").isNotBlank() && dateOf(args.optString("start")) == null ->
                        "开始日要 yyyy-MM-dd"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "set_team_responsibility", label = "负责说明",
            description = "写成员的负责说明（这个项目里 TA 管什么）。队长可改任何人，队员只能改自己。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("member", "成员编号或名字，默认当前身份"),
                AgentToolParam("text", "负责说明", required = true),
            ),
            summarize = { args -> "更新 ${args.optString("member").ifBlank { "我" }} 的负责说明" },
            validate = { args ->
                val target = args.optString("member").takeIf { it.isNotBlank() }?.let { resolveMember(it) }
                    ?: actorCard()
                when {
                    requireWritable() != null -> requireWritable()
                    args.optString("text").isBlank() -> "负责说明不能为空"
                    target == null -> "找不到成员：${args.optString("member")}"
                    !(isCaptain() || target.memberId == actorId) -> "只能改自己的负责说明；替他人修改需队长操作"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "set_team_permission", label = "成员权限",
            description = "队长按人授予/收回一项权限；头衔只是标签，权限才作数。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("member", "成员编号或名字", required = true),
                AgentToolParam("permission", "权限名，如 SCHEDULE_TRANSFER、EDIT_LEDGER", required = true),
                AgentToolParam("enabled", "true 授予 / false 收回", required = true),
            ),
            summarize = { args ->
                "${args.optString("member")} 的「${args.optString("permission")}」" +
                    if (args.optBoolean("enabled", true)) "授权" else "收回"
            },
            validate = { args ->
                val target = resolveMember(args.optString("member"))
                when {
                    requireWritable() != null -> requireWritable()
                    !isCaptain() -> "只有队长能调整成员权限"
                    target == null -> "队里没有这个人：${args.optString("member")}"
                    target.isCaptain -> "队长默认全权限，无需配置"
                    permissionOf(args.optString("permission")) == null -> "没有这个权限：${args.optString("permission")}"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "add_team_member", label = "加队员",
            description = "邀请一位新队员（默认无权限，由队长按人配置）。需要「邀请移除成员」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("name", "队员名字", required = true),
                AgentToolParam("roleTag", "头衔标签，如 建模、答辩，可选"),
                AgentToolParam("responsibility", "负责说明，可选"),
            ),
            summarize = { args -> "邀请 ${args.optString("name")} 入队" },
            validate = { args ->
                guard(TeamPermission.MANAGE_MEMBERS) ?: when {
                    args.optString("name").isBlank() -> "缺少队员名字"
                    state.members.any { it.name == args.optString("name").trim() } -> "队里已经有同名成员"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "remove_team_member", label = "移除队员",
            description = "移除队员：名下任务自动转派给队长，台账永久留痕。需要「邀请移除成员」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(AgentToolParam("member", "成员编号或名字", required = true)),
            summarize = { args -> "移除队员 ${args.optString("member")}" },
            validate = { args ->
                val target = resolveMember(args.optString("member"))
                guard(TeamPermission.MANAGE_MEMBERS) ?: when {
                    target == null -> "队里没有这个人：${args.optString("member")}"
                    target.isCaptain -> "队长不能移除自己；如需结束请用归档"
                    target.memberId == actorId -> "不能移除自己"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "write_team_memory", label = "写团队记忆",
            description = "写一条团队记忆：档案要点 / 负责说明 / 会议结论，全队可读。需要「记忆库写入」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("kind", "PROFILE / RESPONSIBILITY / MEETING", required = true),
                AgentToolParam("content", "内容", required = true),
            ),
            summarize = { args -> "记下「${args.optString("content").take(24)}」" },
            validate = { args ->
                guard(TeamPermission.MEMORY_WRITE) ?: when {
                    args.optString("content").isBlank() -> "记忆内容不能为空"
                    memoryKindOf(args.optString("kind")) == null -> "种类要 PROFILE / RESPONSIBILITY / MEETING"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "record_team_finance", label = "财务记账",
            description = "记一笔队伍支出/收入（元）。需要「财务记账」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("amountYuan", "金额（元），支出为负", required = true),
                AgentToolParam("reason", "用途", required = true),
            ),
            summarize = { args -> "记一笔 ¥${args.optDouble("amountYuan", 0.0)}：${args.optString("reason")}" },
            validate = { args ->
                guard(TeamPermission.FINANCE_BOOKKEEPING) ?: when {
                    args.optDouble("amountYuan", 0.0) == 0.0 -> "金额不能为 0"
                    args.optString("reason").isBlank() -> "缺少用途"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "add_team_link", label = "登记外链",
            description = "给任务登记作品外链（文档/仓库/网盘）。需要「文件外链管理」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("taskId", "任务编号或名字", required = true),
                AgentToolParam("url", "链接", required = true),
            ),
            summarize = { args -> "给「${args.optString("taskId")}」登记外链" },
            validate = { args ->
                guard(TeamPermission.MANAGE_EXTERNAL_LINK) ?: when {
                    resolveTask(args.optString("taskId")) == null -> "找不到任务：${args.optString("taskId")}"
                    !args.optString("url").startsWith("http") -> "链接要以 http(s) 开头"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "update_team_profile", label = "补档案卡",
            description = "把比赛材料里的关键信息补进档案卡槽位（报名截止、提交物、评审规则等）。需要「改基线与截止」权限。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("group", "槽位组：BASIC_INFO/TIMELINE/QUOTA/DELIVERABLES/REVIEW/RESOURCES", required = true),
                AgentToolParam("key", "字段键，如 signup_deadline", required = true),
                AgentToolParam("label", "显示名，如 报名截止", required = true),
                AgentToolParam("value", "内容", required = true),
            ),
            summarize = { args -> "补档案「${args.optString("label")}」=${args.optString("value").take(24)}" },
            validate = { args ->
                guard(TeamPermission.EDIT_BASELINE) ?: when {
                    groupOf(args.optString("group")) == null -> "槽位组不认识：${args.optString("group")}"
                    args.optString("key").isBlank() || args.optString("label").isBlank() || args.optString("value").isBlank() -> "键、显示名、内容都要填"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "plan_team_week", label = "整周排期",
            description = "按负载定人、空闲定时段、依赖倒排，生成整周排期草案。确认后只生成草案，不会直接写入；草案还要再确认一次才生效。",
            kind = AgentToolKind.WRITE,
            params = listOf(),
            summarize = { "生成整周排期草案（不会直接写入）" },
            validate = {
                guard(TeamPermission.SCHEDULE_TRANSFER) ?: when {
                    state.tasks.none { it.active } -> "没有进行中的任务可排，先建任务"
                    state.members.isEmpty() -> "队里还没有成员"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "apply_team_plan", label = "确认整周排期",
            hidden = true,
            description = "由 plan_team_week 生成的排期草案；这一步才真正写入个人日程。",
            kind = AgentToolKind.WRITE,
            summarize = { args ->
                val count = args.optJSONArray("placements")?.length() ?: 0
                "生效整周排期：$count 段安排"
            },
            validate = { args ->
                val placements = args.optJSONArray("placements")
                guard(TeamPermission.SCHEDULE_TRANSFER) ?: when {
                    placements == null || placements.length() == 0 -> "排期草案为空"
                    (0 until placements.length()).any { index ->
                        val item = placements.optJSONObject(index) ?: return@any true
                        resolveTask(item.optString("taskId")) == null || dateOf(item.optString("date")) == null ||
                            item.optInt("endMinute") <= item.optInt("startMinute")
                    } -> "排期草案里有失效的任务或时间，请重新排期"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "follow_competition", label = "关注比赛",
            description = "把一场比赛加进我的追踪，默认开启截止提醒（截止前一天 20:00 和当天 09:00 提醒）。届次编号先 competition_search 查。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("instanceKey", "届次编号或比赛名称", required = true),
            ),
            summarize = { args ->
                val instance = resolveCompetitionInstance(args.optString("instanceKey"))
                "关注比赛「${instance?.title ?: args.optString("instanceKey")}」"
            },
            validate = { args ->
                val raw = args.optString("instanceKey").trim()
                val instance = resolveCompetitionInstance(raw)
                when {
                    competitionSeed == null -> "内置竞赛库种子不可用，无法关注比赛"
                    instance == null -> "竞赛库里没有这场比赛：${raw.ifEmpty { "（没给届次编号或名称）" }}"
                    competitionTracking.any { it.instanceKey == instance.key } -> "「${instance.title}」已经在你的追踪里"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "register_award", label = "登记获奖",
            description = "把一条获奖登记进个人履历（不进队伍档案卡，不影响队友），并按获奖年度口径给推免/学分参考分。只登记用户说的事实。",
            kind = AgentToolKind.WRITE,
            params = listOf(
                AgentToolParam("title", "奖项或赛事名", required = true),
                AgentToolParam("level", "国际/国家级/省级/市级/校级", required = true),
                AgentToolParam("grade", "特等/一等/二等/三等/其他", required = true),
                AgentToolParam("awardYear", "获奖年份，默认今年"),
                AgentToolParam("isTeam", "true 团体 / false 个人，默认 false"),
                AgentToolParam("teamRank", "团体名次（前三名按 85/65/40% 计分）"),
                AgentToolParam("instanceKey", "届次编号，可选；填了会把获奖关联到这场追踪并按目录条目取口径"),
                AgentToolParam("note", "备注（队友名、证书信息等），可选"),
            ),
            summarize = { args ->
                val level = levelOf(args.optString("level"))?.label ?: args.optString("level")
                val grade = gradeOf(args.optString("grade"))?.label ?: args.optString("grade")
                "登记获奖「${args.optString("title")}」$level$grade"
            },
            validate = { args ->
                val year = if (args.has("awardYear")) args.optInt("awardYear", today.year) else today.year
                val anchoredRaw = args.optString("instanceKey").trim()
                when {
                    args.optString("title").isBlank() -> "缺少奖项名"
                    levelOf(args.optString("level")) == null ->
                        "级别要 国际/国家级/省级/市级/校级（或 INTERNATIONAL/NATIONAL/PROVINCIAL/MUNICIPAL/SCHOOL）"
                    gradeOf(args.optString("grade")) == null ->
                        "等次要 特等/一等/二等/三等/其他（或 SPECIAL/FIRST/SECOND/THIRD/OTHER）"
                    year !in 1990..today.year + 1 -> "获奖年份不对：$year"
                    anchoredRaw.isNotBlank() && competitionSeed != null && resolveCompetitionInstance(anchoredRaw) == null ->
                        "竞赛库里没有这场比赛：$anchoredRaw；可以不带届次直接登记"
                    else -> null
                }
            },
        ),
        AgentToolSpec(
            name = "confirm_draft", label = "按最新状态执行草案",
            description = "用户这句话明确授权执行当前待确认草案时调用；界面会按最新数据重新校验后落地，成功与否由执行结果决定",
            kind = AgentToolKind.CONTROL,
        ),
        AgentToolSpec(
            name = "cancel_draft", label = "取消待确认草案",
            description = "用户明确要放弃当前待确认草案时调用；草案作废，不会写入任何东西",
            kind = AgentToolKind.CONTROL,
        ),
    )

    // ------------------------------------------------------------------ reads

    override suspend fun runRead(name: String, arguments: JSONObject): AgentToolResult {
        val actor = actorCard() ?: return AgentToolResult(false, "当前身份不在队内")
        val text = when (name) {
            "team_overview" -> TeamPrompt.briefing(state, actorId, today, roster, cards)
            "team_tasks" -> renderTasks(arguments)
            "team_schedule" -> renderSchedule(arguments)
            "team_member_load" -> renderMemberLoad()
            "team_memory" -> renderMemories(arguments)
            "team_weekly_report" -> renderWeeklyReport(arguments)
            "team_profile" -> renderProfile()
            "team_meeting_slots" -> renderMeetingSlots(arguments)
            "match_teammates" -> renderTeammateMatches(arguments)
            "competition_search" -> renderCompetitionSearch(arguments)
            "competition_deadlines" -> renderCompetitionDeadlines()
            "competition_knowledge" -> renderCompetitionKnowledge(arguments)
            "competition_web" -> renderCompetitionWeb(arguments)
            else -> return AgentToolResult(false, "没有这个读取工具：$name")
        }
        return AgentToolResult(true, "（身份：${actor.name}）\n$text")
    }

    private fun renderTasks(args: JSONObject): String {
        val filter = args.optString("filter", "active")
        val member = args.optString("member").takeIf { it.isNotBlank() }?.let { raw ->
            resolveMember(raw) ?: return "队里没有这个人：$raw"
        }
        val tasks = state.tasks
            .filter { task ->
                when (filter) {
                    "done" -> task.status == TeamTaskStatus.DONE
                    "all" -> true
                    else -> task.active
                }
            }
            .filter { member == null || it.assignedTo == member.memberId }
            .sortedWith(
                compareBy(
                    { it.effectiveDeadline(state.phases, state.competitionDeadline) ?: LocalDate.MAX },
                    { it.taskId },
                ),
            )
        if (tasks.isEmpty()) return "没有符合条件的任务"
        return buildString {
            appendLine("任务 ${tasks.size} 项（最多列 20 项）：")
            tasks.take(20).forEach { task ->
                val deadline = task.effectiveDeadline(state.phases, state.competitionDeadline)?.toString() ?: "无截止"
                val owner = task.assignedTo?.let { id -> state.members.firstOrNull { it.memberId == id }?.name } ?: "待认领"
                val phase = task.phaseId?.let { id -> state.phases.firstOrNull { it.phaseId == id }?.name }
                appendLine(
                    "- ${task.taskId}「${task.title}」${task.status.label}｜$owner｜截止 $deadline｜" +
                        "${fmtHours(task.estimatedHours)}｜${task.rank.label}" + phase?.let { "｜$it" }.orEmpty(),
                )
            }
        }.trimEnd()
    }

    private fun renderSchedule(args: JSONObject): String {
        val days = args.optInt("days", 7).coerceIn(1, 30)
        val member = args.optString("member").takeIf { it.isNotBlank() }?.let { raw ->
            resolveMember(raw) ?: return "队里没有这个人：$raw"
        }
        val end = today.plusDays(days.toLong() - 1)
        val blocks = state.blocks
            .filter { it.sourceTeamId == state.teamId && !it.date.isBefore(today) && !it.date.isAfter(end) }
            .filter { member == null || it.memberId == member.memberId }
            .sortedWith(compareBy({ it.date }, { it.startMinute }))
        if (blocks.isEmpty()) return "$today ~ $end 暂无团队安排"
        return buildString {
            appendLine("$today ~ $end 共 ${blocks.size} 段（最多列 30 段）：")
            blocks.take(30).forEach { block ->
                val name = state.members.firstOrNull { it.memberId == block.memberId }?.name ?: block.memberId
                val label = if (block.visibility == BlockVisibility.PRIVATE_BUSY) "在忙" else block.label ?: "团队安排"
                appendLine("- ${block.date} $name ${fmtMinute(block.startMinute)}-${fmtMinute(block.endMinute)}「$label」")
            }
        }.trimEnd()
    }

    private fun renderMemberLoad(): String {
        val weekEnd = today.plusDays(6)
        val load = TeamScheduler.loadByMember(state.members, emptyList(), state.blocks, state.teamId)
        val weekLoad = state.blocks
            .filter { it.sourceTeamId == state.teamId && !it.date.isBefore(today) && !it.date.isAfter(weekEnd) }
            .groupBy { it.memberId }
            .mapValues { (_, blocks) -> blocks.sumOf { it.endMinute - it.startMinute } / 60.0 }
        return buildString {
            appendLine("成员负载（本周为团队已排工时）：")
            state.members.forEach { member ->
                val mine = state.tasks.filter { it.assignedTo == member.memberId && it.active }
                appendLine(
                    "- ${member.memberId} ${member.name}（${if (member.isCaptain) "队长" else "队员"}）｜" +
                        "进行中 ${mine.count { it.status == TeamTaskStatus.IN_PROGRESS }} · 待办 ${mine.count { it.status == TeamTaskStatus.NOT_STARTED }}｜" +
                        "本周 ${fmtHours(weekLoad[member.memberId] ?: 0.0)}｜累计 ${fmtHours(load[member.memberId] ?: 0.0)}｜" +
                        (if (member.lowConfidence) "课表未导入（低置信度）" else "课表已导入") +
                        "｜每日容量 ${member.dailyCapacityHours}h",
                )
            }
        }.trimEnd()
    }

    private fun renderMemories(args: JSONObject): String {
        val kind = args.optString("kind").takeIf { it.isNotBlank() }?.let { raw ->
            memoryKindOf(raw) ?: return "种类要 PROFILE / RESPONSIBILITY / MEETING"
        }
        val entries = state.memories
            .filter { kind == null || it.kind == kind }
            .sortedByDescending { it.at }
        if (entries.isEmpty()) return "团队记忆还是空的"
        return entries.take(15).joinToString("\n") { entry ->
            val author = state.members.firstOrNull { it.memberId == entry.authorId }?.name ?: entry.authorId
            "- [${entry.kind.label}] ${entry.content}（$author，${entry.at.toLocalDate()}）"
        }
    }

    private fun renderWeeklyReport(args: JSONObject): String {
        val weekStart = args.optString("weekStart").takeIf { it.isNotBlank() }?.let { raw ->
            dateOf(raw) ?: return "日期格式不对：$raw"
        } ?: today.minusDays((today.dayOfWeek.value - 1).toLong())
        val report = state.weeklyReport(weekStart)
        return buildString {
            appendLine("周报 ${report.weekStart} ~ ${report.weekStart.plusDays(6)}")
            appendLine("进展：")
            report.progressSummary.forEach { appendLine("- $it") }
            appendLine("风险：")
            report.riskRadar.forEach { appendLine("- $it") }
            appendLine("下周重点：")
            report.nextWeekFocus.forEach { appendLine("- $it") }
            appendLine("证据：台账 ${report.stats["ledgerEntries"] ?: 0} 条（执行 ${report.stats["executionBlocks"] ?: 0}、" +
                "状态 ${report.stats["statusChanges"] ?: 0}、外链 ${report.stats["externalLinks"] ?: 0}）")
        }.trimEnd()
    }

    private fun renderProfile(): String {
        val card = state.profileCard ?: return "档案卡还没建；可以请队长手动建档，或上传比赛材料后再补。"
        return buildString {
            appendLine("档案卡：${card.competitionName}")
            val missing = card.missingCritical()
            if (missing.isNotEmpty()) appendLine("待补充关键项：${missing.joinToString("、")}")
            card.fields.forEach { (group, fields) ->
                val known = fields.filterNot { it.isUnknown }
                if (known.isNotEmpty()) {
                    appendLine("【${group.label}】" + known.joinToString("；") { "${it.label}：${it.value}" })
                }
            }
            if (card.extras.isNotEmpty()) {
                appendLine("【补充区】" + card.extras.joinToString("；") { "${it.key}：${it.value}" })
            }
        }.trimEnd()
    }

    private fun renderMeetingSlots(args: JSONObject): String {
        val minutes = args.optInt("minutes", 60).coerceIn(15, 240)
        val raw = args.optString("date").takeIf { it.isNotBlank() }
        if (raw == null) {
            val suggestions = SchedulerOps.meetingSuggestions(state, today, minutes)
            if (suggestions.isEmpty()) return "未来一周没有全员都空闲的 $minutes 分钟时段"
            return "未来一周全员可约（$minutes 分钟，按接受度排序）：\n" + suggestions.joinToString("\n") { suggestion ->
                val slot = suggestion.slot
                val weekday = "一二三四五六日"[slot.date.dayOfWeek.value - 1]
                "- ${slot.date.monthValue}-${slot.date.dayOfMonth} 周$weekday " +
                    "${fmtMinute(slot.startMinute)}-${fmtMinute(slot.endMinute)}"
            }
        }
        val date = dateOf(raw) ?: return "日期格式不对：$raw"
        val slots = SchedulerOps.meetingCandidates(state, date, minutes)
        if (slots.isEmpty()) return "$date 没有全员都空闲的 $minutes 分钟时段"
        return "$date 全员空闲候选（$minutes 分钟）：\n" + slots.joinToString("\n") { slot ->
            "- ${fmtMinute(slot.startMinute)}-${fmtMinute(slot.endMinute)}"
        }
    }

    private fun renderTeammateMatches(args: JSONObject): String {
        val need = TeamNeed(
            title = args.optString("need").trim(),
            hoursPerWeek = args.optDouble("hours", 0.0).coerceAtLeast(0.0),
        )
        if (!need.hasText) return "先说要找什么样的人：缺什么角色、要做什么"
        val inTeam = state.members.map { TeamRosterBook.keyOf(it.name) }.toSet()
        val ranked = TeammateMatchEngine.rank(roster.people, need, inTeam).take(6)
        if (ranked.isEmpty()) return "名册里没有可匹配的人；先把想了解的人记进名册，或先攒下一起参赛的记录。"
        return buildString {
            appendLine(
                "按「${need.title}」匹配（${if (need.hoursPerWeek > 0) "每周 ${fmtHours(need.hoursPerWeek)}" else "未限定工时"}，" +
                    "只列名册里的人）：",
            )
            ranked.forEach { match ->
                val role = match.person.roleTag?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                appendLine("- ${match.person.name}$role｜${match.reasons.joinToString("；")}")
            }
        }.trimEnd()
    }

    private fun renderCompetitionSearch(args: JSONObject): String {
        val seed = competitionSeed ?: return "内置竞赛库在本机不可用（种子未加载）"
        val query = args.optString("query").trim()
        val scope = args.optString("scope", "all")
        if (scope == "catalogue") {
            val entries = if (query.isEmpty()) seed.catalogue.take(15) else seed.searchCatalogue(query)
            return if (entries.isEmpty()) "加分目录里没有名称含「$query」的赛事" else competitionHeader(seed) + renderCatalogueRows(entries)
        }
        val pool = when (scope) {
            "ongoing" -> seed.ongoing(today)
            "upcoming" -> seed.upcoming(today)
            else -> seed.ongoing(today) + seed.upcoming(today)
        }
        val norm = SchoolCompetitionSeed.normalize(query)
        val matched = if (norm.isEmpty()) pool else pool.filter { SchoolCompetitionSeed.normalize(it.title).contains(norm) }
        if (matched.isNotEmpty()) return competitionHeader(seed) + renderInstances(matched)
        if (norm.isEmpty()) return "平台快照里没有进行中或待开始的比赛；用 scope=catalogue 可查加分目录"
        val catalogue = seed.searchCatalogue(query)
        return if (catalogue.isEmpty()) {
            "竞赛库里没有名称含「$query」的届次或目录条目"
        } else {
            "届次里没找到「$query」，加分目录里有相关赛事：\n" + renderCatalogueRows(catalogue)
        }
    }

    private fun competitionHeader(seed: SchoolCompetitionSeed): String {
        val rule = seed.ruleVersionFor(today.year) ?: seed.ruleVersions.lastOrNull()
        val points = rule?.let { r ->
            val t = r.tuimian
            "口径（${r.effectiveFrom} 年起）：国一 ${fmtPts(t.nationalFirst)} / 国二 ${fmtPts(t.nationalSecond)} / " +
                "国三 ${fmtPts(t.nationalThird)}；省一 ${fmtPts(t.provincialFirst)} / 省二 ${fmtPts(t.provincialSecond)}（分/项）"
        } ?: "没有可用的加分口径版本"
        return "${seed.schoolName} · $points\n"
    }

    private fun renderInstances(instances: List<CompetitionInstance>): String {
        val seed = competitionSeed ?: return ""
        val lines = instances.take(15).map { inst ->
            val entry = seed.entry(inst.catalogueKey)
            val window = when {
                inst.parsedStart != null && inst.parsedEnd != null -> "${inst.parsedStart} ~ ${inst.parsedEnd}"
                inst.parsedEnd != null -> "至 ${inst.parsedEnd}"
                else -> "窗口未知"
            }
            val left = inst.parsedEnd?.let { end ->
                val days = ChronoUnit.DAYS.between(today, end)
                when {
                    days < 0 -> "已过期"
                    else -> "剩 $days 天"
                }
            }
            "- [${inst.key}]「${inst.title}」${inst.level} · ${inst.state} · $window" +
                left?.let { " · $it" }.orEmpty() + " · ${categoryLabel(entry)}"
        }
        return lines.joinToString("\n") + if (instances.size > 15) "\n（只列前 15 条，可用 query 收窄）" else ""
    }

    private fun renderCatalogueRows(entries: List<CompetitionCatalogEntry>): String =
        entries.take(15).joinToString("\n") { entry ->
            val organizer = listOf(entry.nationalOrganizer, entry.provincialOrganizer)
                .filter { it.isNotBlank() }.joinToString("/")
            "- [${entry.key}]「${entry.name}」${categoryLabel(entry)} · ${catalogLine(entry)}" +
                organizer.takeIf { it.isNotBlank() }?.let { " · 主办 $it" }.orEmpty() +
                entry.officialSite.takeIf { it.isNotBlank() }?.let { " · 官网 $it" }.orEmpty()
        }

    private fun categoryLabel(entry: CompetitionCatalogEntry?): String = when (entry?.category) {
        "A" -> "A类"
        "B" -> "B类"
        else -> "目录外"
    }

    private fun catalogLine(entry: CompetitionCatalogEntry): String = when {
        entry.nationalInCatalog && entry.provincialInCatalog -> "国家+省目录"
        entry.nationalInCatalog -> "仅国家目录"
        entry.provincialInCatalog -> "仅省目录"
        else -> "非官方目录（降一档计）"
    }

    private fun renderCompetitionDeadlines(): String {
        val active = competitionTracking.filter { it.status.remindersActive }
            .sortedWith(compareBy({ it.deadlineEpochDay ?: Long.MAX_VALUE }, { it.title }))
        val settled = competitionTracking.filterNot { it.status.remindersActive }
        val upcoming = competitionSeed?.upcoming(today).orEmpty()
            .filter { inst -> competitionTracking.none { it.instanceKey == inst.key } }
            .filter { inst -> inst.parsedStart?.isBefore(today.plusDays(30)) == true }
        if (active.isEmpty() && settled.isEmpty() && upcoming.isEmpty()) {
            return "追踪里还没有比赛；用 competition_search 查最近有哪些可以关注"
        }
        return buildString {
            if (active.isNotEmpty()) {
                appendLine("我关注的比赛（${active.size}）：")
                active.forEach { row ->
                    val deadline = row.deadlineEpochDay?.let(LocalDate::ofEpochDay)
                    val left = deadline?.let { ChronoUnit.DAYS.between(today, it) }
                    appendLine(
                        "- 「${row.title}」${row.level} · ${row.status.label} · " +
                            (deadline?.toString() ?: "截止未知") +
                            (left?.let { if (it >= 0) "（剩 $it 天）" else "（已过 ${-it} 天）" } ?: "") +
                            if (row.remindEnabled && deadline != null) " · 提醒开" else "",
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                appendLine("最近 30 天内开始、还没关注的比赛（最多 8 条）：")
                upcoming.take(8).forEach { inst ->
                    appendLine("- [${inst.key}]「${inst.title}」${inst.level} · ${inst.parsedStart ?: "时间未定"}开始")
                }
            }
            if (settled.isNotEmpty()) {
                appendLine("已参赛/放弃（${settled.size}）：")
                settled.take(5).forEach { appendLine("- 「${it.title}」${it.status.label}") }
            }
        }.trimEnd()
    }

    /** 内部知识检索：种子（目录+口径）+ 本机追踪 + 获奖履历，每段都标注来源与快照时间。 */
    private fun renderCompetitionKnowledge(args: JSONObject): String {
        val seed = competitionSeed
        if (seed == null && competitionTracking.isEmpty() && competitionAwards.isEmpty()) {
            return "内部竞赛知识库在本机不可用（种子未加载且没有本机记录）"
        }
        val query = args.optString("query").trim()
        val kind = args.optString("kind", "all")
        val sections = buildList {
            if ((kind == "all" || kind == "rule") && seed != null && seed.ruleVersions.isNotEmpty()) {
                add(renderRuleVersions(seed))
            }
            if ((kind == "all" || kind == "catalogue") && seed != null) {
                val entries = if (query.isEmpty()) seed.catalogue.take(10) else seed.searchCatalogue(query, limit = 10)
                if (entries.isNotEmpty()) {
                    add(
                        "【加分目录】（${entries.size} 条 · 来源：${seed.schoolName}种子快照 ${seed.fetchedAt}）\n" +
                            renderCatalogueRows(entries),
                    )
                }
            }
            if ((kind == "all" || kind == "track") && competitionTracking.isNotEmpty()) {
                add("【我的比赛追踪】（来源：本机追踪表）\n" + renderCompetitionDeadlines())
            }
            if ((kind == "all" || kind == "award") && competitionAwards.isNotEmpty()) {
                add(renderKnowledgeAwards())
            }
        }
        if (sections.isEmpty()) {
            return when {
                kind == "rule" -> "内置库里没有口径版本记录"
                kind == "catalogue" && query.isNotEmpty() -> "加分目录里没有名称含「$query」的赛事"
                kind == "track" -> "追踪表里还没有比赛；用 competition_search 查最近有哪些可以关注"
                kind == "award" -> "还没有登记过获奖"
                else -> "内部知识库里没有「$query」相关内容"
            }
        }
        return sections.joinToString("\n\n") +
            "\n\n（以上是内部知识快照，口径只作参考；要最新信息用 competition_web 抓官网核对）"
    }

    private fun renderRuleVersions(seed: SchoolCompetitionSeed): String {
        val body = seed.ruleVersions.sortedBy { it.effectiveFrom }.joinToString("\n\n") { r ->
            val t = r.tuimian
            buildString {
                appendLine("· ${r.effectiveFrom} 年起的口径（来源：${r.source} · ${r.sourceDate} · 口径范围：${r.scope}）")
                appendLine("  推免参考：国一 ${fmtPts(t.nationalFirst)} / 国二 ${fmtPts(t.nationalSecond)} / 国三 ${fmtPts(t.nationalThird)}；" +
                    "省一 ${fmtPts(t.provincialFirst)} / 省二 ${fmtPts(t.provincialSecond)}（分/项）")
                appendLine("  团队按名次比例计分：${t.teamRatios.joinToString("/") { fmtPts(it) }}；" +
                    (if (t.englishHalfWeight) "外语类竞赛按 50% 计分；" else "") +
                    (if (t.unofficialCatalogDropOneTier) "非官方目录降一档计；" else "") +
                    (if (t.specialEqualsFirst) "特殊奖按一等计" else ""))
                appendLine("  奖学金：A类国一 ${r.scholarship.aNationalFirstCny} 元 / 互联网+国一 ${r.scholarship.internetPlusFirstCny} 元；" +
                    "B类${if (r.scholarship.bClassWorkloadOnly) "不计奖学金，只计工作量" else "计奖学金"}")
                append("  第二课堂：每项 ${fmtPts(r.credit.minPerAward)}~${fmtPts(r.credit.maxPerAward)} 分，毕业需 ${fmtPts(r.credit.requiredTotal)} 分（${r.credit.deadlineNote}）")
            }.trimEnd()
        }
        return "【加分口径版本】（按生效年份版本化；学校政策调整会追加新版本，不改旧版）\n$body"
    }

    private fun renderKnowledgeAwards(): String =
        "【我的获奖履历】（${competitionAwards.size} 条 · 来源：本机获奖登记）\n" +
            competitionAwards.sortedByDescending { it.awardYear }.joinToString("\n") { award ->
                val teamPart = if (award.isTeam) "团队（第 ${award.teamRank} 队）" else "个人"
                val matePart = award.teammates.takeIf { it.isNotBlank() }?.let { " · 队友 $it" }.orEmpty()
                val notePart = award.note.takeIf { it.isNotBlank() }?.let { " · 备注：$it" }.orEmpty()
                "- ${award.awardYear} · ${award.level.label}${award.grade.label}「${award.title}」$teamPart$matePart$notePart"
            }

    /** 抓官网：优先内置库登记的官网；给了网址就抓网址（与登记官网不同时如实标注）。 */
    private suspend fun renderCompetitionWeb(args: JSONObject): String {
        val fetcher = competitionWebFetcher
            ?: return "本构建没有联网抓取能力（正式版不联网）；可以在比赛卡里直接点官网链接查看"
        val explicitUrl = args.optString("url").trim()
        val query = args.optString("query").trim()
        val entry = query.takeIf { it.isNotEmpty() }?.let { resolveCatalogueEntry(it) }
        val target = when {
            explicitUrl.isNotBlank() -> explicitUrl
            entry?.officialSite?.isNotBlank() == true -> entry.officialSite
            else -> ""
        }
        if (target.isEmpty()) {
            return if (explicitUrl.isBlank() && query.isBlank()) {
                "没有要抓的内容：请给比赛名（用内置库登记的官网）或直接给一个 http(s) 网址"
            } else {
                "内置库里没有「$query」的官网登记，也没给网址；可以给一个 http(s) 网址让我抓"
            }
        }
        val registered = entry?.officialSite.orEmpty()
        val origin = if (explicitUrl.isNotBlank()) {
            if (registered.isNotBlank() && explicitUrl != registered) "给定网址（内置库登记官网是 $registered）" else "给定网址"
        } else {
            "内置库登记官网"
        }
        val result = fetcher.fetch(target)
        if (!result.ok) return "抓取失败：${result.error}（来源：$target · $origin）"
        val source = result.finalUrl.ifBlank { target }
        if (result.text.isBlank()) {
            return "来源：$source（HTTP ${result.statusCode}，抓取于 ${result.fetchedAt} · $origin）\n" +
                "页面拿到了但没有可提取的正文（可能是 JS 渲染页）；建议手动打开链接核对，不要凭空转述。"
        }
        return buildString {
            appendLine("来源：$source（HTTP ${result.statusCode}，抓取于 ${result.fetchedAt} · $origin）")
            result.title.takeIf { it.isNotBlank() }?.let { appendLine("标题：$it") }
            appendLine("---")
            append(result.text)
        }
    }

    // ------------------------------------------------------------------ writes

    override suspend fun runWrite(action: AgentAction): AgentActionResult = try {
        perform(action)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AgentActionResult(false, failure.message ?: "执行失败")
    }

    private suspend fun perform(action: AgentAction): AgentActionResult {
        val args = runCatching { JSONObject(action.argumentsJson) }.getOrDefault(JSONObject())
        return when (action.toolName) {
            "create_team_task" -> {
                val title = args.optString("title").trim()
                val tier = tierOf(args.optString("tier")) ?: TaskTier.MID_TERM
                val rank = rankOf(args.optString("rank")) ?: TaskRank.SHOULD
                val phaseId = args.optString("phaseId").trim().takeIf { it.isNotEmpty() }
                val assignee = args.optString("assignee").trim().takeIf { it.isNotEmpty() }?.let { raw ->
                    resolveMember(raw) ?: return AgentActionResult(false, "队里没有这个人：$raw")
                }
                val task = TeamTask(
                    taskId = "task-" + UUID.randomUUID().toString().take(8),
                    title = title,
                    tier = tier,
                    phaseId = phaseId,
                    estimatedHours = args.optDouble("hours", 2.0).coerceIn(0.5, 200.0),
                    rank = rank,
                    assignedTo = assignee?.memberId,
                    isBaseline = tier != TaskTier.SHORT_TERM,
                    createdBy = actorId,
                )
                state.addTaskBy(actorId, task)
                AgentActionResult(
                    true,
                    "已建任务「${task.title}」（${task.taskId}，${fmtHours(task.estimatedHours)}）" +
                        if (assignee != null) "，负责人 ${assignee.name}" else "，待排期时按负载定人",
                )
            }

            "update_team_task" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                val updated = task.copy(
                    title = args.optString("title").trim().takeIf { it.isNotEmpty() } ?: task.title,
                    estimatedHours = if (args.has("hours")) args.optDouble("hours", task.estimatedHours) else task.estimatedHours,
                    rank = args.optString("rank").takeIf { it.isNotBlank() }?.let { rankOf(it) } ?: task.rank,
                    phaseId = if (args.has("phaseId")) args.optString("phaseId").ifBlank { null } else task.phaseId,
                )
                state.editTask(actorId, updated)
                AgentActionResult(true, "已更新「${updated.title}」")
            }

            "set_task_deadline" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                val date = dateOf(args.optString("date"))
                    ?: return AgentActionResult(false, "日期格式不对：${args.optString("date")}")
                state.setDeadlineBy(actorId, task.taskId, date, args.optInt("hour", -1).takeIf { it in 0..23 })
                AgentActionResult(true, "「${task.title}」截止改为 $date" + args.optInt("hour", -1).takeIf { it in 0..23 }?.let { " $it 点" }.orEmpty())
            }

            "cancel_team_task" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                state.cancelTask(actorId, task.taskId)
                AgentActionResult(true, "「${task.title}」已取消（划线保留，可撤销）")
            }

            "restore_team_task" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                state.restoreTaskBy(actorId, task.taskId)
                AgentActionResult(true, "「${task.title}」已恢复为待办")
            }

            "transfer_team_task" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                val target = resolveMember(args.optString("toMember"))
                    ?: return AgentActionResult(false, "队里没有这个人：${args.optString("toMember")}")
                state.transfer(actorId, task.taskId, target.memberId)
                AgentActionResult(true, "「${task.title}」已转派给 ${target.name}，本人可提异议")
            }

            "report_task_done" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                val note = args.optString("note").trim().takeIf { it.isNotEmpty() }
                val changed = state.reportDone(actorId, task.taskId, note)
                AgentActionResult(
                    changed,
                    if (changed) "「${task.title}」已报告完成，台账留痕" else "「${task.title}」本来就是完成状态",
                )
            }

            "raise_team_objection" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                state.raiseObjection(actorId, task.taskId, args.optString("reason").trim())
                AgentActionResult(true, "已对「${task.title}」提异议，等队长裁决")
            }

            "resolve_team_objection" -> {
                val objectionId = args.optString("objectionId")
                val keepTransfer = args.optBoolean("keepTransfer", true)
                state.resolveObjection(actorId, objectionId, keepTransfer)
                AgentActionResult(true, if (keepTransfer) "已维持转派" else "已收回转派")
            }

            "add_team_phase" -> {
                val phase = Phase(
                    phaseId = "phase-" + UUID.randomUUID().toString().take(8),
                    name = args.optString("name").trim(),
                    start = dateOf(args.optString("start")) ?: return AgentActionResult(false, "开始日要 yyyy-MM-dd"),
                    end = dateOf(args.optString("end")) ?: return AgentActionResult(false, "结束日要 yyyy-MM-dd"),
                )
                state.addPhaseBy(actorId, phase)
                AgentActionResult(true, "已新增阶段「${phase.name}」${phase.start}~${phase.end}")
            }

            "apply_team_template" -> {
                val template = CompetitionStages.byId(args.optString("template").trim())
                    ?: return AgentActionResult(false, "模板只有 math_modeling / innovation / generic")
                val rawStart = args.optString("start").trim()
                val start = if (rawStart.isBlank()) today
                else dateOf(rawStart) ?: return AgentActionResult(false, "开始日要 yyyy-MM-dd")
                val suffix = UUID.randomUUID().toString().take(6)
                val phases = CompetitionStages.instantiate(template, start, phaseIdPrefix = "phase-$suffix")
                val tasks = CompetitionStages.baselineTasks(template, phases, taskIdPrefix = "task-$suffix", createdBy = actorId)
                val applied = state.applyStagePlan(actorId, template.label, phases, tasks)
                val message = buildString {
                    append("已套用「${template.label}」：${applied.addedPhases.size} 个环节、${applied.addedTasks.size} 项基线任务")
                    if (applied.skipped.isNotEmpty()) {
                        append("；跳过 ${applied.skipped.size} 项（${applied.skipped.first()}）")
                    }
                }
                AgentActionResult(true, message)
            }

            "set_team_responsibility" -> {
                val target = args.optString("member").takeIf { it.isNotBlank() }?.let { resolveMember(it) } ?: actorCard()
                    ?: return AgentActionResult(false, "找不到成员")
                state.updateResponsibility(actorId, target.memberId, args.optString("text"))
                AgentActionResult(true, "${target.name} 的负责说明已更新")
            }

            "set_team_permission" -> {
                val target = resolveMember(args.optString("member"))
                    ?: return AgentActionResult(false, "队里没有这个人：${args.optString("member")}")
                val permission = permissionOf(args.optString("permission"))
                    ?: return AgentActionResult(false, "没有这个权限：${args.optString("permission")}")
                val enabled = args.optBoolean("enabled", true)
                state.setMemberPermission(actorId, target.memberId, permission, enabled)
                AgentActionResult(true, "${target.name} 的「${permission.label}」已${if (enabled) "授予" else "收回"}")
            }

            "add_team_member" -> {
                val member = MemberCard(
                    memberId = "m-" + UUID.randomUUID().toString().take(8),
                    name = args.optString("name").trim(),
                    roleTag = args.optString("roleTag").trim().takeIf { it.isNotEmpty() },
                    responsibility = args.optString("responsibility").trim(),
                )
                state.addMemberBy(actorId, member)
                AgentActionResult(true, "${member.name} 已加入队伍（默认无权限，队长可按需授予）")
            }

            "remove_team_member" -> {
                val target = resolveMember(args.optString("member"))
                    ?: return AgentActionResult(false, "队里没有这个人：${args.optString("member")}")
                state.removeMemberBy(actorId, target.memberId)
                AgentActionResult(true, "${target.name} 已离队；名下任务已转派给队长，台账保留")
            }

            "write_team_memory" -> {
                val kind = memoryKindOf(args.optString("kind"))
                    ?: return AgentActionResult(false, "种类要 PROFILE / RESPONSIBILITY / MEETING")
                state.addTeamMemory(actorId, kind, args.optString("content"))
                AgentActionResult(true, "已记下「${kind.label}」：${args.optString("content").take(20)}")
            }

            "record_team_finance" -> {
                val cents = (args.optDouble("amountYuan", 0.0) * 100).roundToLong()
                val reason = args.optString("reason").trim()
                state.recordFinance(actorId, cents, reason)
                AgentActionResult(true, "已记账：¥${"%.2f".format(cents / 100.0)} $reason")
            }

            "add_team_link" -> {
                val task = resolveTask(args.optString("taskId"))
                    ?: return AgentActionResult(false, "找不到任务：${args.optString("taskId")}")
                state.addExternalLink(actorId, task.taskId, args.optString("url").trim())
                AgentActionResult(true, "已给「${task.title}」登记外链")
            }

            "update_team_profile" -> {
                val group = groupOf(args.optString("group"))
                    ?: return AgentActionResult(false, "槽位组不认识：${args.optString("group")}")
                val base = state.profileCard ?: CompetitionProfileCard("未命名比赛", fields = emptyMap())
                val updated = base.withField(
                    group,
                    args.optString("key").trim(),
                    args.optString("label").trim(),
                    args.optString("value").trim(),
                )
                state.updateProfileCard(actorId, updated)
                AgentActionResult(true, "已补档案「${args.optString("label")}」")
            }

            "plan_team_week" -> {
                val output = SchedulerOps.scheduleOutput(
                    state = state,
                    today = today,
                    adjustment = SchedulerOps.SimulatedTaskAdjustment(),
                    personalTime = personalTime(),
                    actorId = actorId,
                )
                val draft = output.draft
                if (draft.placements.isEmpty()) {
                    AgentActionResult(
                        false,
                        "没排出任何安排：" + (draft.notes.firstOrNull() ?: "检查阶段、截止与成员空闲后重试"),
                    )
                } else {
                    val payload = JSONObject()
                    payload.put(
                        "placements",
                        JSONArray().also { array ->
                            draft.placements.forEach { placement ->
                                array.put(
                                    JSONObject()
                                        .put("taskId", placement.taskId)
                                        .put("memberId", placement.memberId)
                                        .put("date", placement.date.toString())
                                        .put("startMinute", placement.startMinute)
                                        .put("endMinute", placement.endMinute),
                                )
                            }
                        },
                    )
                    payload.put("unassigned", JSONArray(draft.unassigned))
                    payload.put("notes", JSONArray(draft.notes))
                    payload.put(
                        "diagnostics",
                        JSONArray().also { array ->
                            output.diagnostics.forEach { item ->
                                array.put(
                                    JSONObject()
                                        .put("code", item.code)
                                        .put("severity", item.severity.name)
                                        .put("target", item.target.name)
                                        .put("targetId", item.targetId)
                                        .put("message", item.message)
                                        .put("advice", item.advice),
                                )
                            }
                        },
                    )
                    val followUp = AgentAction(
                        id = UUID.randomUUID().toString(),
                        toolName = "apply_team_plan",
                        label = "确认整周排期",
                        summary = describeDraft(draft),
                        argumentsJson = payload.toString(),
                    )
                    AgentActionResult(
                        true,
                        "已算出 ${draft.placements.size} 段安排、${draft.unassigned.size} 项待安排；尚未生效，" +
                            "请在待确认动作里确认整周排期。",
                        listOf(followUp),
                    )
                }
            }

            "apply_team_plan" -> {
                val array = args.optJSONArray("placements")
                    ?: return AgentActionResult(false, "排期草案为空")
                val placements = mutableListOf<Placement>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: return AgentActionResult(false, "排期草案格式不对")
                    val task = resolveTask(item.optString("taskId"))
                        ?: return AgentActionResult(false, "草案里的任务已不存在：${item.optString("taskId")}")
                    val date = dateOf(item.optString("date"))
                        ?: return AgentActionResult(false, "草案里的日期不对：${item.optString("date")}")
                    placements += Placement(
                        taskId = task.taskId,
                        memberId = item.optString("memberId"),
                        date = date,
                        startMinute = item.optInt("startMinute"),
                        endMinute = item.optInt("endMinute"),
                    )
                }
                val unassigned = args.optJSONArray("unassigned").toStringList()
                val notes = args.optJSONArray("notes").toStringList()
                val draft = TeamPlanDraft(placements, unassigned, notes = notes)
                state.commitDraft(actorId, draft, replacesTaskIds = placements.map { it.taskId }.toSet())
                AgentActionResult(
                    true,
                    "排期已生效：${placements.size} 段写入个人日程" +
                        if (unassigned.isNotEmpty()) "；${unassigned.size} 项仍未排进" else "",
                )
            }

            "follow_competition" -> {
                val instance = resolveCompetitionInstance(args.optString("instanceKey"))
                    ?: return AgentActionResult(false, "竞赛库里没有这场比赛：${args.optString("instanceKey")}")
                followCompetition(instance)
                val deadline = instance.parsedEnd
                val deadlinePart = deadline?.let { end ->
                    val days = ChronoUnit.DAYS.between(today, end)
                    "，截止 $end" + if (days in 0..7) "（还剩 $days 天）" else ""
                }.orEmpty()
                AgentActionResult(
                    true,
                    "已关注「${instance.title}」$deadlinePart" +
                        if (deadline != null) "；截止前一天 20:00 和当天 09:00 会提醒你" else "；平台快照里没有截止日期，未设提醒",
                )
            }

            "register_award" -> {
                val title = args.optString("title").trim()
                val level = levelOf(args.optString("level"))
                    ?: return AgentActionResult(false, "级别要 国际/国家级/省级/市级/校级")
                val grade = gradeOf(args.optString("grade"))
                    ?: return AgentActionResult(false, "等次要 特等/一等/二等/三等/其他")
                val year = if (args.has("awardYear")) args.optInt("awardYear", today.year) else today.year
                val isTeam = args.optBoolean("isTeam", false)
                val teamRank = args.optInt("teamRank", 1)
                val instance = args.optString("instanceKey").trim().takeIf { it.isNotBlank() }
                    ?.let { resolveCompetitionInstance(it) }
                val award = CompetitionAward(
                    awardId = "aw-" + UUID.randomUUID().toString().take(8),
                    instanceKey = instance?.key.orEmpty(),
                    title = title,
                    catalogueKey = instance?.catalogueKey.orEmpty(),
                    level = level,
                    grade = grade,
                    awardYear = year,
                    isTeam = isTeam,
                    teamRank = if (isTeam) teamRank.coerceAtLeast(1) else 0,
                    teammates = "",
                    note = args.optString("note").trim(),
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                registerAward(award)
                val seed = competitionSeed
                val entry = instance?.let { seed?.entry(it.catalogueKey) }
                val preview = seed?.let { s ->
                    PointsCalculator.preview(
                        AwardInput(
                            awardYear = year,
                            level = level,
                            grade = grade,
                            isTeam = isTeam,
                            teamRank = teamRank.coerceAtLeast(1),
                            english = entry?.englishHalfWeight == true,
                            catalogueCategory = entry?.category.orEmpty(),
                            nationalInCatalog = entry?.nationalInCatalog ?: true,
                            provincialInCatalog = entry?.provincialInCatalog ?: true,
                        ),
                        s,
                    )
                }
                val pointsPart = preview?.let { p ->
                    val extra = if (entry == null) "（未关联目录赛事，按目录内口径估算）" else ""
                    "；推免参考 ${fmtPts(p.tuimianPoints)} 分$extra" +
                        p.notes.take(2).joinToString("", prefix = "；") { it }
                }.orEmpty()
                AgentActionResult(true, "已把「${award.title}」登记进个人履历$pointsPart")
            }

            else -> AgentActionResult(false, "没有这个写入工具：${action.toolName}")
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 草案的人话摘要：几段、谁多少、哪些没排进。 */
    private fun describeDraft(draft: TeamPlanDraft): String {
        val byMember = draft.placements.groupBy { it.memberId }.mapValues { (_, list) ->
            list.sumOf { it.minutes } / 60.0
        }
        val memberPart = byMember.entries.joinToString("、") { (id, hours) ->
            val name = state.members.firstOrNull { it.memberId == id }?.name ?: id
            "$name ${fmtHours(hours)}"
        }
        val unassignedPart = draft.unassigned.take(3).joinToString("、") { id ->
            state.tasks.firstOrNull { it.taskId == id }?.title ?: id
        }
        return "整周排期草案：${draft.placements.size} 段安排" +
            (memberPart.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: "") +
            (unassignedPart.takeIf { it.isNotEmpty() }?.let { "；未排进：$it" } ?: "") +
            "。确认后写入个人日程"
    }

    /** 写入门票：归档只读 / 身份不在队内 / 指定权限。 */
    private fun guard(permission: TeamPermission?): String? {
        requireWritable()?.let { return it }
        val actor = actorCard() ?: return "当前身份不在队内"
        if (permission != null && !actor.hasPermission(permission)) {
            return "「${actor.name}」没有「${permission.label}」权限，请队长或有权限成员操作"
        }
        return null
    }

    private fun requireWritable(): String? = when {
        state.archived -> "队伍已归档，已转只读"
        actorCard() == null -> "当前身份不在队内"
        else -> null
    }

    private fun resolveTask(raw: String): TeamTask? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        state.tasks.firstOrNull { it.taskId == text }?.let { return it }
        state.tasks.firstOrNull { it.title == text }?.let { return it }
        return state.tasks
            .filter { text.length >= 2 && it.title.contains(text) }
            .minByOrNull { it.title.length }
    }

    private fun resolveMember(raw: String): MemberCard? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        state.members.firstOrNull { it.memberId == text }?.let { return it }
        state.members.firstOrNull { it.name == text }?.let { return it }
        return state.members
            .filter { text.length >= 1 && it.name.contains(text) }
            .minByOrNull { it.name.length }
    }

    /** 竞赛届次解析：编号精确 → 名称精确 → 名称包含（取最短）。 */
    private fun resolveCompetitionInstance(raw: String): CompetitionInstance? {
        val seed = competitionSeed ?: return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        seed.instance(text)?.let { return it }
        seed.instances.firstOrNull { it.title == text }?.let { return it }
        val norm = SchoolCompetitionSeed.normalize(text)
        return seed.instances
            .filter { norm.length >= 2 && SchoolCompetitionSeed.normalize(it.title).contains(norm) }
            .minByOrNull { it.title.length }
    }

    /** 目录条目解析：届次→其目录赛事 → 编号精确 → 名称精确 → 名称包含（取最短）。 */
    private fun resolveCatalogueEntry(raw: String): CompetitionCatalogEntry? {
        val seed = competitionSeed ?: return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        resolveCompetitionInstance(text)?.let { inst -> seed.entry(inst.catalogueKey)?.let { return it } }
        seed.entry(text)?.let { return it }
        seed.catalogue.firstOrNull { it.name == text }?.let { return it }
        val norm = SchoolCompetitionSeed.normalize(text)
        return seed.catalogue
            .filter { norm.length >= 2 && SchoolCompetitionSeed.normalize(it.name).contains(norm) }
            .minByOrNull { it.name.length }
    }

    private fun levelOf(raw: String): AwardLevel? =
        AwardLevel.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun gradeOf(raw: String): AwardGrade? =
        AwardGrade.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun fmtPts(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun dateOf(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

    private fun rankOf(raw: String): TaskRank? =
        TaskRank.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun tierOf(raw: String): TaskTier? =
        TaskTier.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun memoryKindOf(raw: String): TeamMemoryKind? =
        TeamMemoryKind.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun permissionOf(raw: String): TeamPermission? =
        TeamPermission.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun groupOf(raw: String): ProfileSlotGroup? =
        ProfileSlotGroup.entries.firstOrNull { it.name.equals(raw.trim(), true) || it.label == raw.trim() }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun fmtHours(hours: Double): String =
        if (hours < 1) "${(hours * 60).toInt()}分钟" else "${"%.1f".format(hours)}小时"

    private fun fmtMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    companion object {
        /** 本周从周一开始；团队周报按周一起算。 */
        fun weekStartOf(today: LocalDate): LocalDate = today.minusDays((today.dayOfWeek.value - 1).toLong())
    }
}
