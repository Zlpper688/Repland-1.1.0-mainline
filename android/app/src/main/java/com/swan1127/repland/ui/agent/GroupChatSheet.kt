package com.swan1127.repland.ui.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import com.swan1127.repland.ui.components.DesignDialog as AlertDialog
import androidx.compose.material3.Button
import com.swan1127.repland.ui.components.DesignCheckbox as Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import com.swan1127.repland.ui.components.DesignTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.swan1127.repland.ui.components.DesignTextButton as TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.swan1127.repland.data.AgentProfileStore
import com.swan1127.repland.data.AssistantJournal
import com.swan1127.repland.data.AssistantJournalStore
import com.swan1127.repland.data.JournalEntry
import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentBatchPreviewRow
import com.swan1127.repland.domain.model.AgentActionResult
import com.swan1127.repland.domain.model.AgentChatMessage
import com.swan1127.repland.domain.model.AgentControl
import com.swan1127.repland.domain.model.AgentConversationEngine
import com.swan1127.repland.domain.model.AgentConversationEvent
import com.swan1127.repland.domain.model.describeAgentWrite
import com.swan1127.repland.domain.model.AgentProfile
import com.swan1127.repland.domain.model.AgentRole
import com.swan1127.repland.domain.model.AgentSessionState
import com.swan1127.repland.domain.model.AgentToolbox
import com.swan1127.repland.domain.model.AgentTurn
import com.swan1127.repland.domain.model.AgentTurnContext
import com.swan1127.repland.domain.model.AgentTurnModel
import com.swan1127.repland.domain.model.AgentWriteUndo
import com.swan1127.repland.domain.model.AiAdvisorFailureReason
import com.swan1127.repland.domain.model.ChatDayPlanner
import com.swan1127.repland.domain.model.DailyBrief
import com.swan1127.repland.domain.model.MemoryReference
import com.swan1127.repland.domain.model.SchedulingStyle
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.ToolOutcome
import com.swan1127.repland.domain.model.UserProfile
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.PlannerIcons
import com.swan1127.repland.ui.components.DurationWheelField
import com.swan1127.repland.ui.components.rememberDurationWheelHold
import com.swan1127.repland.ui.components.TimeWheelField
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one-sentence entry as a group chat with a real tool loop.
 *
 * Three kinds of lines are visually distinct on purpose — they are different *kinds* of
 * evidence about what happened:
 * - 文字输出: the members' prose, normal body size.
 * - 工具调用: each real tool execution, small mono-feel log lines; a line exists only
 *   because a tool actually ran, with its arguments and result on display.
 * - 逻辑调整: state changes — queued pending actions (amber, behind the confirmation
 *   gate) and their executed outcomes.
 *
 * Writes never execute inside the model's turn. They queue as pending actions; only the
 * user's 确认 — per item or in batch — runs them, and the results are fed back so the
 * planner continues from what actually happened.
 */
/** 助手日记里每日简报的主题名：同一天多次刷新按它成链。 */
private const val BRIEF_TOPIC = "今日简报"

/** 超过这个间隔再打开，上一段对话算结束：没确认的草案过期，只能看不能点。 */
private const val SESSION_IDLE_LIMIT_MS = 12 * 60 * 60 * 1000L

/** 可见消息只留最近这些；更早的内容已经在协议记录里压成摘要。 */
private const val MAX_VISIBLE_MESSAGES = 60

/** 协议记录只留最近这些条，更早的压成一行摘要继续供模型参考。 */
private const val MAX_TRANSCRIPT_MESSAGES = 40

internal enum class DraftStatus(val label: String) {
    COMMITTED("已执行"),
    CANCELLED("已取消"),
    EXPIRED("已过期"),
    REPLACED("已更新"),
}

