package com.swan1127.repland.domain.model

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The tool-calling conversation runtime.
 *
 * The group chat used to be five prose calls plus a cosmetic tool chip; nothing the agents
 * "did" touched the app. This runtime replaces that with a real loop: the planner answers in
 * a strict JSON envelope, each `tool` entry is executed against [AgentToolbox] (reads run
 * inline, writes become [AgentAction]s that only the user's confirmation can execute), and
 * the tool result is fed back so the next round can react to what actually happened.
 *
 * The envelope is parsed from ordinary text on purpose: the BYOK endpoint is whatever the
 * user configured, and there is no guarantee it supports native function calling.
 */
enum class AgentToolKind { READ, WRITE, CONTROL }

/**
 * 对话式控制：模型读懂用户这句话是在授权执行、取消，还是要撤销刚做完的一批写入。
 * 控制工具本身不改数据——确认仍走同一条执行路径，由界面按最新状态重新准入。
 */
enum class AgentControl { CONFIRM_DRAFT, CANCEL_DRAFT, UNDO_LAST }

data class AgentToolParam(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

data class AgentToolSpec(
    val name: String,
    val label: String,
    val description: String,
    val params: List<AgentToolParam> = emptyList(),
    val kind: AgentToolKind,
    /**
     * 派生动作专用：由别的工具在执行后生成，模型不该也不能直接调用，
     * 因此不进提示词目录（仍然能排队、能确认执行）。
     */
    val hidden: Boolean = false,
    /** 人话摘要，用于确认卡片；不提供就回退到 name=value 列表。 */
    val summarize: ((JSONObject) -> String)? = null,
    /**
     * 准入校验：排队给用户确认之前跑一遍确定性检查（参数可解析、目标此刻真实存在、
     * 时段此刻不冲突）。返回 null 放行；返回文本则拒收，文本会原样反馈给模型修正。
     */
    val validate: ((JSONObject) -> String?)? = null,
    /**
     * 草案槽：同一槽的写入互相取代，而不是叠成两张卡。用户在对话里一步步改排期时，
     * 新方案落地就替换旧草案，最终只有一张卡等待确认。
     */
    val draftSlot: String? = null,
)

data class AgentToolCall(val name: String, val argumentsJson: String) {
    fun arguments(): JSONObject = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
}

sealed interface AgentTurn {
    data class Reply(val text: String?, val toolCall: AgentToolCall? = null) : AgentTurn
    data class Failed(val reason: AiAdvisorFailureReason) : AgentTurn
}

data class AgentChatMessage(val role: String, val content: String)

/** 任务在当前计划里的那一段；规则层改期时按它移动原安排。 */
data class AgentRulePlacement(
    val segmentId: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
)

fun interface AgentTurnModel {
    suspend fun complete(messages: List<AgentChatMessage>): AgentTurn

    /** 是否真的能接单；只装配了对象但没有密钥时必须如实返回 false，界面才不冒充 AI。 */
    suspend fun ready(): Boolean = true
}

object NoOpAgentTurnModel : AgentTurnModel {
    override suspend fun complete(messages: List<AgentChatMessage>): AgentTurn =
        AgentTurn.Failed(AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED)

    override suspend fun ready(): Boolean = false
}

data class AgentToolResult(val ok: Boolean, val summary: String)

/** A write the model asked for. Nothing is persisted until the user confirms it. */
data class AgentAction(
    val id: String,
    val toolName: String,
    val label: String,
    val summary: String,
    val argumentsJson: String,
)

data class AgentActionResult(
    val ok: Boolean,
    val summary: String,
    /** Actions produced by executing this one (auto-schedule emits the placements it picked). */
    val followUpActions: List<AgentAction> = emptyList(),
)

sealed interface AgentConversationEvent {
    /** 文字输出：成员对用户说的话。 */
    data class TextOutput(val text: String) : AgentConversationEvent

    /** 文字输出：被咨询的专家成员的回答，作为群聊气泡出现。 */
    data class SpecialistSaid(val agentId: String, val name: String, val text: String) : AgentConversationEvent

    /** 工具即将执行：界面头部据此亮出「正在处理：…」，慢模型下用户看得见进度。 */
    data class ToolStarted(val label: String) : AgentConversationEvent

    /** 工具调用：一次真实的工具执行记录（参数与结果都展示）。 */
    data class ToolInvoked(
        val name: String,
        val label: String,
        val argsSummary: String,
        val outcome: ToolOutcome,
        val resultSummary: String,
    ) : AgentConversationEvent

    /** 逻辑调整：一个等待用户确认的写入操作。 */
    data class ActionQueued(val action: AgentAction) : AgentConversationEvent

    /** 同槽新方案取代了旧的待确认草案；旧卡应转为只读记录。 */
    data class DraftReplaced(val previous: List<AgentAction>) : AgentConversationEvent

    /** 模型读懂了用户的控制意图；执行由界面按最新状态完成。 */
    data class ControlRequested(val control: AgentControl) : AgentConversationEvent

    /** 云端没接住，但这句由本地确定性规则接住了；界面据此如实标注「本地规则」。 */
    data class LocalFallback(val reason: AiAdvisorFailureReason? = null) : AgentConversationEvent

    data class Failed(val reason: AiAdvisorFailureReason, val agentName: String? = null) : AgentConversationEvent
}

/** What survives between turns: the raw protocol transcript plus still-unconfirmed writes. */
data class AgentSessionState(
    val transcript: List<AgentChatMessage> = emptyList(),
    val pendingActions: List<AgentAction> = emptyList(),
)

/** The app's operation surface behind the tools. Reads answer; writes only queue. */
interface AgentToolbox {
    /**
     * 用户这一句原话。钟点、时长、星期这些约束常常只在句子里，模型漏填时工具得能回读；
     * 每轮开始时由引擎交进来，工具本身不猜。
     */
    fun noteUtterance(utterance: String) {}

    /** 本地规则层的参照物：当前活动任务。空列表表示宿主没接上任务视图。 */
    fun ruleTasks(): List<RuleTask> = emptyList()

    /** 任务此刻已排进哪一段；改期要挪这一段，而不是再排一段。 */
    fun rulePlacementOf(taskId: String): AgentRulePlacement? = null

    fun specs(): List<AgentToolSpec>

    suspend fun runRead(name: String, arguments: JSONObject): AgentToolResult

    suspend fun runWrite(action: AgentAction): AgentActionResult

    /** 批量写入给确认卡用的逐件预览：一件一行，用户逐件挑时间。非批量工具返回空列表。 */
    fun batchPreview(action: AgentAction): List<AgentBatchPreviewRow> = emptyList()

    /** 这一批当前用的行事风格；卡上的方案切换器按它标出选中项。 */
    fun batchStyle(action: AgentAction): SchedulingStyle = SchedulingStyle.PACKED

    /** 这一批要落在哪一天的实况。空列表表示"就按界面那天"，跨天批量时由 toolbox 给出另一天的安排。 */
    fun batchOccupied(action: AgentAction): List<TimelineEntry> = emptyList()

    /** 跨天批次的卡上要写明落在哪天；就是界面那天时返回 null，不额外占一行。 */
    fun batchDayLabel(action: AgentAction): String? = null
}

/**
 * 批量方案里的一件。落点由 toolbox 算，卡片只负责显示和把用户挑的时间写回参数——
 * 两处各算一遍的话，卡上说的时间和真正写入的时间迟早会分家。
 */
data class AgentBatchPreviewRow(
    /** 对应 items 数组里的位置；用户改这一件的时间时按它写回参数。 */
    val index: Int,
    val name: String,
    val durationMinutes: Int,
    val startMinute: Int,
    val endMinute: Int,
    val trackId: String,
    /** 这一件对应的任务编号；已有任务改时间时，它自己当前那段不算冲突。 */
    val taskId: String? = null,
    /** 用户那句话里点名的时间；null 表示这个落点是助手挑的。 */
    val userPickedStartMinute: Int? = null,
    /** 非空表示这一件被排成了某个已有安排的下属（同一时间做事）。 */
    val nestedUnderTitle: String? = null,
)

/**
 * Whole-batch undo for confirmed writes: one capture before a batch runs, one restore when
 * the user regrets it. The token is opaque so the host can snapshot raw storage; the domain
 * only needs "put it back exactly as it was".
 */
interface AgentWriteUndo {
    /** 批量执行前抓一份快照；返回 null 表示宿主此刻不支持回退。 */
    suspend fun capture(): Any?

    /** 把快照原样放回；返回是否成功。 */
    suspend fun restore(token: Any): Boolean
}

/** What the engine knows about the day, computed once by the caller and reused across rounds. */
data class AgentTurnContext(
    val date: LocalDate,
    val occupiedEntries: List<TimelineEntry>,
    /** 来自过往已确认草案的用户事实；只进提示词，不落库。 */
    val memoryNotes: List<Pair<String, String>> = emptyList(),
    /** 记忆画像（本地 FSRS 派生的可提取率信号）；null 表示还没有可信的记录。 */
    val memoryProfile: MemoryReference? = null,
    /** 最近小组对话沉淀下来的结论片段。 */
    val memoryEpisodes: List<String> = emptyList(),
    /** 场景专属的确定性实况段（团队域用：队伍/阶段/任务/排期/异常）；空串表示不加。 */
    val domainBriefing: String = "",
    /** 用户亲口写的「关于我」自述（第一人称、用户资产）；空串表示还没写，提示词里不出现。 */
    val personaDoc: String = "",
    /** 内容画像：模型读真实材料后写下的理解（含用户更正），已由用户授权；空串表示没有。 */
    val portraitNote: String = "",
) {
    /**
     * 记忆的唯一取用口径：规划师、被 @ 的专家、私下咨询都读这一段，避免同一个用户习惯
     * 在三个地方各拿一份、还各排各的序。习惯取最近的，记忆取最容易被忘的。
     * 用户自述排在最前并标明出处：它是用户亲口写的，优先于下面这些推测信号。
     */
    fun memoryBriefing(factLimit: Int = 10, riskLimit: Int = 6): String {
        val facts = memoryNotes.takeLast(factLimit).joinToString("\n") { (key, value) -> "- $key：$value" }
        val profile = memoryProfile?.takeUnless { it.isEmpty }
        val risks = profile?.riskLines(riskLimit)?.joinToString("\n").orEmpty()
        val episodes = memoryEpisodes.takeLast(3).joinToString("；")
        return buildString {
            personaDoc.trim().takeIf(String::isNotEmpty)?.let {
                appendLine("用户自己写的自述（用户亲口写的，优先于下面的推测信号；只用来理解用户，不要据此编造进度）：")
                appendLine(it)
            }
            portraitNote.trim().takeIf(String::isNotEmpty)?.let {
                appendLine(it)
            }
            if (facts.isNotEmpty()) {
                appendLine("已沉淀的用户习惯（来自过去确认过的草案，不是猜测）：")
                appendLine(facts)
            }
            if (profile != null) {
                appendLine(profile.logNote() + "。其中最容易忘掉、安排时该优先往前放的：")
                appendLine(risks)
            }
            if (episodes.isNotEmpty()) appendLine("最近小组达成的结论：$episodes")
        }.trimEnd()
    }
}

/** 待确认卡片的写入描述；参数被「改」过之后卡片要能按新参数重算。 */
fun describeAgentWrite(spec: AgentToolSpec, arguments: JSONObject): String =
    spec.summarize?.invoke(arguments) ?: run {
        val parts = spec.params.mapNotNull { param ->
            val raw = arguments.opt(param.name) ?: return@mapNotNull null
            val text = raw.toString()
            if (text.isBlank()) null else "${param.name}=$text"
        }
        if (parts.isEmpty()) spec.label else "${spec.label}：${parts.joinToString("，")}"
    }

/**
 * 对话里的确定性防线。模型很会顺着句子编，但有两件事不能交给它猜：
 * 只说了时间的一句话要先问清排哪个任务；完成/取消/顺延/进度是用户亲口说了才算的事实。
 * 词表、问法与网页版一致，同一句话在两个平台上得到同一个回答。
 */
internal object AgentConversationGuards {
    private val STATUS_WORDS = mapOf(
        "complete" to Regex(
            "(完成|做完|搞定|弄完了?|弄好了?|写完了?|写好了?|学完了?|看完了?|吃完(?:饭)?了?|吃过了?|读完了?|" +
                "交了|提交了|结束了|已经做了|已做|干完|处理完|改好了?|画完了?|背完了?|练完了?|上完课)",
        ),
        "cancel" to Regex("(取消|不做了|删掉|别做了|不用做了|去掉)"),
        "postpone" to Regex("(推迟|延后|往后挪|顺延|挪后)"),
        "progress" to Regex("(进度|做到|完成了一半|一半了|\\d+\\s*%)"),
    )

    /** 将来的说法不能当成已经做完；句子里另有「已经/刚/昨天」时才算真的做完了。 */
    private val FUTURE_CUE = Regex(
        "(明天|后天|大后天|下周|下星期|下礼拜|周[一二三四五六日天]|星期[一二三四五六日天]|" +
            "礼拜[一二三四五六日天]|要|打算|准备|帮我排|安排|排进|排到)",
    )
    private val PAST_CUE = Regex("(已经|刚刚|刚|昨天|前天)")
    private val CLARIFY_QUESTION = Regex("哪个任务|哪件事|排哪|安排哪|哪一项|做什么")
    private val QUOTED = Regex("「(.+?)」")

    /** 状态类工具对应的词性；record_feedback 只有带进度时才算状态写入。 */
    private fun statusActionOf(toolName: String, arguments: JSONObject): String? = when (toolName) {
        "complete_task" -> "complete"
        "cancel_task" -> "cancel"
        "postpone_task" -> "postpone"
        "record_feedback" -> if (!arguments.isNull("progressPercent")) "progress" else null
        else -> null
    }

    /**
     * 返回拒收理由；null 表示用户这句话确实授权了这个状态写入。
     * 模型可以把「帮我排下午一点」理解成排期，但不能把一句听不懂的话理解成完成。
     */
    fun statusWordingRejection(toolName: String, arguments: JSONObject, utterance: String): String? {
        val action = statusActionOf(toolName, arguments) ?: return null
        if (!STATUS_WORDS.getValue(action).containsMatchIn(utterance)) {
            return "用户这句话里没说要${statusName(action)}，不能替用户做这个决定"
        }
        if ((action == "complete" || action == "progress") &&
            FUTURE_CUE.containsMatchIn(utterance) && !PAST_CUE.containsMatchIn(utterance)
        ) {
            return "用户说的是将来的安排，不是已经做完的事"
        }
        return null
    }

    /** 被拒收的状态写入换成一句人话反问，替换掉模型那半句「已完成」。 */
    fun statusClarify(spec: AgentToolSpec, arguments: JSONObject): String {
        val action = statusActionOf(spec.name, arguments) ?: return "没听准，能再说一句吗？"
        val summary = spec.summarize?.invoke(arguments) ?: spec.label
        val name = QUOTED.find(summary)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: return "没听准：$summary，是这个意思吗？"
        return when (action) {
            "complete" -> "没听准：你是想把「$name」记为完成吗？"
            "cancel" -> "没听准：你是想取消「$name」吗？"
            "postpone" -> "没听准：你是想把「$name」往后挪吗？"
            else -> "没听准：你是想给「$name」记这条进度吗？"
        }
    }

    private fun statusName(action: String): String = when (action) {
        "complete" -> "完成"
        "cancel" -> "取消"
        "postpone" -> "往后挪"
        else -> "记进度"
    }

    /** 只给了时间、没给任务时的反问；带上时刻，下一轮的短回答才能接住。 */
    fun timeOnlyAsk(utterance: String): String? {
        val minute = AgentLocalPlanner.timeOnlyClock(utterance) ?: return null
        return "好的，${clockLabel(minute)} 开始。要排哪个任务？"
    }

    /**
     * 上一轮问「13:00 排哪个任务」时，这一句只回答任务名，时间要从问题里接过来。
     * 光靠模型记不住那一轮的时间，用户就会得到一张没说要的时段卡。
     */
    fun carriedClock(transcript: List<AgentChatMessage>, utterance: String): Int? {
        if (AgentLocalPlanner.clockMinuteOf(utterance) != null) return null
        val lastAssistant = transcript.lastOrNull { it.role == AgentConversationEngine.ROLE_ASSISTANT }
            ?: return null
        val say = runCatching { JSONObject(lastAssistant.content).optString("say") }
            .getOrElse { lastAssistant.content }
        if (!CLARIFY_QUESTION.containsMatchIn(say)) return null
        return AgentLocalPlanner.clockMinuteOf(say)
    }

    fun clockLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}

class AgentConversationEngine(
    private val chat: AgentTurnModel,
    private val toolbox: AgentToolbox,
    private val personas: List<AgentProfile> = DefaultAgentTeam.agents,
    /** A stalled provider is a hang, not an answer; the budget covers the whole turn. */
    private val turnTimeoutMillis: Long = 120_000L,
    private val maxToolRounds: Int = 8,
    /**
     * 场景自带的人设段；非空时替换个人版规划师提示词（工具目录、JSON 信封、日期、
     * domainBriefing、记忆仍由引擎统一追加）。团队域用它注入队长/队员视角与机制纪律。
     */
    private val systemPromptOverride: ((AgentTurnContext) -> String)? = null,
) {
    suspend fun runTurn(
        session: AgentSessionState,
        utterance: String,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState {
        // 只说了时间：先问清排哪个任务，不让模型替用户从清单里挑。
        // 团队场景的「10:00」常是在答会议时间，不能套这个问法。
        val personalChat = systemPromptOverride == null
        if (personalChat) {
            AgentConversationGuards.timeOnlyAsk(utterance)?.let { ask ->
                return answerWithClarification(session, utterance, ask, onEvent)
            }
        }
        // 一句短回答补全上一轮的问题时，把问题里的时间接过来；光靠模型记不住。
        val carried = if (personalChat) {
            AgentConversationGuards.carriedClock(session.transcript, utterance)
        } else {
            null
        }
        val effectiveUtterance = if (carried != null) {
            "$utterance ${AgentConversationGuards.clockLabel(carried)}"
        } else {
            utterance
        }
        toolbox.noteUtterance(effectiveUtterance)
        val transcript = session.transcript + AgentChatMessage(ROLE_USER, effectiveUtterance)
        return withTimeoutOrNull(turnTimeoutMillis) {
            drive(transcript, session.pendingActions, effectiveUtterance, context, onEvent)
        } ?: run {
            val rescued = admitLocal(transcript, session.pendingActions, effectiveUtterance, context, onEvent)
            if (rescued != null) {
                onEvent(AgentConversationEvent.LocalFallback(AiAdvisorFailureReason.TIMEOUT))
                rescued
            } else {
                onEvent(AgentConversationEvent.Failed(AiAdvisorFailureReason.TIMEOUT))
                session
            }
        }
    }

    /**
     * 确定性反问：不调模型，直接把问题和用户这句话记进协议记录。
     * 下一轮的短回答因此有一个明确的问题可以承接，模型恢复后也知道自己问过什么。
     */
    private suspend fun answerWithClarification(
        session: AgentSessionState,
        utterance: String,
        ask: String,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState {
        onEvent(AgentConversationEvent.TextOutput(ask))
        return AgentSessionState(
            transcript = session.transcript +
                AgentChatMessage(ROLE_USER, utterance) +
                AgentChatMessage(ROLE_ASSISTANT, JSONObject().put("say", ask).toString()),
            pendingActions = session.pendingActions,
        )
    }

    /**
     * 云端整体不可用（没密钥、没连上）时的入口：一次模型都不调，只跑本地规则。
     * 接不住返回 null，由调用方照常说明云端不可用——离线兜底不冒充智能。
     */
    suspend fun runOfflineTurn(
        session: AgentSessionState,
        utterance: String,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState? {
        // 云端的失败不该把一句能听懂的话变成报错：只给时间时照样反问。
        toolbox.noteUtterance(utterance)
        if (systemPromptOverride == null) {
            AgentConversationGuards.timeOnlyAsk(utterance)?.let { ask ->
                return answerWithClarification(session, utterance, ask, onEvent)
            }
        }
        return admitLocal(
            (session.transcript + AgentChatMessage(ROLE_USER, utterance)).toMutableList(),
            session.pendingActions,
            utterance,
            context,
            onEvent,
        )
    }

    /**
     * 云端没配、密钥被拒、模型超时时的本地兜底：只接形状确定的那句话，接住了照样走准入
     * 校验和确认卡。返回 null 表示本地接不住，调用方要把云端失败的原因如实说给用户。
     */
    private suspend fun admitLocal(
        transcript: List<AgentChatMessage>,
        pending: List<AgentAction>,
        utterance: String,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState? {
        // 窄而确定的可用档句式先走老解析器：它只认「哪天 + 哪段 + 有空/没空」，
        // 不会误抢别的句子，用户已经习惯的那几句行为保持不变。
        AgentLocalPlanner.plan(utterance, context.date)?.let { call ->
            return queueLocalCalls(transcript, pending, listOf(call), onEvent)
        }
        // 规则层是地板：模型不可用时，带日期、钟点、重复、截止的普通句子照样能出确认卡。
        // 它产出的仍是工具调用，准入校验和确认卡一道不少。
        return when (val outcome = AgentRulePlanner.plan(
            utterance, LocalDate.now(), context.date, toolbox.ruleTasks(), toolbox::rulePlacementOf,
        )) {
            is AgentRuleOutcome.Ask -> {
                onEvent(AgentConversationEvent.TextOutput(outcome.text))
                AgentSessionState(
                    transcript + AgentChatMessage(ROLE_ASSISTANT, JSONObject().put("say", outcome.text).toString()),
                    pending,
                )
            }
            is AgentRuleOutcome.Queue -> queueLocalCalls(transcript, pending, outcome.calls, onEvent)
            null -> null
        }
    }

    /** 本地规则产出的调用同样过准入：任何一条被拒，这一句就整句退回，不排半张卡。 */
    private suspend fun queueLocalCalls(
        transcript: List<AgentChatMessage>,
        pending: List<AgentAction>,
        calls: List<AgentToolCall>,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState? {
        if (calls.isEmpty()) return null
        val queued = mutableListOf<AgentAction>()
        val summaries = mutableListOf<String>()
        for (call in calls) {
            val spec = toolbox.specs().firstOrNull { it.name == call.name } ?: return null
            if (spec.kind != AgentToolKind.WRITE) return null
            val arguments = call.arguments()
            val rejection = spec.validate?.invoke(arguments)
            if (rejection != null) {
                onEvent(
                    AgentConversationEvent.ToolInvoked(
                        name = call.name, label = spec.label, argsSummary = describeArguments(arguments),
                        outcome = ToolOutcome.FAILED, resultSummary = "未进入待确认：$rejection",
                    ),
                )
                return null
            }
            val action = AgentAction(
                id = UUID.randomUUID().toString(),
                toolName = spec.name,
                label = spec.label,
                summary = describeWrite(spec, arguments),
                argumentsJson = call.argumentsJson,
            )
            queued += action
            summaries += "${spec.name}（${action.summary}）"
        }
        queued.forEach { onEvent(AgentConversationEvent.ActionQueued(it)) }
        return AgentSessionState(
            transcript + AgentChatMessage(
                ROLE_USER,
                "[本地规则已登记，未经模型] ${summaries.joinToString("；")}。" +
                    "这句话是离线解析出来的；等模型恢复后不要重复登记同一段时间。",
            ),
            pending + queued,
        )
    }

    /** Called after the user confirmed (or executed) one queued write; the loop resumes. */
    suspend fun resumeAfterAction(
        session: AgentSessionState,
        action: AgentAction,
        result: AgentActionResult,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState = resumeAfterActions(session, listOf(action to result), context, onEvent)

    /**
     * Batch settlement after the user confirmed writes. Deliberately silent: the executed
     * rows in the UI are the user-facing record, so settling never burns a model call. The
     * results still land in the transcript, so the planner speaks from accurate state on the
     * next user turn; auto-schedule follow-ups are re-admitted here — the deterministic
     * planner computed them minutes ago, and the user may have changed the day since.
     */
    suspend fun resumeAfterActions(
        session: AgentSessionState,
        confirmed: List<Pair<AgentAction, AgentActionResult>>,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState {
        if (confirmed.isEmpty()) return session
        val transcript = session.transcript.toMutableList()
        var pending = session.pendingActions
        confirmed.forEach { (action, result) ->
            pending = pending - action
            transcript += AgentChatMessage(
                ROLE_USER,
                "${if (result.ok) "[用户已确认执行]" else "[执行失败]"} ${action.toolName}（${action.summary}）" +
                    "结果：${result.summary}",
            )
            result.followUpActions.forEach { followUp ->
                val spec = toolbox.specs().firstOrNull { it.name == followUp.toolName }
                val rejection = spec?.validate?.invoke(
                    runCatching { JSONObject(followUp.argumentsJson) }.getOrDefault(JSONObject()),
                )
                if (rejection != null) {
                    transcript += AgentChatMessage(ROLE_USER, "[未加入待确认队列] ${followUp.toolName}：$rejection")
                    onEvent(
                        AgentConversationEvent.ToolInvoked(
                            name = followUp.toolName, label = spec.label, argsSummary = followUp.summary,
                            outcome = ToolOutcome.FAILED, resultSummary = "未进入待确认：$rejection",
                        ),
                    )
                } else {
                    pending = pending + followUp
                    // 派生动作同样是待确认，不给这一行，模型只看到上游「已排出 N 项」，
                    // 就会对用户宣布排好了，而时间轴上什么都没有。
                    transcript += AgentChatMessage(
                        ROLE_USER,
                        "[已加入待确认队列] ${followUp.toolName}（${followUp.summary}）。" +
                            "它还没有执行；等用户确认后你才会收到结果，不要当作已经完成。",
                    )
                    onEvent(AgentConversationEvent.ActionQueued(followUp))
                }
            }
        }
        return AgentSessionState(transcript.toList(), pending)
    }

    private suspend fun drive(
        seedTranscript: List<AgentChatMessage>,
        seedPending: List<AgentAction>,
        utterance: String,
        context: AgentTurnContext,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentSessionState {
        val system = systemPrompt(context)
        val transcript = seedTranscript.toMutableList()
        val pending = seedPending.toMutableList()
        repeat(maxToolRounds) {
            val turn = chat.complete(listOf(system) + transcript)
            when (turn) {
                is AgentTurn.Failed -> {
                    // 模型答不上来不等于这句话没法办：先让本地规则接一次，接住了就不报错。
                    val rescued = admitLocal(transcript, pending, utterance, context, onEvent)
                    if (rescued != null) {
                        onEvent(AgentConversationEvent.LocalFallback(turn.reason))
                        return rescued
                    }
                    onEvent(AgentConversationEvent.Failed(turn.reason))
                    return AgentSessionState(transcript.toList(), pending.toList())
                }
                is AgentTurn.Reply -> {
                    transcript += AgentChatMessage(ROLE_ASSISTANT, envelopeFor(turn))
                    val call = turn.toolCall
                    val spec = call?.let { candidate -> toolbox.specs().firstOrNull { it.name == candidate.name } }
                    // 状态写入要过用户措辞这一关：一句听不清的话不能被模型翻译成"已完成"。
                    // 在模型那半句话显示出来之前就换成反问，屏幕上不留一句没发生的结论。
                    val wordingRejection = if (call != null && spec != null) {
                        AgentConversationGuards.statusWordingRejection(call.name, call.arguments(), utterance)
                    } else {
                        null
                    }
                    if (call != null && spec != null && wordingRejection != null) {
                        val ask = AgentConversationGuards.statusClarify(spec, call.arguments())
                        onEvent(AgentConversationEvent.TextOutput(ask))
                        transcript += AgentChatMessage(
                            ROLE_USER,
                            "[未加入待确认队列] ${spec.name}：$wordingRejection；已改为向用户确认原意，等用户回答后再决定。",
                        )
                        transcript += AgentChatMessage(ROLE_ASSISTANT, JSONObject().put("say", ask).toString())
                        onEvent(
                            AgentConversationEvent.ToolInvoked(
                                name = call.name, label = spec.label, argsSummary = describeArguments(call.arguments()),
                                outcome = ToolOutcome.FAILED, resultSummary = "未进入待确认：$wordingRejection",
                            ),
                        )
                        return AgentSessionState(transcript.toList(), pending.toList())
                    }
                    turn.text?.trim()?.takeIf(String::isNotBlank)?.let { text ->
                        onEvent(AgentConversationEvent.TextOutput(text))
                    }
                    if (call == null) return AgentSessionState(transcript.toList(), pending.toList())
                    if (spec == null) {
                        transcript += AgentChatMessage(ROLE_USER, "[工具结果] ${call.name}：没有这个工具，可用工具见列表")
                        onEvent(
                            AgentConversationEvent.ToolInvoked(
                                name = call.name, label = call.name, argsSummary = describeArguments(call.arguments()),
                                outcome = ToolOutcome.FAILED, resultSummary = "没有这个工具",
                            ),
                        )
                        return@repeat
                    }
                    when (spec.kind) {
                        AgentToolKind.READ -> {
                            onEvent(AgentConversationEvent.ToolStarted(spec.label))
                            val outcome: AgentToolResult = if (call.name == SPECIALIST_TOOL) {
                                consultSpecialist(context, call.arguments(), onEvent)
                            } else {
                                runCatching { toolbox.runRead(call.name, call.arguments()) }
                                    .getOrElse { AgentToolResult(false, "执行失败：${it.message ?: "未知错误"}") }
                            }
                            transcript += AgentChatMessage(ROLE_USER, "[工具结果] ${call.name}：${outcome.summary}")
                            onEvent(
                                AgentConversationEvent.ToolInvoked(
                                    name = call.name, label = spec.label, argsSummary = describeArguments(call.arguments()),
                                    outcome = if (outcome.ok) ToolOutcome.DONE else ToolOutcome.FAILED,
                                    resultSummary = outcome.summary,
                                ),
                            )
                        }
                        AgentToolKind.WRITE -> {
                            onEvent(AgentConversationEvent.ToolStarted(spec.label))
                            val rejection = spec.validate?.invoke(call.arguments())
                            if (rejection != null) {
                                // 准入不过：不入队、不打扰用户，把人话理由喂回循环让模型自行修正。
                                transcript += AgentChatMessage(
                                    ROLE_USER,
                                    "[未加入待确认队列] ${spec.name}：$rejection 请修正参数，或先读取现状/询问用户后重试。",
                                )
                                onEvent(
                                    AgentConversationEvent.ToolInvoked(
                                        name = call.name, label = spec.label, argsSummary = describeArguments(call.arguments()),
                                        outcome = ToolOutcome.FAILED, resultSummary = "未进入待确认：$rejection",
                                    ),
                                )
                                return@repeat
                            }
                            // 模型自选的时段在入队前还要过小盾这一关：几何冲突引擎已拦，
                            // 剩下"这样排合不合理"是判断题，归评审员。自动排期的落点由
                            // 确定性规划器算出、排队时重新准入，不走这道判断。
                            if (spec.name in CONFLICT_REVIEWED) {
                                val verdict = conflictReview(call, spec, context)
                                transcript += AgentChatMessage(ROLE_USER, verdict.transcriptLine)
                                onEvent(
                                    AgentConversationEvent.ToolInvoked(
                                        name = "conflict_review", label = "小盾评审",
                                        argsSummary = describeWrite(spec, call.arguments()),
                                        outcome = if (verdict.passed) ToolOutcome.DONE else ToolOutcome.FAILED,
                                        resultSummary = verdict.display,
                                    ),
                                )
                                if (!verdict.passed) return@repeat
                            }
                            val action = AgentAction(
                                id = UUID.randomUUID().toString(),
                                toolName = spec.name,
                                label = spec.label,
                                summary = describeWrite(spec, call.arguments()),
                                argumentsJson = call.argumentsJson,
                            )
                            val slot = spec.draftSlot
                            if (slot != null) {
                                val specsByName = toolbox.specs().associateBy { it.name }
                                val replaced = pending.filter { queued ->
                                    specsByName[queued.toolName]?.draftSlot == slot
                                }
                                if (replaced.isNotEmpty()) {
                                    pending.removeAll(replaced)
                                    transcript += AgentChatMessage(
                                        ROLE_USER,
                                        "[草案已更新] 新一轮方案取代了之前尚未确认的 ${replaced.size} 项" +
                                            "（${replaced.joinToString("；") { it.summary }}）；旧草案作废，" +
                                            "现在只有新草案可确认。",
                                    )
                                    onEvent(AgentConversationEvent.DraftReplaced(replaced))
                                }
                            }
                            pending += action
                            transcript += AgentChatMessage(
                                ROLE_USER,
                                "[已加入待确认队列] ${spec.name}（${action.summary}）。" +
                                    "它还没有执行；等用户确认后你才会收到结果，不要当作已经完成。",
                            )
                            onEvent(AgentConversationEvent.ActionQueued(action))
                        }
                        AgentToolKind.CONTROL -> {
                            onEvent(AgentConversationEvent.ToolStarted(spec.label))
                            val control = controlOf(spec.name)
                            val allowed = when (control) {
                                AgentControl.CONFIRM_DRAFT, AgentControl.CANCEL_DRAFT -> pending.isNotEmpty()
                                AgentControl.UNDO_LAST -> true
                                null -> false
                            }
                            if (control == null || !allowed) {
                                val reason = if (control == null) "没有这个控制工具" else "当前没有待确认草案"
                                transcript += AgentChatMessage(
                                    ROLE_USER,
                                    "[控制未执行] ${spec.name}：$reason。不要对用户声称已经执行；先说明现状。",
                                )
                                onEvent(
                                    AgentConversationEvent.ToolInvoked(
                                        name = call.name, label = spec.label, argsSummary = "无参数",
                                        outcome = ToolOutcome.FAILED, resultSummary = reason,
                                    ),
                                )
                                return@repeat
                            }
                            onEvent(
                                AgentConversationEvent.ToolInvoked(
                                    name = call.name, label = spec.label, argsSummary = "无参数",
                                    outcome = ToolOutcome.DONE, resultSummary = "交给界面按最新状态执行",
                                ),
                            )
                            onEvent(AgentConversationEvent.ControlRequested(control))
                            transcript += AgentChatMessage(
                                ROLE_USER,
                                "[控制] 已确认用户意图为「${spec.label}」；界面会按最新状态执行，" +
                                    "你在收到执行结果前不要宣布它已生效。",
                            )
                            // 控制意图交给界面落地，本轮到此为止；界面执行后会写回确认/取消记录。
                            return AgentSessionState(transcript.toList(), pending.toList())
                        }
                    }
                }
            }
        }
        return AgentSessionState(transcript.toList(), pending.toList())
    }

    /** 评审结论：passed 之外的 display 给用户看，transcriptLine 喂回循环。 */
    private data class ConflictVerdict(val passed: Boolean, val display: String, val transcriptLine: String)

    /**
     * The structural evaluator-optimizer checkpoint: every model-chosen placement passes
     * 小盾 before the user ever sees a confirmation card. The reviewer gets one narrow
     * context — the candidate plus today's schedule — and must answer 通过 or 冲突, so a
     * small model stays reliable. No response from the reviewer never blocks queueing:
     * a dead reviewer is not a conflict, and the confirmation gate is still ahead.
     */
    private suspend fun conflictReview(
        call: AgentToolCall,
        spec: AgentToolSpec,
        context: AgentTurnContext,
    ): ConflictVerdict {
        val guard = personas.firstOrNull { it.role == AgentRole.CONFLICT }
            ?: return ConflictVerdict(true, "", "")
        val schedule = context.occupiedEntries.sortedBy(TimelineEntry::startMinute).take(15)
            .joinToString("\n") { entry -> "- ${entry.kind.name}「${entry.title}」${span(entry.startMinute, entry.endMinute)}" }
            .ifEmpty { "（今日暂无安排）" }
        val windows = DayFacts.of(context.date, context.occupiedEntries).freeWindows
            .take(6).joinToString("、") { span(it.first, it.last + 1) }
            .ifEmpty { "无" }
        val turn = chat.complete(
            listOf(
                AgentChatMessage(
                    ROLE_SYSTEM,
                    guard.introPrompt + "\n这是排期写入入队前的最终检查，只依据用户消息里列出的事实判断：" +
                        "候选是否与已有安排撞车、是否把休息或课间余量挤掉、时长是否放得进空档。" +
                        "整段完整落进某个已有任务块里面是「同一时间做两件事」，那件大任务是它的父级，不算冲突；" +
                        "只跟已有安排重叠一半、压到课程/固定事项/休息，或压到锁定与不可避免的安排，才算冲突。" +
                        "没有问题第一行只写「通过」；有问题第一行写「冲突」，第二行给一句话理由。" +
                        "没有列出的安排不许当作存在，不许编造。",
                ),
                AgentChatMessage(
                    ROLE_USER,
                    "候选写入：${describeWrite(spec, call.arguments())}\n" +
                        "今日安排：\n$schedule\n30 分钟以上空档：$windows\n今天日期：${context.date}",
                ),
            ),
        )
        return when (turn) {
            is AgentTurn.Failed -> ConflictVerdict(
                true, "评审员没响应，放行待确认",
                "[小盾评审] 评审员没响应；候选仍进入待确认队列，由用户最终把关。",
            )
            is AgentTurn.Reply -> {
                val firstLine = turn.text?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                if (firstLine.startsWith("通过")) {
                    ConflictVerdict(true, firstLine.ifBlank { "通过" }, "[小盾评审] 候选时段检查通过。")
                } else {
                    val reason = turn.text?.trim()?.takeIf(String::isNotEmpty) ?: "评审员没有给出理由"
                    ConflictVerdict(
                        false, reason,
                        "[未加入待确认队列] ${spec.name}：小盾评审没通过——$reason 请换个时段或先向用户说明，然后重试。",
                    )
                }
            }
        }
    }

    private suspend fun consultSpecialist(
        context: AgentTurnContext,
        arguments: JSONObject,
        onEvent: suspend (AgentConversationEvent) -> Unit,
    ): AgentToolResult {        val persona = personas.firstOrNull { it.id == arguments.optString("member") || it.name == arguments.optString("member") }
            ?: return AgentToolResult(
                false,
                "没有这个成员，可选：${personas.filterNot { p -> p.isPlanner }.joinToString("、") { p -> "${p.name}(${p.id})" }}",
            )
        val question = arguments.optString("question").trim()
        if (question.isEmpty()) return AgentToolResult(false, "缺少 question")
        val turn = chat.complete(
            listOf(
                AgentChatMessage(
                    ROLE_SYSTEM,
                    persona.introPrompt + "\n你正在被规划师私下咨询，回答会转述给用户。用中文，两句话以内，先给结论，" +
                        "只能依据提供的信息，不许编造。" +
                        // 没有这一段的话专家只能凭人设猜：同一条记忆至少要让每个角色都读到同一份。
                        "\n${DayFacts.of(context.date, context.occupiedEntries).briefing()}" +
                        context.memoryBriefing().takeIf(String::isNotEmpty)
                            ?.let { "\n用户记忆：\n$it" }.orEmpty(),
                ),
                AgentChatMessage(ROLE_USER, question),
            ),
        )
        return when (turn) {
            is AgentTurn.Failed -> AgentToolResult(false, "${persona.name}没能回答")
            is AgentTurn.Reply -> {
                val text = turn.text?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: return AgentToolResult(false, "${persona.name}没有给出回答")
                onEvent(AgentConversationEvent.SpecialistSaid(persona.id, persona.name, text))
                AgentToolResult(true, "${persona.name}：$text")
            }
        }
    }

    // ------------------------------------------------------------------ prompts

    private fun systemPrompt(context: AgentTurnContext): AgentChatMessage {
        val date = context.date
        val override = systemPromptOverride
        if (override != null) {
            return AgentChatMessage(
                ROLE_SYSTEM,
                buildString {
                    appendLine(override(context))
                    appendLine(ENVELOPE_RULES)
                    appendLine(CONVERSATION_RULES)
                    appendLine("可用工具：")
                    appendToolCatalog()
                    appendLine("今天日期：$date（星期${"一二三四五六日"[(date.dayOfWeek.value - 1) % 7]}）。")
                    context.domainBriefing.takeIf(String::isNotBlank)?.let { appendLine(it) }
                    context.memoryBriefing().takeIf(String::isNotBlank)?.let { lines ->
                        appendLine(lines)
                        appendLine("这些记忆只用来决定「先排什么、排在谁旁边」，不要拿来编造用户没做过的进度。")
                    }
                }.trimEnd(),
            )
        }
        val facts = DayFacts.of(date, context.occupiedEntries)
        val planner = personas.firstOrNull { it.isPlanner } ?: personas.last()
        return AgentChatMessage(
            ROLE_SYSTEM,
            buildString {
                appendLine("你是学习规划小组的规划师「${planner.name}」。用户在群聊里说话，组里还有：" +
                    personas.filterNot { it.isPlanner }.joinToString("、") { "${it.name}（${roleName(it.role)}）" } + "。")
                appendLine("一切实际操作都通过工具完成；引用的数字只能来自工具结果，不许编造。")
                appendLine("回答涉及任务/安排等当前状态的问题前，必须先调用读取工具现查；" +
                    "对话历史里出现过的工具结果只是当时的快照，用户随时可能改过，不得拿来当最新数据。")
                appendLine(ENVELOPE_RULES)
                appendLine(CONVERSATION_RULES)
                appendLine("批量优先：用户一口气说了几件事，就用 plan_tasks 一次提交（items 里写全每一件事，" +
                    "清单里已有的给 taskId、新的给 name），建好和排时间会合成一张确认卡，一次确认全部落地；" +
                    "不要先让用户逐条确认清单，也不要一件一件 create_task 排队。缺的信息用默认值先排上：" +
                    "时长没给就按 60 分钟或任务自带时长，时间没给就交给它自动挑空档。")
                appendLine("建事项并排期不必先调用读取工具：今日实况与 30 分钟以上空档已经写在下面，" +
                    "plan_tasks 入队前还会按最新数据重算；但回答任务或安排的现状类问题仍然必须现查。")
                // 写在下面的实况只有界面那一天；不说清楚，模型会把"挪到周五"照今天的时间排。
                appendLine("下面那份实况只写了 $date 这一天。用户说明天/下周几/某个日期，就把那一天原样写进工具的 date，" +
                    "别按今天的时间排；看不见那一天时工具会退回「看不到那天的安排」，那时照实说，不要挑别的日子代替。")
                appendLine("改一件已有任务的名称、时长、计划日或截止日，用 update_task 只写要改的那几个字段；" +
                    "不要先 cancel 再新建，那样丢掉它的历史和已排好的时段。")
                appendLine("工具说「没有登记可用档」或用户说排不下的时候，问清哪几天哪些时段真的有空，" +
                    "用 set_available_time 登记上（空闲 free=true，开会值班这类占用 free=false），再重排；" +
                    "不要让用户自己去时间设置里填表。")
                // 日常安排是周模板，不是待办任务：模型最容易犯的错是把「每天」写成一条单日任务。
                appendLine("用户要的是每周重复的固定占用（「排成日常事项」「每天这个时间玩」「每个工作日下午」" +
                    "「每周三晚上」）时，用 save_routine：name + weekdays + durationMinutes，它是固定安排不是待办任务；" +
                    "只有用户点名了钟点才写 start（或 end），说「看着空档/合适时间/你定/你安排」或没给钟点时省掉 start，" +
                    "助手会按那几天的共同空档挑一个固定时段。say 里只能说「按共同空档挑一个固定时段」，" +
                    "挑中的钟点由确认卡告诉你；不要说成「每天自动挑/不固定钟点/每天临时看」，" +
                    "也不要在 say 里报任何钟点。不要用 create_task 建，也绝不能把「每天」只排成一天。" +
                    "改时间或改日子时给 replace=true，同名旧安排会在同一次确认里被替换，不要先删再排两道确认。" +
                    "固定安排不能跨天：end 必须晚于 start，最多到 24:00；拿不准就不写 end，只写 durationMinutes，" +
                    "不要在 say 里报一个参数里没有的钟点。")
                appendLine("取消日常安排用 remove_routine：只给名称就取消同名全部，weekdays 只取消其中几天；" +
                    "先 query_routines 核对原名。取消固定安排不会动任务本身或已排的时段。")
                appendLine("写入之前必须先读取相关现状并以其为准：编号、时段、时长都要来自刚才的读取结果；" +
                    "历史消息里的状态是快照，用户随时可能手动改过。只有会影响排法的事实缺口才问用户，" +
                    "能先给方案的一律先做成确认卡。")
                appendLine("放入/移动时段的写入在排队前会自动经过小盾的冲突检查；" +
                    "收到「评审没通过」就换个时段或先跟用户说明，不要原样重试。")
                appendLine("可用工具：")
                appendToolCatalog()
                appendLine("今日日期：$date（星期${"一二三四五六日"[(date.dayOfWeek.value - 1) % 7]}）。")
                appendLine(facts.briefing())
                context.memoryBriefing().takeIf(String::isNotEmpty)?.let { lines ->
                    appendLine(lines)
                    appendLine("这些记忆只用来决定「先排什么、排在谁旁边」，不要拿来编造用户没做过的进度。")
                }
            },
        )
    }

    private fun roleName(role: AgentRole): String = when (role) {
        AgentRole.REQUIREMENTS -> "需求分析"
        AgentRole.AVAILABILITY -> "时间管理"
        AgentRole.HABIT -> "习惯偏好"
        AgentRole.CONFLICT -> "冲突检查"
        AgentRole.PLANNER -> "规划"
    }

    /** 两种场景共用同一份工具目录，参数格式只在这里定义一次。 */
    private fun StringBuilder.appendToolCatalog() {
        toolbox.specs().filterNot { it.hidden }.forEach { spec ->
            val params = spec.params.joinToString(", ") { param ->
                val enums = if (param.enumValues.isEmpty()) "" else "，可选：${param.enumValues.joinToString("/")}"
                "${param.name}${if (param.required) "" else "?"}（${param.description}$enums）"
            }
            val kindLabel = when (spec.kind) {
                AgentToolKind.READ -> "读取"
                AgentToolKind.WRITE -> "写入·需确认"
                AgentToolKind.CONTROL -> "控制"
            }
            append("- ${spec.name}（${spec.label}，$kindLabel）：${spec.description}")
            if (params.isNotEmpty()) appendLine("。参数：$params") else appendLine()
        }
    }

    private fun controlOf(name: String): AgentControl? = when (name) {
        "confirm_draft" -> AgentControl.CONFIRM_DRAFT
        "cancel_draft" -> AgentControl.CANCEL_DRAFT
        "undo_last" -> AgentControl.UNDO_LAST
        else -> null
    }

    // ------------------------------------------------------------------ helpers

    private fun envelopeFor(turn: AgentTurn.Reply): String = turn.toolCall?.let { call ->
        JSONObject()
            .put("say", turn.text ?: "")
            .put("tool", JSONObject().put("name", call.name).put("arguments", call.arguments()))
            .toString()
    } ?: JSONObject().put("say", turn.text ?: "").toString()

    private fun describeArguments(arguments: JSONObject): String {
        val names = arguments.names() ?: return "无参数"
        if (names.length() == 0) return "无参数"
        return (0 until names.length()).joinToString(" · ") { index ->
            val key = names.getString(index)
            "$key=${arguments.opt(key)}"
        }
    }

    private fun describeWrite(spec: AgentToolSpec, arguments: JSONObject): String =
        describeAgentWrite(spec, arguments)

    private fun span(startMinute: Int, endMinute: Int): String =
        "${"%02d:%02d".format(startMinute / 60, startMinute % 60)}-${"%02d:%02d".format(endMinute / 60, endMinute % 60)}"

    /** 与旧群聊引擎同一套实况口径：课程/固定/休息是硬占用，空档是其余补集。 */
    private data class DayFacts(
        val hardBusy: List<TimelineEntry>,
        val freeWindows: List<IntRange>,
    ) {
        /** 规划师和被咨询的专家共用同一段实况，免得两边各排各的口径。 */
        fun briefing(): String = buildString {
            appendLine("今日实况：")
            if (hardBusy.isEmpty()) {
                appendLine("- 没有课程/固定事项/休息占用")
            } else {
                hardBusy.take(10).forEach {
                    appendLine("- ${it.kind.name} ${it.title} ${fmt(it.startMinute)}-${fmt(it.endMinute)}（不可占用）")
                }
            }
            if (freeWindows.isEmpty()) {
                appendLine("- 今天没有 30 分钟以上的连续空档")
            } else {
                appendLine("- 30 分钟以上空档：" + freeWindows.take(6).joinToString("、") { "${fmt(it.first)}-${fmt(it.last + 1)}" })
            }
            // 作息是规划条件不是待排资源：模型看不到这条就会在深夜替人找空档。
            appendLine(
                "- 睡眠窗口 ${fmt(ChatDayPlanner.SLEEP_START_MINUTE)}–${fmt(ChatDayPlanner.SLEEP_END_MINUTE)} 不排任务，" +
                    "睡前一小时内也别排用脑的事；今天实在排不下就说明并建议挪到明天，不要压到夜里。" +
                    "用户亲口点名的凌晨时间照办，但要提醒一句睡眠。",
            )
        }.trimEnd()

        private fun fmt(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

        companion object {
            private const val MIN_WINDOW_MINUTES = 30

            fun of(date: LocalDate, occupied: List<TimelineEntry>): DayFacts {
                val hardBusy = occupied
                    .filter { it.kind in HARD_BUSY }
                    .sortedBy(TimelineEntry::startMinute)
                val windows = mutableListOf<IntRange>()
                var cursor = 0
                hardBusy.forEach { entry ->
                    if (entry.startMinute > cursor) windows += cursor until entry.startMinute
                    if (entry.endMinute > cursor) cursor = entry.endMinute
                }
                if (cursor < MINUTES_PER_DAY) windows += cursor until MINUTES_PER_DAY
                val dayStart = ChatDayPlanner.DEFAULT_DAY_START_MINUTE
                val dayEnd = ChatDayPlanner.DEFAULT_DAY_END_MINUTE
                return DayFacts(
                    hardBusy = hardBusy,
                    freeWindows = windows.mapNotNull { window ->
                        val from = maxOf(window.first, dayStart)
                        val to = minOf(window.last, dayEnd - 1)
                        if (to - from + 1 >= MIN_WINDOW_MINUTES) from..to else null
                    },
                )
            }

            private const val MINUTES_PER_DAY = 24 * 60
            val HARD_BUSY = setOf(TimelineKind.COURSE, TimelineKind.REST, TimelineKind.COMMITMENT)
        }
    }

    companion object {
        /** 咨询专家成员的内置读取工具：由引擎自己执行，不经过 toolbox。 */
        const val SPECIALIST_TOOL = "ask_specialist"
        /** 模型自选时段的写入在入队前必须过小盾评审；自动排期的落点不在此列。 */
        val CONFLICT_REVIEWED = setOf("place_task", "move_placement")
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 单行 JSON 信封契约：个人与团队场景必须一字不差，模型才不会换格式。 */
        private const val ENVELOPE_RULES = "回复必须是单行 JSON，两种形式之一：\n" +
            "需要调用工具：{\"say\":\"给用户看的一句话\",\"tool\":{\"name\":\"工具名\",\"arguments\":{…}}}\n" +
            "不需要工具（最终回答）：{\"say\":\"给用户看的一句话\"}\n" +
            "规则：say 用中文，不超过 60 字；带工具那一轮的 say 先播报你打算做什么（如「我先查今天的空档」），" +
            "让用户知道这一步在干嘛；一条回复只带一个工具，所以要把一次意图装进一个工具调用里；" +
            "写入类工具不会立即执行，会先列给用户确认，你在收到确认结果前不要声称它已生效；" +
            "用户明确表示「就按这个执行/确认/排吧」时调用 confirm_draft，" +
            "明确表示「取消/先不要/不排了」时调用 cancel_draft，要撤销刚确认的一批写入时调用 undo_last；" +
            "用户仍在讨论、补充或犹豫时不要调用控制工具，也不要替用户做这个决定。"

        /** 连续对话与状态写入的纪律：与网页版同一套说法。 */
        private const val CONVERSATION_RULES = "用户只给了时间、没说任务名时，问清是哪一个任务，" +
            "不要替用户从清单里挑。\n" +
            "对话记录是连续上下文：用户用很短的回答补全你上一轮的问题（例如你先问「13:00 排哪个任务」、" +
            "用户答「吃饭」）时，要把两轮合起来理解，时间从上一轮的问题里接过来。\n" +
            "完成、取消、顺延、进度是用户亲口说了才算的事实：这句话里没有对应的意思就先问清，" +
            "不要猜成完成；写成将来的安排不等于已经做完。\n" +
            "复盘不逼用户选完成/部分/顺延：用户说没做完、需要再安排一些时间时，" +
            "先用 record_feedback 记下进度，再用 plan_tasks 在空档另加一段；原来那段保留不动。" +
            "要改期才用 move_placement（挪原段），再给时间用 plan_tasks（会多一段），两者不要混。"
    }
}
