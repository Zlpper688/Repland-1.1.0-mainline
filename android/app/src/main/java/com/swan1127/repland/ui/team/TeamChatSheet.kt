package com.swan1127.repland.ui.team

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.runtime.TeamDecisionBrief
import com.swan1127.repland.domain.team.runtime.TeamOutcome
import com.swan1127.repland.ui.agent.PlusCapsule
import com.swan1127.repland.ui.agent.ReplyBubbleBody
import com.swan1127.repland.ui.agent.ToolCatalogSheet
import com.swan1127.repland.ui.agent.VoiceInputBar
import com.swan1127.repland.ui.agent.isInAppVoiceAvailable
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.DesignTextField as OutlinedTextField
import com.swan1127.repland.ui.components.PlannerIcons
import com.swan1127.repland.ui.theme.replandAccent
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 团队助手对话面板：与个人模式群聊同一套视觉语言，但这里只有一个助手——
 * 以当前身份（队长/队员）说话和写入的「队伍助手」，背后是整面 TeamToolbox 工具墙。
 * 三类行依旧刻意区分：正文气泡、工具调用日志（等宽小字）、已执行的逻辑调整（琥珀）。
 * 写入永远排队等确认：待确认卡逐件可跳过，一次确认执行全部；排期草案在卡上确认生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamChatSheet(
    vm: TeamViewModel,
    /** 长按回复里的喜欢/不喜欢：隐式记忆证据，由调用方落库。 */
    onReplyFeedback: (liked: Boolean, snippet: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val state = ui.snapshot?.state
    val actor = ui.actorId?.let { id -> state?.members?.firstOrNull { it.memberId == id } }
    val outcome = ui.outcome

    var items by remember {
        mutableStateOf(TeamChatSession.items.ifEmpty { listOf(TeamChatItem.Agent("a-welcome", welcomeLine(state?.teamName))) })
    }
    var input by remember { mutableStateOf(TeamChatSession.draft) }
    var seq by remember { mutableIntStateOf(TeamChatSession.nextId) }
    var voiceMode by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var showToolCatalog by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var seedIndex by remember { mutableIntStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    /** 逐字浮现一条回复：传输层一次给整段，打字机只是动画。 */
    suspend fun reveal(id: String, text: String) {
        items = items + TeamChatItem.Agent(id, text, revealed = 0)
        val total = text.length
        val step = maxOf(1, total / 48)
        var shown = 0
        while (shown < total) {
            delay(14)
            shown = minOf(total, shown + step)
            items = items.map { item ->
                if (item is TeamChatItem.Agent && item.id == id) item.copy(revealed = shown) else item
            }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        if (ui.busy != null || actor == null || state?.archived == true) return
        input = ""
        keyboard?.hide()
        items = items + TeamChatItem.User("u-${seq++}", text)
        vm.say(text)
    }

    // 换队或换身份：对话连同旧身份的动作一起清空（VM 侧同样重置会话）。
    LaunchedEffect(state?.teamId, ui.actorId) {
        if (TeamChatSession.teamId != state?.teamId || TeamChatSession.actorId != ui.actorId) {
            TeamChatSession.teamId = state?.teamId
            TeamChatSession.actorId = ui.actorId
            TeamChatSession.items = emptyList()
            TeamChatSession.draft = ""
            TeamChatSession.lastReply = null
            TeamChatSession.lastError = null
            TeamChatSession.nextId = 0
            seq = 0
            items = listOf(TeamChatItem.Agent("a-welcome", welcomeLine(state?.teamName)))
            input = ""
        }
    }

    // 关掉面板不丢对话：记录、草稿与去重标记都挂在会话对象上，重开接着看。
    LaunchedEffect(items) { TeamChatSession.items = items }
    LaunchedEffect(seq) { TeamChatSession.nextId = seq }
    LaunchedEffect(input) { TeamChatSession.draft = input }

    // 新一轮开始时清掉回复去重标记：同样的回复连说两轮也要都显示。
    LaunchedEffect(ui.busy) {
        if (ui.busy != null) {
            TeamChatSession.lastReply = null
            TeamChatSession.lastError = null
        }
    }

    // VM 出了新内容 → 落成聊天行；工具轨迹按原文去重，确认执行后整段旧轨迹不会重复入账。
    LaunchedEffect(ui.assistantReply, ui.assistantTrace, ui.assistantError) {
        ui.assistantReply?.takeIf { it.isNotBlank() && it != TeamChatSession.lastReply }?.let { reply ->
            TeamChatSession.lastReply = reply
            reveal("a-${seq++}", reply)
        }
        ui.assistantTrace.forEach { line ->
            if (line.isBlank()) return@forEach
            val alreadyShown = items.any { item ->
                (item as? TeamChatItem.ToolCall)?.raw == line || (item as? TeamChatItem.Adjustment)?.raw == line
            }
            if (!alreadyShown) items = items + traceItem("t-${seq++}", line)
        }
        ui.assistantError?.takeIf { it.isNotBlank() && it != TeamChatSession.lastError }?.let { error ->
            TeamChatSession.lastError = error
            items = items + TeamChatItem.Notice("n-${seq++}", error)
        }
    }

    val liveCount = listOf(
        outcome?.draft != null,
        ui.pendingActions.isNotEmpty(),
        ui.busy != null,
    ).count { it }
    LaunchedEffect(items.size, liveCount) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size + liveCount - 1)
    }

    val context = LocalContext.current
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
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        modifier = Modifier.testTag("team-chat-sheet"),
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
        val focusManager = LocalFocusManager.current
        BackHandler {
            if (voiceMode) {
                voiceMode = false
            } else {
                scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) onDismiss()
                }
                focusManager.clearFocus(force = true)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TeamChatHeader(
                actorName = actor?.name,
                isCaptain = actor?.isCaptain == true,
                aiAvailable = ui.aiAvailable,
                busy = ui.busy,
                onDismiss = onDismiss,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                val lastReplyId = items.lastOrNull { it is TeamChatItem.Agent && it.id != "a-welcome" }?.id
                items(items, key = { it.id }) { item ->
                    when (item) {
                        is TeamChatItem.User -> UserBubble(
                            name = actor?.name ?: "?",
                            isCaptain = actor?.isCaptain == true,
                            text = item.text,
                        )
                        is TeamChatItem.Agent -> AssistantBubble(
                            text = item.text.take(item.revealed),
                            typing = item.revealed < item.text.length,
                            feedback = item.feedback,
                            onQuote = { snippet ->
                                input = "关于「$snippet」："
                                inputFocus.requestFocus()
                            },
                            onFeedback = { liked ->
                                items = items.map {
                                    if (it.id == item.id && it is TeamChatItem.Agent) it.copy(feedback = liked) else it
                                }
                                onReplyFeedback(liked, item.text.take(40))
                            },
                            modifier = if (item.id == lastReplyId && item.revealed >= item.text.length) {
                                Modifier.testTag("team-chat-reply")
                            } else {
                                Modifier
                            },
                        )
                        is TeamChatItem.ToolCall -> ToolCallLine(item)
                        is TeamChatItem.Adjustment -> AdjustmentLine(item)
                        is TeamChatItem.Notice -> NoticeBubble(item.text)
                    }
                }
                outcome?.takeIf { it.draft != null }?.let { current ->
                    item(key = "outcome") {
                        OutcomeDraftCard(
                            outcome = current,
                            state = state,
                            canConfirm = actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true && state?.archived != true,
                            onConfirm = { current.draft?.let(vm::commitDraft) },
                            onDiscard = vm::dismissOutcome,
                        )
                    }
                }
                if (ui.pendingActions.isNotEmpty()) {
                    item(key = "pending") {
                        TeamPendingCard(
                            actions = ui.pendingActions,
                            busy = ui.busy,
                            onConfirm = vm::confirmAssistantActions,
                            onSkip = { action -> vm.discardAssistantAction(action.id) },
                        )
                    }
                }
                if (ui.busy != null) {
                    item(key = "busy") { BusyRow(ui.busy ?: TeamBusy.TALKING) }
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
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("今天有事", "做完了", "来不及了", "不同意").forEach { phrase ->
                        PhraseChip(phrase) { input = phrase }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box {
                        IconButton(onClick = { plusMenu = true }, modifier = Modifier.testTag("team-chat-plus")) {
                            Icon(DesignIcons.Plus, contentDescription = "更多输入方式", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PlusCapsule("语音输入", tag = "team-chat-voice") {
                                    plusMenu = false
                                    startVoice()
                                }
                                PlusCapsule("灵感一句", tag = "team-chat-seed") {
                                    plusMenu = false
                                    input = TeamInspirationSeeds[seedIndex % TeamInspirationSeeds.size]
                                    seedIndex += 1
                                    inputFocus.requestFocus()
                                }
                                PlusCapsule("技能清单", tag = "team-chat-tools") {
                                    plusMenu = false
                                    showToolCatalog = true
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("team-chat-input")
                            .focusRequester(inputFocus)
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = {
                            Text(
                                when {
                                    actor == null -> "先在页面顶部选择身份…"
                                    inputFocused -> "发送消息…"
                                    else -> "点击输入，或点 + 说话…"
                                },
                            )
                        },
                        minLines = 1,
                        maxLines = 4,
                        enabled = actor != null && state?.archived != true,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { send(input) },
                        enabled = input.isNotBlank() && ui.busy == null && actor != null && state?.archived != true,
                        modifier = Modifier.testTag("team-chat-send"),
                    ) {
                        Icon(PlannerIcons.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showToolCatalog) {
        ToolCatalogSheet(
            specs = vm.currentToolSpecs(),
            onDismiss = { showToolCatalog = false },
        )
    }
}

private fun welcomeLine(teamName: String?): String =
    (teamName?.takeIf { it.isNotBlank() }?.let { "我是「$it」的队伍助手" } ?: "我是这支队伍的助手") +
        "，只有一个我，但工具不少：查比赛、查截止、报进度、查口径、排周计划都能做。" +
        "说一句话，我调工具来完成；写入都会先等你确认。"

/** 灵感一句：队伍场景的第一人称对话种子，都能被现有团队工具接住。 */
private val TeamInspirationSeeds = listOf(
    "帮我看看这周谁的任务最重",
    "把没排进本周的任务列出来",
    "这周的排期有哪些冲突？",
    "帮我汇总本周每个人的进度",
)

/** 工具轨迹原文 → 聊天行：已执行/未执行的是逻辑调整，其余是工具调用日志。 */
private fun traceItem(id: String, line: String): TeamChatItem {
    val executed = Regex("^(已执行|未执行) · (.+)$").find(line.trim())
    if (executed != null) {
        val rest = executed.groupValues[2]
        val at = rest.indexOf('：')
        return TeamChatItem.Adjustment(
            id = id,
            label = if (at >= 0) rest.take(at) else rest,
            summary = if (at >= 0) rest.substring(at + 1) else "",
            ok = executed.groupValues[1] == "已执行",
            raw = line,
        )
    }
    val at = line.indexOf('：')
    return if (at > 0) {
        TeamChatItem.ToolCall(id = id, label = line.take(at), result = line.substring(at + 1), raw = line)
    } else {
        TeamChatItem.ToolCall(id = id, label = line, result = "", raw = line)
    }
}

@Composable
private fun TeamChatHeader(
    actorName: String?,
    isCaptain: Boolean,
    aiAvailable: Boolean,
    busy: TeamBusy?,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(size = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("队伍助手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (busy != null) {
                    busyLabel(busy)
                } else {
                    actorName?.let { "$it${if (isCaptain) " · 队长" else ""}" } ?: "未选身份"
                },
                modifier = Modifier.testTag("team-chat-status"),
                style = MaterialTheme.typography.labelSmall,
                color = if (busy != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("team-chat-close")) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun UserBubble(name: String, isCaptain: Boolean, text: String) {
    val accent = replandAccent(if (isCaptain) 1 else 0)
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
        Surface(color = accent.surface, shape = CircleShape, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent.ink,
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    typing: Boolean,
    feedback: Boolean?,
    onQuote: (String) -> Unit,
    onFeedback: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        AssistantAvatar(size = 32)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "队伍助手",
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

@Composable
private fun AssistantAvatar(size: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                DesignIcons.Assistant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size((size * 0.52f).dp),
            )
        }
    }
}

/** 工具调用日志：等宽小字摆在明面上，读起来是"日志"而不是对话。 */
@Composable
private fun ToolCallLine(item: TeamChatItem.ToolCall) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(8.dp, 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "⚙ ${item.label} · 完成",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (item.result.isNotBlank()) {
                    Text(
                        item.result,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 逻辑调整：确认执行后的写入回执，琥珀色调，是"应用状态确实变了"的证据行。 */
@Composable
private fun AdjustmentLine(item: TeamChatItem.Adjustment) {
    val tone = if (item.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val ink = if (item.ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
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
                    Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ink)
                    if (item.summary.isNotBlank()) {
                        Text(item.summary, style = MaterialTheme.typography.bodySmall, color = ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.error, shape = CircleShape, modifier = Modifier.size(20.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("!", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError)
            }
        }
        Column {
            Text(
                "这一步没跑通",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BusyRow(busy: TeamBusy) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(size = 32)
        Spacer(Modifier.width(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Row(
                modifier = Modifier.padding(10.dp, 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(busyLabel(busy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun busyLabel(busy: TeamBusy): String = when (busy) {
    TeamBusy.TALKING -> "正在读取现状、整理下一步…"
    TeamBusy.CONFIRMING -> "正在执行确认的写入…"
    TeamBusy.SCHEDULING -> "正在按个人时间重排…"
    TeamBusy.REPORTING -> "正在汇总本周周报…"
}

/** 确认门：待确认写入排队在这里，逐件可跳过，一次确认执行全部。 */
@Composable
private fun TeamPendingCard(
    actions: List<AgentAction>,
    busy: TeamBusy?,
    onConfirm: () -> Unit,
    onSkip: (AgentAction) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "待确认 · ${actions.size} 件事",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "确认前不写入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            actions.forEach { action ->
                val highRisk = TeamDecisionBrief.riskOf(action) == TeamDecisionBrief.Risk.HIGH
                val consequence = TeamDecisionBrief.consequenceOf(action)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(action.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                if (highRisk) {
                                    Badge("高风险", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            if (action.summary.isNotBlank()) {
                                Text(
                                    action.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (consequence != null) {
                                Text(
                                    consequence,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (highRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onSkip(action) }, enabled = busy == null) {
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
            Button(
                onClick = onConfirm,
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().testTag("team-chat-confirm"),
            ) {
                Text(if (busy == TeamBusy.CONFIRMING) "执行中…" else "确认执行全部 ${actions.size} 件")
            }
        }
    }
}

/** 会话产出的排期草案卡：确认生效才落库，放弃不执行。把做决定需要的信息（改什么/影响谁/风险）前置强调。 */
@Composable
private fun OutcomeDraftCard(
    outcome: TeamOutcome,
    state: TeamCollabState?,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    val draft = outcome.draft ?: return
    val brief = state?.let { TeamDecisionBrief.of(draft, it) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("team-chat-outcome"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                outcome.summaryLine,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            outcome.structuredRequest?.rawText?.takeIf { it.isNotBlank() }?.let { raw ->
                Text(
                    "原话：$raw",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (brief != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge("共 ${TeamDecisionBrief.hoursText(brief.totalHours)}",
                        MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (brief.conflictCount > 0) {
                        Badge("${brief.conflictCount} 段冲突", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (brief.reassignCount > 0) {
                        Badge("${brief.reassignCount} 项改分工", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                brief.memberLoads.forEachIndexed { index, load ->
                    // 负载排行渐隐：头部最醒目，尾部收进背景；低置信度行保持全亮提醒核对。
                    val fade = if (load.lowConfidence || brief.memberLoads.size < 3) 1f
                    else (1f - 0.18f * index).coerceAtLeast(0.5f)
                    Text(
                        "· ${load.name} +${TeamDecisionBrief.hoursText(load.addedHours)}" +
                            if (load.lowConfidence) "（课表未导入，请重点核对）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = (if (load.lowConfidence) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            .copy(alpha = fade),
                    )
                }
                brief.lines.take(8).forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "· ${line.taskTitle}（${TeamDecisionBrief.hoursText(line.hours)}）" +
                                "${line.date.format(NUM_MONTH)} ${minutesText(line.startMinute)}–${minutesText(line.endMinute)} → ${line.memberName}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        val flags = buildList {
                            if (line.conflict) add("与已有安排冲突")
                            if (line.lowConfidence) add("低置信度")
                            line.assigneeChange?.let { add("分工：$it") }
                        }
                        if (flags.isNotEmpty()) {
                            Text(
                                flags.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
                if (brief.lines.size > 8) {
                    Text(
                        "· 其余 ${brief.lines.size - 8} 条安排略",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (brief.unassignedTitles.isNotEmpty()) {
                    Text(
                        "未排进本期：${brief.unassignedTitles.joinToString("、")}（进入待安排）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                draft.placements.take(6).forEach { placement ->
                    Text(
                        "· ${placement.date.format(NUM_MONTH)} ${minutesText(placement.startMinute)}–" +
                            "${minutesText(placement.endMinute)} ${state?.let { memberName(it, placement.memberId) }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (draft.unassigned.isNotEmpty()) {
                    Text(
                        "未排进本期 ${draft.unassigned.size} 项（进入待安排）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            draft.notes.forEach { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (canConfirm) {
                CapsuleRow(
                    capsules = listOf(
                        CapsuleSpec("confirm", "确认生效", CapsuleKind.VERB, tag = "team-confirm-draft"),
                        CapsuleSpec("discard", "放弃", CapsuleKind.VERB),
                    ),
                    onCapsuleClick = { spec -> if (spec.key == "confirm") onConfirm() else onDiscard() },
                )
            } else if (draft.placements.isNotEmpty()) {
                Text(
                    "草案等待队长或有权限成员确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhraseChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val NUM_MONTH = DateTimeFormatter.ofPattern("M/d")

/** 对话与草稿，挂在组合之外：关掉面板再打开还在。内存态，不落盘。 */
internal object TeamChatSession {
    var items: List<TeamChatItem> = emptyList()
    var nextId: Int = 0
    var draft: String = ""
    var teamId: String? = null
    var actorId: String? = null
    var lastReply: String? = null
    var lastError: String? = null
}

internal sealed interface TeamChatItem {
    val id: String

    data class User(override val id: String, val text: String) : TeamChatItem
    data class Agent(
        override val id: String,
        val text: String,
        /** 屏幕上已浮现的字符数；打字机动画推进它。 */
        val revealed: Int = text.length,
        val feedback: Boolean? = null,
    ) : TeamChatItem
    data class ToolCall(
        override val id: String,
        val label: String,
        val result: String,
        val raw: String,
    ) : TeamChatItem
    data class Adjustment(
        override val id: String,
        val label: String,
        val summary: String,
        val ok: Boolean,
        val raw: String,
    ) : TeamChatItem
    data class Notice(override val id: String, val text: String) : TeamChatItem
}