internal data class DraftRecordRow(val name: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatSheet(
    activeDate: LocalDate,
    occupiedEntries: List<TimelineEntry>,
    cloudEnabled: Boolean,
    /** 用户已授权但云端此刻不可用时，允许本地规则接住形状确定的那一句；授权没开就不接。 */
    offlineWritesEnabled: Boolean = false,
    /** 云端不可用的真实原因：本地数据没就绪说没就绪，别一律报成「没开 AI」。 */
    blockedReason: AiAdvisorFailureReason = AiAdvisorFailureReason.DISABLED,
    turnModel: AgentTurnModel,
    toolbox: AgentToolbox,
    /** 批量写入的回退能力；null 表示宿主没接，界面就不出现撤销入口。 */
    writeUndo: AgentWriteUndo? = null,
    /** 从本地数据沉淀的记忆种子；空列表表示还没有可依赖的事实。 */
    rememberedFacts: List<Pair<String, String>> = emptyList(),
    memoryProfile: MemoryReference? = null,
    memoryEpisodes: List<String> = emptyList(),
    /** 用户亲口写的「关于我」自述；空串表示还没写。进提示词，排在推测记忆前面。 */
    personaDoc: String = "",
    /** 内容画像（已授权时才有）；空串表示没有。 */
    portraitNote: String = "",
    /** 助手日记落盘；null 表示宿主没接，简报仍在聊天里出，只是不留痕、没有日记入口的历史。 */
    journalStore: AssistantJournalStore? = null,
    /** 每次确认执行写入后回调；调用方用它沉淀记忆等副作用。 */
    onWriteExecuted: (AgentAction, Boolean, String) -> Unit = { _, _, _ -> },
    /** 长按回复里的喜欢/不喜欢：隐式记忆证据，由调用方落库。 */
    onReplyFeedback: (liked: Boolean, snippet: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { AgentProfileStore(context) }
    val sessionStore = remember { AgentChatSessionStore(context) }
    var agents by remember { mutableStateOf(store.loadAgents()) }
    var user by remember { mutableStateOf(store.loadUser()) }
    val planner = agents.firstOrNull { it.isPlanner } ?: agents.last()
    var lastActiveAt by remember { mutableStateOf(System.currentTimeMillis()) }
    /** 模型读懂的控制意图，等这一轮结束再由界面落地，避免和引擎写回互相覆盖。 */
    var requestedControl by remember { mutableStateOf<AgentControl?>(null) }
    var confirmNewSession by remember { mutableStateOf(false) }

    var session by remember {
        mutableStateOf(
            // Restore the protocol transcript with the messages, so confirming a pending
            // action after reopening the sheet still resumes with full context.
            AgentSessionState(
                transcript = GroupChatSession.transcript,
                pendingActions = GroupChatSession.pending,
            ),
        )
    }
    var messages by remember {
        mutableStateOf(
            GroupChatSession.items.ifEmpty {
                listOf<ChatItem>(ChatItem.Agent("welcome", planner.id, "我在。要安排什么，直接说。"))
            },
        )
    }
    var input by remember { mutableStateOf(GroupChatSession.draft) }
    var busy by remember { mutableStateOf(false) }
    var seq by remember { mutableIntStateOf(GroupChatSession.nextId) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showManagement by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var batchUndoToken by remember { mutableStateOf<Any?>(null) }
    var liveTool by remember { mutableStateOf<String?>(null) }
    var plusMenu by remember { mutableStateOf(false) }
    var showToolCatalog by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var seedIndex by remember { mutableIntStateOf(0) }
    var showDiary by remember { mutableStateOf(false) }
    var journal by remember { mutableStateOf(AssistantJournal()) }
    val inputFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val engine = remember(turnModel, toolbox) { AgentConversationEngine(chat = turnModel, toolbox = toolbox) }

    fun nowLabel(): String {
        val now = LocalDateTime.now()
        return "%02d:%02d".format(now.hour, now.minute)
    }

    /** 草案定稿成一张只读记录：标题、逐件内容、最终状态和时间。 */
    fun draftRowsOf(actions: List<AgentAction>): List<DraftRecordRow> {
        val rows = mutableListOf<DraftRecordRow>()
        actions.forEach { action ->
            val preview = runCatching { toolbox.batchPreview(action) }.getOrDefault(emptyList())
            if (preview.isNotEmpty()) {
                preview.forEach { row ->
                    rows += DraftRecordRow(
                        name = row.name,
                        detail = "${chatFormatMinute(row.startMinute)}–${chatFormatMinute(row.endMinute)} · ${row.durationMinutes} 分钟",
                    )
                }
            } else {
                rows += DraftRecordRow(action.label, action.summary)
            }
        }
        return rows
    }

    fun draftRecordOf(actions: List<AgentAction>, status: DraftStatus, detail: String? = null): ChatItem.DraftRecord {
        val rows = draftRowsOf(actions).let { list ->
            if (detail == null) list else list + DraftRecordRow("结果", detail)
        }
        val title = when {
            actions.size == 1 -> actions.first().label
            else -> "排期草案"
        }
        return ChatItem.DraftRecord(
            id = "draft-${seq++}",
            title = title,
            rows = rows,
            status = status,
            time = nowLabel(),
        )
    }

    /** 可见对话只留最近一段：更早的交给协议摘要，不再占屏幕。 */
    fun capMessages(items: List<ChatItem>): List<ChatItem> =
        if (items.size <= MAX_VISIBLE_MESSAGES) items else items.takeLast(MAX_VISIBLE_MESSAGES)

    /**
     * 协议记录也留最近一段：丢掉的旧话压成一行摘要放在最前，模型的连续性不断，
     * 但提示词不会随会话天数无限膨胀。
     */
    fun condenseTranscript(lines: List<AgentChatMessage>): List<AgentChatMessage> {
        if (lines.size <= MAX_TRANSCRIPT_MESSAGES) return lines
        val dropped = lines.dropLast(MAX_TRANSCRIPT_MESSAGES - 1)
        val digest = dropped.joinToString("；") { line ->
            val text = line.content.replace("\n", " ").trim().take(80)
            if (line.role == AgentConversationEngine.ROLE_USER) "用户：$text" else "助手：$text"
        }.takeLast(1_400)
        return listOf(
            AgentChatMessage(AgentConversationEngine.ROLE_USER, "[较早对话摘要] $digest"),
        ) + lines.takeLast(MAX_TRANSCRIPT_MESSAGES - 1)
    }

    // 关掉软件再打开：消息、协议、草案、账本、没发出去的输入都回来。
    // 同进程内重开面板时内存里更新，磁盘反而可能慢半拍，所以内存优先。
    LaunchedEffect(Unit) {
        if (GroupChatSession.items.isNotEmpty() || GroupChatSession.transcript.isNotEmpty()) return@LaunchedEffect
        val snapshot = sessionStore.load() ?: return@LaunchedEffect
        lastActiveAt = snapshot.lastActiveAtMillis ?: System.currentTimeMillis()
        val boundaryExpired = snapshot.lastActiveAtMillis?.let {
            System.currentTimeMillis() - it > SESSION_IDLE_LIMIT_MS
        } ?: false
        var restoredItems = snapshot.items
        var restoredSession = AgentSessionState(snapshot.transcript, snapshot.pending)
        if (boundaryExpired && snapshot.pending.isNotEmpty()) {
            // 会话已结束：上一段留下的草案只能看，不能再点确认。
            restoredItems = restoredItems + draftRecordOf(snapshot.pending, DraftStatus.EXPIRED)
            restoredSession = AgentSessionState(
                transcript = snapshot.transcript + AgentChatMessage(
                    AgentConversationEngine.ROLE_USER,
                    "[会话已结束] 上次未确认的草案已过期，不能再确认；其中的事项都没有写入。需要时请重新安排。",
                ),
                pendingActions = emptyList(),
            )
        }
        messages = restoredItems.filterNot { it is ChatItem.Typing }.ifEmpty { messages }
        session = restoredSession
        seq = maxOf(seq, snapshot.nextId)
        input = snapshot.draft
        GroupChatSession.createdTasks = snapshot.createdTasks
        GroupChatSession.scheduledTaskIds = snapshot.scheduledTaskIds
        (toolbox as? AgentAppToolbox)?.adoptLedger(snapshot.createdTasks, snapshot.scheduledTaskIds)
    }

    // Closing and reopening the sheet must not erase the conversation, and the protocol
    // session must survive too so confirming an earlier pending action still resumes with
    // the full transcript. A busy spinner is deliberately not persisted for the same
    // reason: it is a claim that a coroutine that died with the sheet is still running.
    LaunchedEffect(messages) {
        GroupChatSession.items = messages
        GroupChatSession.nextId = seq
    }
    LaunchedEffect(session) {
        GroupChatSession.transcript = session.transcript
        GroupChatSession.pending = session.pendingActions
    }
    // 关掉面板不等于放弃这句话：没发出去的输入跟着会话一起留着，下次打开还在。
    LaunchedEffect(input) { GroupChatSession.draft = input }

    // 落盘按「消息条数 / 协议 / 输入」触发；逐字浮现只改同一条的 revealed，不反复写文件。
    LaunchedEffect(messages.size, session, input, lastActiveAt) {
        delay(250)
        sessionStore.save(
            PersistedAgentSession(
                items = messages,
                transcript = session.transcript,
                pending = session.pendingActions,
                nextId = seq,
                createdTasks = GroupChatSession.createdTasks,
                scheduledTaskIds = GroupChatSession.scheduledTaskIds,
                draft = input,
                lastActiveAtMillis = lastActiveAt,
            ),
        )
    }

    LaunchedEffect(messages.size, session.pendingActions.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // 回合结束（含失败/停止）就收回状态行：它描述的是正在跑的那一轮，不是永久标题。
    LaunchedEffect(busy) { if (!busy) liveTool = null }

    /**
     * Types a finished message out character by character. The transport returns the whole
     * string in one response, so this is an animation, not a stream.
     */
    suspend fun reveal(id: String, agentId: String, text: String) {
        messages = messages + ChatItem.Agent(id, agentId, text, revealed = 0)
        val total = text.length
        // ~48 frames whatever the length: a long report must not type for five seconds.
        val step = maxOf(1, total / 48)
        var shown = 0
        while (shown < total) {
            delay(14)
            shown = minOf(total, shown + step)
            messages = messages.map { item ->
                if (item.id == id && item is ChatItem.Agent) item.copy(revealed = shown) else item
            }
        }
    }

    fun handleEvent(event: AgentConversationEvent) {
        when (event) {
            is AgentConversationEvent.ToolStarted -> liveTool = event.label

            is AgentConversationEvent.TextOutput ->
                scope.launch { reveal("a-${seq++}", planner.id, event.text) }

            is AgentConversationEvent.SpecialistSaid ->
                scope.launch { reveal("a-${seq++}", event.agentId, event.text) }

            is AgentConversationEvent.ToolInvoked ->
                messages = messages + ChatItem.ToolCall(
                    id = "t-${seq++}",
                    label = event.label,
                    argsSummary = event.argsSummary,
                    resultSummary = event.resultSummary,
                    outcome = event.outcome,
                )

            is AgentConversationEvent.ActionQueued -> {
                // 入队就亮卡：等整轮工具循环跑完才渲染，慢模型下用户会以为这句话丢了。
                // 引擎返回的 session 里同样含这条待确认，回合结束时整体覆盖不会重复。
                session = session.copy(pendingActions = session.pendingActions + event.action)
                lastActiveAt = System.currentTimeMillis()
            }

            is AgentConversationEvent.DraftReplaced -> {
                // 同槽新方案已经取代旧草案：旧卡转为只读记录，界面上不出现两张待确认卡。
                val ids = event.previous.map { it.id }.toSet()
                session = session.copy(pendingActions = session.pendingActions.filterNot { it.id in ids })
                messages = messages + draftRecordOf(event.previous, DraftStatus.REPLACED)
            }

            is AgentConversationEvent.ControlRequested -> requestedControl = event.control

            is AgentConversationEvent.Failed ->
                messages = messages + ChatItem.Notice("f-${seq++}", event.reason, event.agentName)

            is AgentConversationEvent.LocalFallback -> Unit
        }
    }

    fun turnContext() = AgentTurnContext(
        date = activeDate,
        occupiedEntries = occupiedEntries,
        memoryNotes = rememberedFacts,
        memoryProfile = memoryProfile,
        memoryEpisodes = memoryEpisodes,
        personaDoc = personaDoc,
        portraitNote = portraitNote,
    )

    suspend fun readSlice(toolName: String): String =
        runCatching { toolbox.runRead(toolName, JSONObject()).summary }
            .getOrElse { "（读取失败：${it.message ?: "未知错误"}）" }

    /**
     * 定向咨询：被点名的专家只答自己岗位内的事，数据切片按岗位配——
     * 小析=任务清单、小刻=空档、小盾=今日安排（都来自真实读取工具），
     * 小习=已沉淀的记忆。没有工具循环：纯岗位应答，不产生写入。
     * （局部函数需先声明再引用，所以放在 send() 之前。）
     */
    suspend fun directConsult(agent: AgentProfile, raw: String) {
        val question = raw.replace("@${agent.name}", "").trim().ifBlank { raw.trim() }
        val briefing = turnContext().memoryBriefing()
        val roleSlice: String = when (agent.role) {
            AgentRole.REQUIREMENTS -> readSlice("query_tasks")
            // 小刻光看空档不够：空档是"时间轴上没东西"，预算才是"登记过的空闲里还能排多久"。
            AgentRole.AVAILABILITY -> "${readSlice("query_free_windows")}\n${readSlice("query_time_budget")}"
            AgentRole.CONFLICT -> readSlice("query_schedule")
            // 小习的本职就是记忆：给它完整画像，而不是只有习惯键值。
            AgentRole.HABIT -> briefing.ifBlank { "（还没有沉淀的记忆）" }
            AgentRole.PLANNER -> "（规划师不在此列）"
        }
        // 其余角色也带上同一段记忆：同一条用户习惯不该只有小习看得见，否则各自给出的建议会互相打架。
        val slice = if (agent.role == AgentRole.HABIT || briefing.isBlank()) {
            roleSlice
        } else {
            "$roleSlice\n【用户记忆】$briefing"
        }
        messages = messages + ChatItem.ToolCall(
            id = "t-${seq++}",
            label = "定向咨询",
            argsSummary = "@${agent.name} $question",
            resultSummary = roleSlice.take(80),
            outcome = ToolOutcome.DONE,
        )
        val typingId = "typing-${seq++}"
        messages = messages + ChatItem.Typing(typingId, agent.id)
        val turn = turnModel.complete(
            listOf(
                AgentChatMessage(
                    AgentConversationEngine.ROLE_SYSTEM,
                    agent.introPrompt + "\n用户在群聊里@了你。只回答你岗位内的问题；" +
                        "只依据下面提供的实时数据说话，数据里没有的就直说没有，不许编造，" +
                        "也不要执行或承诺任何写入操作。用中文，三句话以内。",
                ),
                AgentChatMessage(
                    AgentConversationEngine.ROLE_USER,
                    "【你的岗位数据】$slice\n【用户的话】$question",
                ),
            ),
        )
        messages = messages.filterNot { it.id == typingId }
        when (turn) {
            is AgentTurn.Failed ->
                messages = messages + ChatItem.Notice("f-${seq++}", turn.reason, agent.name)
            is AgentTurn.Reply -> {
                val answer = turn.text?.trim()?.takeIf { it.isNotEmpty() } ?: "（${agent.name}这次没答上来）"
                reveal("a-${seq++}", agent.id, answer)
            }
        }
    }

    /**
     * 全员自我介绍：问的是"你们"而不是某一个人时，每个成员按自己的岗位各说一句。
     * 文案来自 AgentProfile.selfIntro（可在成员管理里改），不调模型、不产生写入。
     */
    suspend fun teamIntros() {
        agents.forEach { agent ->
            val line = agent.selfIntro.trim().ifBlank { "我是${agent.name}。" }
            reveal("a-${seq++}", agent.id, line)
        }
    }

    /** 简报请求：胶囊「今日简报」和用户手打「简报」走同一条路（手动=对话等价），且不依赖云端。 */
    fun isBriefRequest(text: String): Boolean =
        text == "简报" || text.contains("今日简报") || text.contains("每日简报")

    /**
     * 生成/刷新今日简报。同一天已有简报卡就地编辑（updated=true，借编辑视角成链），否则追加一张；
     * 每次刷新都往日记里添一条快照——聊天只留最近一份，历史走助手日记（借鉴 #8/#6）。
     * 纯本地确定性整理，不调模型、不产生写入，所以离线也能出。
     */
    suspend fun refreshBrief(userInitiated: Boolean) {
        val brief = DailyBrief.of(activeDate, occupiedEntries)
        val now = LocalDateTime.now()
        val timeLabel = "%02d:%02d".format(now.hour, now.minute)
        val existing = messages.firstOrNull { it is ChatItem.Brief && it.date == activeDate }
        val card = ChatItem.Brief(
            id = existing?.id ?: "brief-${seq++}",
            date = activeDate,
            headline = brief.headline,
            evidence = brief.evidence,
            freeWindows = brief.freeWindows,
            footer = brief.footer,
            time = timeLabel,
            updated = existing != null,
        )
        messages = if (existing != null) {
            messages.map { if (it.id == existing.id) card else it }
        } else {
            messages + card
        }
        val entry = JournalEntry(
            id = UUID.randomUUID().toString(),
            date = activeDate,
            at = now,
            topic = BRIEF_TOPIC,
            summary = brief.headline,
            evidence = brief.evidence + brief.freeWindows.map { "空档 $it" },
            replied = userInitiated,
        )
        journal = journal.copy(entries = journal.entries + entry)
        journalStore?.save(journal)
    }

    /** 用户回话了：把今天还没标回复的简报如实标成"已回复"。没回话就不动，日记里直说没有后续依据。 */
    fun markBriefReplied() {
        val updated = journal.entries.map {
            if (it.date == activeDate && it.topic == BRIEF_TOPIC && !it.replied) it.copy(replied = true) else it
        }
        if (updated != journal.entries) {
            journal = journal.copy(entries = updated)
            scope.launch { journalStore?.save(journal) }
        }
    }

    // 每日简报：只在"今天确有安排"且今天还没简报过时主动贴一次（空日子不打扰，借鉴 #8 + 减负）。
    // 日记是落盘真相源：重启后今天已简报过就不再重复贴，历史仍可在助手日记里翻。
    LaunchedEffect(activeDate, journalStore) {
        val loaded = journalStore?.load() ?: AssistantJournal()
        journal = loaded
        val hasEvents = occupiedEntries.any { it.date == activeDate }
        val briefedToday = loaded.entries.any { it.date == activeDate && it.topic == BRIEF_TOPIC }
        val cardToday = messages.any { it is ChatItem.Brief && it.date == activeDate }
        if (hasEvents && !briefedToday && !cardToday) refreshBrief(userInitiated = false)
    }

    /** 确认就是那一个确定的落库步骤：先按当前状态重新准入，再写入，最后把整张草案定稿成记录。 */
    suspend fun runExecution(actions: List<AgentAction>) {
        if (actions.isEmpty()) return
        // 事务语义：批前快照，批后可整体回退。start_focus 是当下意图而非状态写入，
        // 撤销把刚启动的专注会话杀掉反而更糟，含它的批次不提供撤销。
        val undoToken = writeUndo
            ?.takeIf { actions.none { action -> action.toolName == "start_focus" } }
            ?.capture()
        val specs = toolbox.specs().associateBy { it.name }
        val executed = mutableListOf<Pair<AgentAction, AgentActionResult>>()
        actions.forEach { action ->
            // 排队时合法不等于执行时还合法：用户可能已经改过任务、挪过安排。
            val rejection = specs[action.toolName]?.validate?.invoke(JSONObject(action.argumentsJson))
            val result = if (rejection != null) {
                AgentActionResult(false, "没有写入：$rejection")
            } else {
                runCatching { toolbox.runWrite(action) }
                    .getOrElse { AgentActionResult(false, "执行失败：${it.message ?: "未知错误"}") }
            }
            executed += action to result
            // 逻辑调整：这次写入真实发生了，落成可追溯的一行。
            messages = messages + ChatItem.Adjustment(
                id = "x-${seq++}",
                label = action.label,
                summary = result.summary,
                ok = result.ok,
            )
            onWriteExecuted(action, result.ok, result.summary)
        }
        session = engine.resumeAfterActions(session, executed, turnContext()) { event -> handleEvent(event) }
        val okCount = executed.count { it.second.ok }
        val failedCount = executed.size - okCount
        messages = messages + draftRecordOf(
            actions,
            DraftStatus.COMMITTED,
            detail = if (failedCount == 0) null else "成功 $okCount 件 · 未写入 $failedCount 件",
        )
        batchUndoToken = if (undoToken != null && okCount > 0) undoToken else null
        lastActiveAt = System.currentTimeMillis()
    }

    fun executeActions(actions: List<AgentAction>) {
        if (actions.isEmpty() || busy) return
        busy = true
        job = scope.launch {
            try {
                runExecution(actions)
            } finally {
                busy = false
                job = null
            }
        }
    }

    suspend fun runUndo() {
        val token = batchUndoToken ?: return
        val ok = runCatching { writeUndo?.restore(token) == true }.getOrDefault(false)
        messages = messages + ChatItem.Adjustment(
            id = "x-${seq++}",
            label = "撤销本次写入",
            summary = if (ok) "已恢复执行前的安排、任务和可用时间" else "恢复没成功：状态可能已被其他操作改变，请手动检查",
            ok = ok,
        )
        // 回退改变了世界模型，必须让下一轮对话知道之前的执行结果不再成立。
        session = session.copy(
            transcript = session.transcript + AgentChatMessage(
                AgentConversationEngine.ROLE_USER,
                if (ok) "[用户已撤销] 上一批确认执行的写入已整体恢复到执行前的状态；" +
                    "之前收到的执行结果一律不再成立，引用现状前请重新读取。"
                else "[撤销失败] 用户尝试撤销上一批写入但没有成功，现状以读取工具为准。",
            ),
        )
        batchUndoToken = null
        lastActiveAt = System.currentTimeMillis()
    }

    fun undoBatch() {
        if (batchUndoToken == null || busy) return
        busy = true
        job = scope.launch {
            try {
                runUndo()
            } finally {
                busy = false
                job = null
            }
        }
    }

    fun cancelDraft() {
        val draft = session.pendingActions
        if (draft.isEmpty()) return
        session = session.copy(
            pendingActions = emptyList(),
            transcript = session.transcript + AgentChatMessage(
                AgentConversationEngine.ROLE_USER,
                "[用户取消了待确认草案] ${draft.joinToString("；") { it.summary }}；这些都没有执行。",
            ),
        )
        messages = messages + draftRecordOf(draft, DraftStatus.CANCELLED)
        lastActiveAt = System.currentTimeMillis()
    }

    fun skipAction(action: AgentAction) {
        val previous = session.pendingActions
        if (previous.none { it.id == action.id }) return
        val remaining = previous - action
        session = session.copy(
            pendingActions = remaining,
            transcript = session.transcript + AgentChatMessage(
                AgentConversationEngine.ROLE_USER,
                "[用户跳过了这一项] ${action.label}（${action.summary}）；它没有执行，其余照旧等待确认。",
            ),
        )
        messages = messages + ChatItem.ToolCall(
            id = "t-${seq++}",
            label = "跳过一项",
            argsSummary = action.summary,
            resultSummary = if (remaining.isEmpty()) "已从草案移除；草案至此取消" else "已从草案移除",
            outcome = ToolOutcome.SKIPPED,
        )
        if (remaining.isEmpty()) messages = messages + draftRecordOf(previous, DraftStatus.CANCELLED)
        lastActiveAt = System.currentTimeMillis()
    }

    fun updateAction(action: AgentAction) {
        val previous = session.pendingActions.firstOrNull { it.id == action.id } ?: return
        // 「改」只更新了参数，卡片摘要要按新参数重算，否则显示的还是排队时的旧值。
        val spec = toolbox.specs().firstOrNull { it.name == action.toolName }
        val refreshed = spec?.let {
            action.copy(summary = describeAgentWrite(it, JSONObject(action.argumentsJson)))
        } ?: action
        if (refreshed == previous) return
        session = session.copy(
            pendingActions = session.pendingActions.map { if (it.id == refreshed.id) refreshed else it },
            // 回写「旧 → 新」：模型下一轮读到的是用户真实改过的版本，而不是排队时的旧值。
            transcript = session.transcript + AgentChatMessage(
                AgentConversationEngine.ROLE_USER,
                "[用户修改了待确认动作] ${previous.label}：${previous.summary} → ${refreshed.summary}；按新参数等待确认。",
            ),
        )
        messages = messages + ChatItem.ToolCall(
            id = "t-${seq++}",
            label = "修改草案",
            argsSummary = previous.summary,
            resultSummary = refreshed.summary,
            outcome = ToolOutcome.DONE,
        )
        lastActiveAt = System.currentTimeMillis()
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || busy) return
        lastActiveAt = System.currentTimeMillis()
        // 简报是确定性本地能力：先于云端闸门接住，离线/没配密钥也能出（手动=对话等价）。
        if (isBriefRequest(text)) {
            input = ""
            messages = messages + ChatItem.User("u-${seq++}", text)
            scope.launch { refreshBrief(userInitiated = true) }
            return
        }
        if (!cloudEnabled) {
            if (!offlineWritesEnabled) {
                // 这一句根本没发出去：留在输入框里，改好设置或等数据就绪后可以直接再发，
                // 不让用户看着自己的话消失再重打一遍。
                messages = messages + ChatItem.Notice("f-${seq++}", blockedReason, planner.name)
                return
            }
            input = ""
            messages = messages + ChatItem.User("u-${seq++}", text)
            markBriefReplied()
            busy = true
            job = scope.launch {
                try {
                    // 一次模型都不调：接住了就出确认卡，接不住才承认云端不可用。
                    val rescued = engine.runOfflineTurn(session, text, turnContext()) { event -> handleEvent(event) }
                    if (rescued == null) {
                        messages = messages + ChatItem.Notice("f-${seq++}", blockedReason, planner.name)
                    } else {
                        session = rescued
                    }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    // 本轮炸了也必须留一行人话：不能让用户的话发出去后既没回复也没报错。
                    messages = messages + ChatItem.Notice("f-${seq++}", AiAdvisorFailureReason.TRANSPORT_FAILURE, planner.name)
                } finally {
                    messages = capMessages(messages)
                    session = session.copy(transcript = condenseTranscript(session.transcript))
                    busy = false
                    job = null
                }
            }
            return
        }
        input = ""
        keyboard?.hide()
        busy = true
        messages = messages + ChatItem.User("u-${seq++}", text)
        markBriefReplied()
        job = scope.launch {
            try {
                // @路由：点名的成员直接应答自己的岗位；没点名但问的是"你们"，全员各自自我介绍；
                // 只有两者都不是，才交给规划师的工具循环。
                val mention = agents.filterNot { it.isPlanner }.firstOrNull { text.contains("@${it.name}") }
                when {
                    mention != null -> directConsult(mention, text)
                    asksForWholeTeam(text) -> teamIntros()
                    else -> session = engine.runTurn(session, text, turnContext()) { event -> handleEvent(event) }
                }
                // 控制意图等整轮结束再由界面落地：确认仍走同一条执行路径，取消/撤销也只写一次。
                val control = requestedControl
                requestedControl = null
                when (control) {
                    AgentControl.CONFIRM_DRAFT -> runExecution(session.pendingActions)
                    AgentControl.CANCEL_DRAFT -> cancelDraft()
                    AgentControl.UNDO_LAST -> runUndo()
                    null -> Unit
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                messages = messages + ChatItem.Notice("f-${seq++}", AiAdvisorFailureReason.TRANSPORT_FAILURE, planner.name)
            } finally {
                messages = capMessages(messages)
                session = session.copy(transcript = condenseTranscript(session.transcript))
                busy = false
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        requestedControl = null
        busy = false
    }

    /** 显式开始新对话：旧消息收进记录，未确认草案取消，输入清空。 */
    fun startNewSession() {
        if (busy) return
        if (session.pendingActions.isNotEmpty()) {
            cancelDraft()
        }
        messages = listOf(ChatItem.Agent("welcome", planner.id, "我在。要安排什么，直接说。"))
        seq = 0
        session = AgentSessionState()
        batchUndoToken = null
        lastActiveAt = System.currentTimeMillis()
        GroupChatSession.createdTasks = emptyList()
        GroupChatSession.scheduledTaskIds = emptySet()
        GroupChatSession.items = messages
        GroupChatSession.transcript = emptyList()
        GroupChatSession.pending = emptyList()
        scope.launch { sessionStore.clear() }
    }

    val systemVoiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        if (!words.isNullOrBlank()) {
            input = words
            send(words)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceMode = true
        } else {
            // No microphone permission: use the system recogniser, which owns its own dialog.
            runCatching {
                systemVoiceLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    },
                )
            }
        }
    }

    val startVoice: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            !isInAppVoiceAvailable(context) -> runCatching {
                systemVoiceLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    },
                )
            }

            granted -> voiceMode = true
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!sheetState.isVisible) onDismiss() },
        sheetState = sheetState,
        // 返回键由下面自己的 BackHandler 处理：第一下收键盘，第二下才收面板。
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.testTag("assistant-sheet"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Surface(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 8.dp).width(36.dp).height(4.dp).background(
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(2.dp),
                ),
            ) {}
        },
    ) {
        val imeVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        BackHandler {
            if (imeVisible) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            } else {
                scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) { onDismiss() }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GroupHeader(
                agents = agents,
                liveStatus = liveTool,
                busy = busy,
                onManage = { showManagement = true },
                onNewSession = { confirmNewSession = true },
                onDismiss = onDismiss,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // 只给「最新的一条」挂稳定标签：断言要的是这次说的那句，不是历史里任意一句。
                // 正文还在逐字浮现时不挂，免得测试读到半句话。
                val lastReplyId = messages.lastOrNull { it is ChatItem.Agent }?.id
                val lastNoticeId = messages.lastOrNull { it is ChatItem.Notice }?.id
                val lastBriefId = messages.lastOrNull { it is ChatItem.Brief }?.id
                items(messages, key = { it.id }) { item ->
                    when (item) {
                        is ChatItem.User -> UserBubble(user = user, text = item.text)
                        is ChatItem.Typing -> AgentTypingRow(agent = agentOf(item.agentId, agents))
                        is ChatItem.Agent -> AgentBubble(
                            agent = agentOf(item.agentId, agents),
                            text = item.text.take(item.revealed),
                            typing = item.revealed < item.text.length,
                            feedback = item.feedback,
                            onQuote = { snippet ->
                                input = "关于「$snippet」："
                                inputFocus.requestFocus()
                            },
                            onFeedback = { liked ->
                                messages = messages.map {
                                    if (it.id == item.id && it is ChatItem.Agent) it.copy(feedback = liked) else it
                                }
                                onReplyFeedback(liked, item.text.take(40))
                            },
                            modifier = if (item.id == lastReplyId && item.revealed >= item.text.length) {
                                Modifier.testTag("group-reply")
                            } else {
                                Modifier
                            },
                        )
                        is ChatItem.ToolCall -> ToolCallLine(item)
                        is ChatItem.Adjustment -> AdjustmentLine(item)
                        is ChatItem.DraftRecord -> DraftRecordCard(item)
                        is ChatItem.Brief -> BriefCard(
                            item,
                            modifier = if (item.id == lastBriefId) Modifier.testTag("group-brief-card") else Modifier,
                        )
                        is ChatItem.Notice -> NoticeBubble(
                            item.reason,
                            item.agentName,
                            modifier = if (item.id == lastNoticeId) Modifier.testTag("group-notice") else Modifier,
                        )
                    }
                }
                if (session.pendingActions.isNotEmpty()) {
                    item(key = "pending-actions") {
                        PendingActionCard(
                            actions = session.pendingActions,
                            busy = busy,
                            onExecuteAll = { executeActions(session.pendingActions) },
                            onSkip = ::skipAction,
                            onCancelAll = ::cancelDraft,
                            onEdit = ::updateAction,
                            batchPreview = toolbox::batchPreview,
                            batchStyle = toolbox::batchStyle,
                            batchOccupied = toolbox::batchOccupied,
                            batchDayLabel = toolbox::batchDayLabel,
                            onStyleChange = { store.saveSchedulingStyle(it) },
                            occupiedEntries = occupiedEntries,
                        )
                    }
                }
                if (batchUndoToken != null) {
                    item(key = "batch-undo") {
                        BatchUndoRow(busy = busy, onUndo = ::undoBatch)
                    }
                }
                if (busy) {
                    item(key = "typing") { AgentTypingRow(agent = planner) }
                }
            }

            if (voiceMode) {
                VoiceInputBar(
                    onFinish = { text, cancelled ->
                        voiceMode = false
                        if (!cancelled && text.isNotBlank()) send(text)
                    },
                    onDismiss = { voiceMode = false },
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    agents.forEach { agent ->
                        Surface(
                            onClick = { input = if (input.isBlank()) "@${agent.name} " else "$input@${agent.name} " },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape,
                        ) {
                            Text(
                                "${agent.avatar} @${agent.name}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box {
                        IconButton(onClick = { plusMenu = true }, modifier = Modifier.testTag("group-plus")) {
                            Icon(DesignIcons.Plus, contentDescription = "更多输入方式", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PlusCapsule("语音输入", tag = "group-voice") {
                                    plusMenu = false
                                    startVoice()
                                }
                                PlusCapsule("灵感一句", tag = "group-seed") {
                                    plusMenu = false
                                    input = InspirationSeeds[seedIndex % InspirationSeeds.size]
                                    seedIndex += 1
                                    inputFocus.requestFocus()
                                }
                                PlusCapsule("技能清单", tag = "group-tools") {
                                    plusMenu = false
                                    showToolCatalog = true
                                }
                                PlusCapsule("今日简报", tag = "group-brief") {
                                    plusMenu = false
                                    send("今日简报")
                                }
                                PlusCapsule("助手日记", tag = "group-diary") {
                                    plusMenu = false
                                    showDiary = true
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group-input")
                            .focusRequester(inputFocus)
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = { Text(if (inputFocused) "发送消息…" else "点击输入，或点 + 说话…") },
                        minLines = 1,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.small,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { if (busy) stop() else send(input) },
                        enabled = busy || input.isNotBlank(),
                        modifier = Modifier.testTag("group-send"),
                    ) {
                        if (busy) {
                            Icon(Icons.Outlined.Close, contentDescription = "停止生成", tint = MaterialTheme.colorScheme.error)
                        } else {
                            Icon(PlannerIcons.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showManagement) {
        AgentManagementSheet(
            agents = agents,
            user = user,
            onSaveAgents = { updated ->
                agents = updated
                store.saveAgents(updated)
            },
            onSaveUser = { updated ->
                user = updated
                store.saveUser(updated)
            },
            onDismiss = { showManagement = false },
        )
    }

    if (showToolCatalog) {
        ToolCatalogSheet(
            specs = toolbox.specs(),
            onDismiss = { showToolCatalog = false },
        )
    }

    if (confirmNewSession) {
        AlertDialog(
            onDismissRequest = { confirmNewSession = false },
            title = { Text("开始新对话？") },
            text = { Text("未确认草案会取消") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewSession = false
                    startNewSession()
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewSession = false }) { Text("留在这里") }
            },
        )
    }

    if (showDiary) {
        AssistantDiarySheet(
            journal = journal,
            onDismiss = { showDiary = false },
        )
    }
}

@Composable
private fun GroupHeader(
    agents: List<AgentProfile>,
    liveStatus: String?,
    busy: Boolean,
    onManage: () -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            agents.take(4).forEach { agent ->
                Surface(
                    onClick = onManage,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(agent.avatar, fontSize = 16.sp) }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("规划小组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 只在真正干活时出现状态行；常驻一行「N 位成员/已连接云端」只是噪音。
            if (liveStatus != null || busy) {
                Text(
                    if (liveStatus != null) "正在处理：$liveStatus" else "正在思考…",
                    modifier = Modifier.testTag("group-status"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onNewSession, modifier = Modifier.testTag("group-new-session")) {
            Icon(Icons.Outlined.Add, contentDescription = "新对话")
        }
        TextButton(onClick = onManage) { Text("管理") }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("assistant-close")) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun UserBubble(user: UserProfile, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(12.dp, 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(8.dp))
        Avatar(user.avatar, size = 32)
    }
}

/** 文字输出：成员的正文气泡；卡片堆/折叠/长按菜单的质感与团队助手共用 ReplyBubbleBody。 */
@Composable
private fun AgentBubble(
    agent: AgentProfile,
    text: String,
    typing: Boolean,
    feedback: Boolean?,
    onQuote: (String) -> Unit,
    onFeedback: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Avatar(agent.avatar, size = 32)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                agent.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            ReplyBubbleBody(
                text = text,
                typing = typing,
                feedback = feedback,
                onQuote = onQuote,
                onFeedback = onFeedback,
            )
        }
    }
}

/**
 * 工具调用：一次真实执行的记录。刻意小一号并用等宽排版，让它读起来是"日志"而不是
 * 对话——参数和结果都摆在明面上，用户可以核对每个动作到底传了什么。
 */
@Composable
private fun ToolCallLine(item: ChatItem.ToolCall) {
    val tone = when (item.outcome) {
        ToolOutcome.DONE -> MaterialTheme.colorScheme.tertiaryContainer
        ToolOutcome.SKIPPED -> MaterialTheme.colorScheme.surfaceContainerHigh
        ToolOutcome.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val ink = when (item.outcome) {
        ToolOutcome.DONE -> MaterialTheme.colorScheme.onTertiaryContainer
        ToolOutcome.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolOutcome.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Surface(color = tone, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(8.dp, 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "⚙ ${item.label} · ${item.outcome.label()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                )
                if (item.argsSummary != "无参数") {
                    Text(
                        item.argsSummary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = ink.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    item.resultSummary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = ink.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 逻辑调整：已执行的写入。中等字号、琥珀色调，与工具日志和正文都不同——
 * 这是"应用状态确实变了"的证据行。
 */
/** 刚确认的写入还来得及整体反悔；一行轻量入口，不打断滚动。 */
@Composable
private fun BatchUndoRow(busy: Boolean, onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)) {
            Text(
                "刚才的写入已生效，反悔可以整体撤销",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CapsuleRow(
                capsules = listOf(
                    CapsuleSpec("undo", "撤销", CapsuleKind.VERB, enabled = !busy, tag = "group-batch-undo"),
                ),
                onCapsuleClick = { onUndo() },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun AdjustmentLine(item: ChatItem.Adjustment) {
    val tone = if (item.ok) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val ink = if (item.ok) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Surface(color = tone, shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(10.dp, 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.ok) "✓" else "✕",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ink,
                    )
                    Text(
                        item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink,
                    )
                }
            }
        }
    }
}

/**
 * 每日简报卡：今天哪些时段被占住、还有哪些整块空档，确定性整理、不调云端。
 * 同一天刷新就地编辑（右上角"已更新 HH:mm"），聊天只留最近一份；历史在助手日记里成链。
 * 与回执/提示卡同一套质感：缩进对齐成员头像列，纸感卡面 + 空档胶囊 + 脚注。
 */
@Composable
private fun BriefCard(item: ChatItem.Brief, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(start = 40.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "今日简报",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (item.updated) "已更新 ${item.time}" else item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    item.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.evidence.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.evidence.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (item.freeWindows.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.freeWindows.forEach { window ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    window,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                Text(
                    item.footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 已定稿的草案：留在对话里作为记录，默认收起；状态用颜色和字标明，不再提供任何操作。
 */
@Composable
private fun DraftRecordCard(item: ChatItem.DraftRecord) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val tone = when (item.status) {
        DraftStatus.COMMITTED -> MaterialTheme.colorScheme.secondaryContainer
        DraftStatus.CANCELLED, DraftStatus.REPLACED -> MaterialTheme.colorScheme.surfaceContainerHigh
        DraftStatus.EXPIRED -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val ink = when (item.status) {
        DraftStatus.COMMITTED -> MaterialTheme.colorScheme.onSecondaryContainer
        DraftStatus.CANCELLED, DraftStatus.REPLACED -> MaterialTheme.colorScheme.onSurfaceVariant
        DraftStatus.EXPIRED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Surface(
            onClick = { expanded = !expanded },
            color = tone,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f).testTag("draft-record"),
        ) {
            Column(Modifier.padding(12.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${item.title} · ${item.rows.size} 件",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.status.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ink,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp),
                        tint = ink,
                    )
                }
                Text(
                    item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.7f),
                )
                if (expanded) {
                    item.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                row.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = ink,
                            )
                            Text(
                                row.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = ink.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The confirmation gate. Queued writes render here and nowhere else: until 确认, the
 * planner's proposals are words; after it, they become [ChatItem.Adjustment] evidence.
 */
@Composable
private fun PendingActionCard(
    actions: List<AgentAction>,
    busy: Boolean,
    onExecuteAll: () -> Unit,
    onSkip: (AgentAction) -> Unit,
    onCancelAll: () -> Unit,
    onEdit: (AgentAction) -> Unit,
    batchPreview: (AgentAction) -> List<AgentBatchPreviewRow>,
    batchStyle: (AgentAction) -> SchedulingStyle,
    batchOccupied: (AgentAction) -> List<TimelineEntry>,
    batchDayLabel: (AgentAction) -> String?,
    onStyleChange: (SchedulingStyle) -> Unit,
    occupiedEntries: List<TimelineEntry>,
) {
    var editing by remember { mutableStateOf<AgentAction?>(null) }
    var editingBatch by remember { mutableStateOf<Pair<AgentAction, Int>?>(null) }
    var expanded by remember { mutableStateOf(true) }
    // 卡上列的是「件」，一个批量工具里就有四件：按工具数说「1 项」，标题和按钮读起来都像数错了。
    val totalItems = remember(actions) {
        actions.sumOf { batchPreview(it).size.takeIf { n -> n > 0 } ?: 1 }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "待确认 · $totalItems 件事",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (expanded) {
            actions.forEach { action ->
                val rows = remember(action) { batchPreview(action) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (rows.isNotEmpty()) {
                        Column(Modifier.padding(10.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    action.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                // 排在别的日子时必须写在卡上：只有时段没有日期，确认的人以为排的是今天。
                                batchDayLabel(action)?.let { day ->
                                    Text(
                                        day,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { onSkip(action) }, enabled = !busy) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "跳过",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // 行事风格一次换掉整套方案：点一下，toolbox 按新的间隔把每一件重排，仍然只确认一次。
                            val selectedStyle = batchStyle(action)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SchedulingStyle.entries.forEach { style ->
                                    val selected = style == selectedStyle
                                    DesignClickableSurface(
                                        onClick = {
                                            if (selected) return@DesignClickableSurface
                                            onStyleChange(style)
                                            onEdit(action.copy(argumentsJson = argsWithStyle(action.argumentsJson, style)))
                                        },
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text(
                                            style.label,
                                            modifier = Modifier.padding(11.dp, 5.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                            rows.forEach { row ->
                                DesignClickableSurface(
                                    onClick = { editingBatch = action to row.index },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    enabled = !busy,
                                ) {
                                    Row(Modifier.padding(10.dp, 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(row.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text(
                                                buildString {
                                                    append("${row.durationMinutes} 分钟")
                                                    row.nestedUnderTitle?.let { append(" · 挂进「$it」") }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            "${chatFormatMinute(row.startMinute)}–${chatFormatMinute(row.endMinute)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.padding(10.dp, 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    action.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isEditableAction(action)) {
                                TextButton(onClick = { editing = action }) { Text("改") }
                            }
                            IconButton(onClick = { onSkip(action) }, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "跳过",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancelAll, enabled = !busy) { Text("取消") }
                Button(
                    onClick = onExecuteAll,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("agent-confirm-actions"),
                ) {
                    Text("确认 · $totalItems 件")
                }
            }
            }
        }
    }

    editing?.let { target ->
        ActionEditDialog(
            action = target,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                onEdit(updated)
            },
            onSkip = {
                editing = null
                onSkip(target)
            },
        )
    }

    editingBatch?.let { (target, index) ->
        val previewRows = batchPreview(target)
        previewRows.firstOrNull { it.index == index }?.let { row ->
            BatchItemTimeDialog(
                action = target,
                row = row,
                rows = previewRows,
                occupiedEntries = batchOccupied(target).ifEmpty { occupiedEntries },
                onDismiss = { editingBatch = null },
                onSave = { argumentsJson ->
                    editingBatch = null
                    onEdit(target.copy(argumentsJson = argumentsJson))
                },
            )
        }
    }
}

/**
 * 批量卡上「逐件挑时间」的那一层。
 *
 * 只写回用户点名的那一刻（items[i].start），其余落点仍由 [ChatDayPlanner] 就当天实况重排——
 * 每件各存一个死时间会让「一次确认」变成 N 次局部决定，而且邻居一挪就早已对不上。
 * 校验走 chosenTimeRejected，与写入层同一口径：挑出来的时间必须一次就写得进去，
 * 否则「保存」按不下去——确认之后再冒第二次「确认调整」，这张卡省掉的轮次又回来了。
 */
@Composable
private fun BatchItemTimeDialog(
    action: AgentAction,
    row: AgentBatchPreviewRow,
    rows: List<AgentBatchPreviewRow>,
    occupiedEntries: List<TimelineEntry>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val wheelKey = "${action.id}:${row.index}"
    var letAssistantPick by remember(wheelKey) { mutableStateOf(row.userPickedStartMinute == null) }
    val wheel = rememberTimeWheelHold(row.userPickedStartMinute ?: row.startMinute, key = wheelKey)
    val durationWheel = rememberDurationWheelHold(row.durationMinutes, key = wheelKey)
    val chunkMinutes = durationWheel.settledMinutes()
    val startMinute = if (letAssistantPick) row.startMinute else wheel.settledMinuteOfDay()
    val endMinute = (startMinute + chunkMinutes).coerceAtMost(1_440)
    val parentTitle = ChatDayPlanner.nestedHostTitleAt(
        startMinute = startMinute,
        endMinute = endMinute,
        occupied = occupiedEntries,
        excludeTaskId = row.taskId,
    )
    val rejection = ChatDayPlanner.chosenTimeRejected(
        startMinute = startMinute,
        endMinute = endMinute,
        occupied = occupiedEntries,
        siblings = rows.filter { it.index != row.index && it.userPickedStartMinute != null }
            .map { it.startMinute to it.endMinute },
        excludeTaskId = row.taskId,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${row.name}」什么时候做") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    parentTitle?.let { "挂进「$it」里同时做" } ?: "独立占一段时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DurationWheelField(durationWheel, Modifier.fillMaxWidth(), tag = "duration")
                if (!letAssistantPick) {
                    TimeWheelField(wheel, Modifier.fillMaxWidth(), tag = "start")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = letAssistantPick, onCheckedChange = { letAssistantPick = it })
                    Text("让助手自己找空档", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (letAssistantPick) {
                        "助手放 ${chatFormatMinute(row.startMinute)}–${chatFormatMinute((row.startMinute + chunkMinutes).coerceAtMost(1_440))}；" +
                            "旁边时间跟着重排"
                    } else {
                        rejection ?: "确认后写入"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!letAssistantPick && rejection != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = letAssistantPick || rejection == null,
                onClick = {
                    onSave(
                        argsWithStart(
                            argumentsJson = action.argumentsJson,
                            index = row.index,
                            startOrNull = if (letAssistantPick) null else chatFormatMinute(startMinute),
                            durationMinutes = chunkMinutes,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把用户挑好的开始时间写回批量参数里的第 index 件（start 传 null = 交回助手找空档）。
 *
 * 只改这一件的时间，其余件保持不动：落点全部由规划器按当天实况重算，所以一件挪了、
 * 旁边的空档会跟着重排——这正是"一次确认"能成立的前提。index 对齐 batchItems 的口径，
 * 非对象元素会被跳过，不能按数组下标硬数。
 */
internal fun argsWithStart(argumentsJson: String, index: Int, startOrNull: String?, durationMinutes: Int? = null): String {
    val args = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
    val raw = args.opt("items")
    val array: JSONArray = when (raw) {
        is JSONArray -> raw
        is JSONObject -> JSONArray().put(raw)
        is String -> runCatching { JSONArray(raw.trim()) }.getOrNull() ?: return argumentsJson
        else -> return argumentsJson
    }
    var seen = -1
    for (position in 0 until array.length()) {
        val item = array.optJSONObject(position) ?: continue
        seen += 1
        if (seen != index) continue
        if (startOrNull == null) item.remove("start") else item.put("start", startOrNull)
        durationMinutes?.let { item.put("durationMinutes", it) }
        return args.put("items", if (raw is JSONObject) item else array).toString()
    }
    return argumentsJson
}

/** 换整套方案：只改一个 style，toolbox 会重算所有落点。 */
internal fun argsWithStyle(argumentsJson: String, style: SchedulingStyle): String {
    val args = runCatching { JSONObject(argumentsJson) }.getOrElse { return argumentsJson }
    return args.put("style", style.key).toString()
}

private fun isEditableAction(action: AgentAction): Boolean =
    action.toolName in setOf("create_task", "place_task", "move_placement")

/**
 * 「你们都能做什么」「大家都介绍一下」——问的是整个团队，不是某一个人。
 * 需要团队称谓 + 能力/介绍类问法同时出现，避免"帮我做这件事"被误判成"介绍会"。
 */
internal fun asksForWholeTeam(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty()) return false
    val roster = listOf("你们", "大家", "各位", "每位", "每一位", "每个人", "每人", "全员", "团队", "成员", "几个人", "哪些人")
    val ask = listOf(
        "能做什么", "会做什么", "能干什么", "会干什么", "都能做", "都会做", "能帮", "有什么用", "什么用",
        "介绍", "是谁", "都有谁", "各自", "能力", "分工", "职责", "负责什么",
    )
    return roster.any { text.contains(it) } && ask.any { text.contains(it) }
}

/** 对可编辑操作改参数；保存后的参数会随确认一起执行。 */
@Composable
private fun ActionEditDialog(
    action: AgentAction,
    onDismiss: () -> Unit,
    onSave: (AgentAction) -> Unit,
    onSkip: () -> Unit,
) {
    val args = remember(action.id) { runCatching { JSONObject(action.argumentsJson) }.getOrDefault(JSONObject()) }
    var name by remember { mutableStateOf(args.optString("name").ifBlank { args.optString("taskId") }) }
    val initialStart = parseMinuteOfDay(args.optString("start")) ?: args.optIntOrNull("startMinute")
    var startEnabled by remember { mutableStateOf(initialStart != null) }
    // 时长只有轮盘一种写法：档位全在 5–720 内，所以「时长不合法」「结束要晚于开始」
    // 这两类报错从源头上不存在了，也不需要再多一个结束时间轮盘。
    val initialDuration = args.optIntOrNull("durationMinutes")
        ?: args.optIntOrNull("endMinute")?.let { end -> end - args.optInt("startMinute", 8 * 60) }
        ?: 60
    // 轮盘值在保存时直接从滚动状态读取；换一项操作时重新开始，不沿用上一项的时间。
    val startWheel = rememberTimeWheelHold(initialStart ?: 8 * 60, key = action.id)
    val durationWheel = rememberDurationWheelHold(initialDuration, key = action.id)
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改这一项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action.toolName == "create_task") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    DurationWheelField(durationWheel, Modifier.fillMaxWidth(), tag = "duration")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = startEnabled, onCheckedChange = { startEnabled = it })
                        Text("指定开始时间", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (startEnabled) {
                        TimeWheelField(startWheel, Modifier.fillMaxWidth(), tag = "start")
                    }
                } else {
                    TimeWheelField(startWheel, Modifier.fillMaxWidth(), tag = "start")
                    DurationWheelField(durationWheel, Modifier.fillMaxWidth(), tag = "duration")
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (action.toolName == "create_task" && name.isBlank()) {
                    error = "名称不能为空"
                } else {
                    val minutes = durationWheel.settledMinutes()
                    val updated = when (action.toolName) {
                        "create_task" -> {
                            if (startEnabled) {
                                args.put("start", chatFormatMinute(startWheel.settledMinuteOfDay()))
                            } else args.remove("start")
                            args.put("name", name.trim()).put("durationMinutes", minutes)
                        }
                        else -> {
                            val start = startWheel.settledMinuteOfDay()
                            args.put("startMinute", start).put("endMinute", (start + minutes).coerceAtMost(1_440))
                        }
                    }
                    onSave(action.copy(argumentsJson = updated.toString()))
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("跳过", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}

private fun parseMinuteOfDay(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val parts = text.split(':', '：')
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** 失败不弹大卡：消息旁一枚深色「!」徽章 + 两行小字，和 Today 的失败语言一致。 */
@Composable
private fun NoticeBubble(reason: AiAdvisorFailureReason, agentName: String?, modifier: Modifier = Modifier) {
    val who = agentName?.let { "$it " }.orEmpty()
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.error, shape = CircleShape, modifier = Modifier.size(20.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("!", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Column {
            Text(
                "${who}这一步没跑通",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "${reason.label()} · ${reason.hint()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentTypingRow(agent: AgentProfile) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(agent.avatar, size = 32)
        Spacer(Modifier.width(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Row(
                modifier = Modifier.padding(10.dp, 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Text("${agent.name} 正在处理…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Avatar(emoji: String, size: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = (size * 0.55f).sp) }
    }
}

private fun agentOf(id: String, agents: List<AgentProfile>): AgentProfile =
    agents.firstOrNull { it.id == id } ?: agents.first()

private fun ToolOutcome.label(): String = when (this) {
    ToolOutcome.DONE -> "完成"
    ToolOutcome.SKIPPED -> "跳过"
    ToolOutcome.FAILED -> "失败"
}

/** What actually went wrong, then what the user can do about it. */
private fun AiAdvisorFailureReason.label(): String = when (this) {
    AiAdvisorFailureReason.SOURCE_CHANGED -> "任务或时间刚发生变化"
    AiAdvisorFailureReason.CONTEXT_UNAVAILABLE -> "本地数据还没就绪"
    AiAdvisorFailureReason.CONFIGURATION_CHANGED -> "AI 配置刚被修改"
    AiAdvisorFailureReason.DISABLED -> "云端未启用"
    AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED -> "没有可用的服务商配置"
    AiAdvisorFailureReason.TRANSPORT_FAILURE -> "连不上服务商"
    AiAdvisorFailureReason.TIMEOUT -> "请求超时"
    AiAdvisorFailureReason.AUTHENTICATION_FAILURE -> "密钥被拒绝"
    AiAdvisorFailureReason.RATE_LIMITED -> "被限流"
    AiAdvisorFailureReason.REMOTE_FAILURE -> "服务商返回错误"
    AiAdvisorFailureReason.INVALID_RESPONSE -> "返回内容无法解析"
}

private fun AiAdvisorFailureReason.hint(): String = when (this) {
    AiAdvisorFailureReason.SOURCE_CHANGED -> "把需求重新发送一次"
    AiAdvisorFailureReason.CONTEXT_UNAVAILABLE -> "稍等片刻再发送"
    AiAdvisorFailureReason.CONFIGURATION_CHANGED -> "回到本页重试，或检查设置"
    AiAdvisorFailureReason.DISABLED -> "到设置里打开 AI 并同意授权"
    AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED -> "到设置里填 baseUrl、模型和 API Key"
    AiAdvisorFailureReason.TRANSPORT_FAILURE -> "检查网络，以及 baseUrl 是否填错"
    AiAdvisorFailureReason.TIMEOUT -> "稍后重试，或换一个更快的模型"
    AiAdvisorFailureReason.AUTHENTICATION_FAILURE -> "检查 API Key 是否属于这个地址"
    AiAdvisorFailureReason.RATE_LIMITED -> "等一会儿再发"
    AiAdvisorFailureReason.REMOTE_FAILURE -> "换个模型或稍后重试"
    AiAdvisorFailureReason.INVALID_RESPONSE -> "换个模型，或把需求说得更具体"
}

/** + 菜单里的竖排胶囊：只放真实能力（语音/灵感），不放没有的上传拍照。 */
@Composable
internal fun PlusCapsule(text: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 灵感一句：第一人称的对话种子，点一下填进输入框；藏在 + 菜单里，不占常驻空间。 */
private val InspirationSeeds = listOf(
    "我想把明天下午空出来复习，帮我看看怎么排",
    "今天剩下的时间还够做什么？",
    "帮我把没排进去的任务找个时间",
    "这周哪几天晚上有空？我想安排运动",
)

/**
 * The conversation and the protocol session, kept outside the composable so dismissing the
 * sheet does not throw them away. In-memory only: nothing here is written to Room.
 * [createdTasks]/[scheduledTaskIds] mirror AgentAppToolbox's ledger so a reopened sheet with
 * a fresh toolbox still honors what this conversation created and scheduled.
 */
internal object GroupChatSession {
    var items: List<ChatItem> = emptyList()
    var transcript: List<AgentChatMessage> = emptyList()
    var pending: List<AgentAction> = emptyList()
    var nextId: Int = 0
    var createdTasks: List<AgentAppToolbox.CreatedTask> = emptyList()
    var scheduledTaskIds: Set<String> = emptySet()
    /** 没发出去的输入；关闭面板不该把它当成丢弃。 */
    var draft: String = ""
}

internal sealed interface ChatItem {
    val id: String

    data class User(override val id: String, val text: String) : ChatItem
    data class Agent(
        override val id: String,
        val agentId: String,
        val text: String,
        /** How many characters are on screen; the typewriter advances this. */
        val revealed: Int = text.length,
        /** 长按菜单里的喜欢/不喜欢；null 表示还没表态。 */
        val feedback: Boolean? = null,
    ) : ChatItem
    data class Typing(override val id: String, val agentId: String) : ChatItem
    data class ToolCall(
        override val id: String,
        val label: String,
        val argsSummary: String,
        val resultSummary: String,
        val outcome: ToolOutcome,
    ) : ChatItem
    data class Adjustment(
        override val id: String,
        val label: String,
        val summary: String,
        val ok: Boolean,
    ) : ChatItem
    /** 每日简报：确定性整理的今日实况卡。同一天只留最近一份，刷新就地编辑（借鉴 #8/#6）。 */
    data class Brief(        override val id: String,
        val date: LocalDate,
        val headline: String,
        val evidence: List<String>,
        val freeWindows: List<String>,
        val footer: String,
        val time: String,
        val updated: Boolean,
    ) : ChatItem
    data class Notice(
        override val id: String,
        val reason: AiAdvisorFailureReason,
        val agentName: String?,
    ) : ChatItem

    /** 已定稿的草案：卡片留在对话里可收纳、可回看，但不再接受任何操作。 */
    data class DraftRecord(
        override val id: String,
        val title: String,
        val rows: List<DraftRecordRow>,
        val status: DraftStatus,
        val time: String,
    ) : ChatItem
}
