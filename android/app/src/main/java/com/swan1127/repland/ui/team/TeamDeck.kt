package com.swan1127.repland.ui.team

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.swan1127.repland.data.team.TeamSummary
import com.swan1127.repland.domain.team.CompetitionStages
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.inviteCode
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.rememberLandscapeSurface
import com.swan1127.repland.ui.theme.LocalReducedMotion
import com.swan1127.repland.ui.theme.replandAccent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DECK_DAY = DateTimeFormatter.ofPattern("M月d日")
private val DECK_TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 比赛窗口：一叠比赛卡，像主页书堆一样滑着翻；当前这场把「队长近期计划」放最前，
 * 其次才是赛段。右下角加号常在，空态（没有队伍）也能开新比赛或加入。
 */
@Composable
internal fun CompetitionDeck(
    teams: List<TeamSummary>,
    state: TeamCollabState?,
    revision: Long,
    onSwitchTeam: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenBaseline: () -> Unit,
    onOpenProfile: () -> Unit,
    onPlan: () -> Unit,
    canPlan: Boolean,
    planBusy: Boolean,
    archived: Boolean,
    onInvite: () -> Unit = {},
) {
    val accent = replandAccent(3)
    val surface = rememberLandscapeSurface(MaterialTheme.colorScheme.surfaceContainerHigh)
    val reducedMotion = LocalReducedMotion.current
    if (state == null) {
        EmptyDeck(accent = accent, surface = surface, onAdd = onAdd)
        return
    }
    val others = remember(teams, state.teamId) {
        teams.filter { it.teamId != state.teamId }.sortedByDescending { it.updatedAtEpochMillis }
    }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    val swipe = if (others.isNotEmpty()) {
        Modifier.pointerInput(others) {
            detectHorizontalDragGestures(
                onDragStart = { dragAccum.floatValue = 0f },
                onDragCancel = { dragAccum.floatValue = 0f },
                onDragEnd = {
                    val consumed = dragAccum.floatValue
                    dragAccum.floatValue = 0f
                    if (consumed < -80f) onSwitchTeam(others.first().teamId)
                    else if (consumed > 80f) onSwitchTeam(others.last().teamId)
                },
            ) { change, dragAmount ->
                change.consume()
                dragAccum.floatValue += dragAmount
            }
        }
    } else Modifier

    Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).then(swipe).testTag("team-deck")) {
        others.take(2).forEachIndexed { index, summary ->
            val relative = (index + 1).toFloat()
            val progress by animateFloatAsState(
                targetValue = relative,
                animationSpec = if (reducedMotion) tween(0) else spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "team-deck-behind-$index",
            )
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = surface,
                border = BorderStroke(1.dp, accent.ink.copy(alpha = .12f)),
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(-progress)
                    .graphicsLayer {
                        translationX = (13.dp * progress).toPx()
                        translationY = (-9.dp * progress).toPx()
                        rotationZ = 3.2f * progress
                        scaleX = 1f - 0.05f * progress
                        scaleY = 1f - 0.05f * progress
                    }
                    .alpha(if (index == 0) 1f else .72f),
            ) {
                Box(Modifier.background(Brush.verticalGradient(listOf(accent.ink.copy(alpha = .16f), Color.Transparent)))) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(summary.teamName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("滑动切换", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = surface,
            border = BorderStroke(1.dp, accent.ink.copy(alpha = .2f)),
            modifier = Modifier.fillMaxWidth().zIndex(10f).testTag("team-deck-front"),
        ) {
            Box(
                Modifier.background(
                    Brush.verticalGradient(
                        0f to accent.ink.copy(alpha = .38f),
                        .42f to accent.ink.copy(alpha = .14f),
                        1f to accent.ink.copy(alpha = .04f),
                    ),
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 66.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    DeckHeader(state, teams, revision, accent = accent, onOpenProfile = onOpenProfile,
                        onSwitchTeam = onSwitchTeam, onAdd = onAdd, onInvite = onInvite)
                    PlanPreview(state, revision, accent.ink, canPlan = canPlan, planBusy = planBusy,
                        archived = archived, onPlan = onPlan)
                    HorizontalDivider(color = accent.ink.copy(alpha = .14f))
                    StagePreview(state, revision, accent.ink, onOpenBaseline = onOpenBaseline)
                }
            }
        }
        DesignClickableSurface(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .zIndex(20f)
                .padding(14.dp)
                .size(48.dp)
                .testTag("team-deck-add"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            pressedScale = .9f,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(DesignIcons.Plus, "新建比赛队伍", Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun DeckHeader(
    state: TeamCollabState,
    teams: List<TeamSummary>,
    revision: Long,
    accent: com.swan1127.repland.ui.theme.ReplandAccent,
    onOpenProfile: () -> Unit,
    onSwitchTeam: (String) -> Unit,
    onAdd: () -> Unit,
    onInvite: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenProfile).testTag("team-deck-profile"),
        ) {
            Text("第二课堂 · 比赛窗口", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = accent.ink.copy(alpha = .74f))
            Text(state.teamName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                state.profileCard?.competitionName ?: "档案卡待完善 · 点这里补比赛信息",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        MemberStack(state.members, revision)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val captain = state.members.firstOrNull { it.isCaptain }
        Surface(shape = RoundedCornerShape(999.dp), color = accent.ink.copy(alpha = .12f)) {
            Text(
                "队长 ${captain?.name ?: "未设"}",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = accent.ink,
            )
        }
        DeadlinePill(state.competitionDeadline, accent.ink)
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                "${teams.size} 个比赛 ▾",
                style = MaterialTheme.typography.labelSmall,
                color = accent.ink.copy(alpha = .8f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp).testTag("team-deck-menu"),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                teams.forEach { team ->
                    DropdownMenuItem(
                        text = { Text(if (team.teamId == state.teamId) "${team.teamName} ✓" else team.teamName) },
                        onClick = {
                            menuOpen = false
                            onSwitchTeam(team.teamId)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("新建 / 加入比赛队伍") }, onClick = {
                    menuOpen = false
                    onAdd()
                })
            }
        }
    }
    InviteCodeLine(state.inviteCode, accent.ink, onInvite)
}

@Composable
private fun InviteCodeLine(code: String, ink: Color, onInvite: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "队伍码 $code",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { clipboard.setText(AnnotatedString(code)) }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("team-deck-code"),
        )
        Text("点码复制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "邀请队员 ›",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onInvite)
                .padding(horizontal = 6.dp, vertical = 4.dp).testTag("team-deck-invite"),
        )
    }
}

@Composable
private fun MemberStack(members: List<MemberCard>, revision: Long) {
    val shown = remember(revision) { members.take(3) }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            shown.forEachIndexed { index, member ->
                val accent = replandAccent(if (member.isCaptain) 1 else 2 + index)
                Box(
                    Modifier
                        .offset(x = (index * -8).dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(member.name.take(1), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = accent.ink)
                }
            }
            if (members.size > shown.size) {
                Box(
                    Modifier.offset(x = (shown.size * -8).dp).size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+${members.size - shown.size}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("共 ${members.size} 人", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanPreview(
    state: TeamCollabState,
    revision: Long,
    ink: Color,
    canPlan: Boolean,
    planBusy: Boolean,
    archived: Boolean,
    onPlan: () -> Unit,
) {
    val upcoming = remember(revision) {
        val today = LocalDate.now()
        state.blocks.asSequence()
            .filter { it.sourceTeamId == state.teamId && it.taskId != null && !it.date.isBefore(today) }
            .sortedWith(compareBy({ it.date }, { it.startMinute }))
            .take(2)
            .toList()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("队长安排的近期计划", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = ink)
        Spacer(Modifier.weight(1f))
        if (upcoming.isEmpty() && canPlan && !archived) {
            TextButton(onClick = onPlan, enabled = !planBusy, modifier = Modifier.testTag("team-deck-plan")) {
                Text(if (planBusy) "排期中…" else "排这一周", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (upcoming.isEmpty()) {
        Text(
            if (canPlan) "还没排近期计划 · 点右上角「排这一周」" else "队长还没排近期计划",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        upcoming.forEach { block ->
            val task = state.tasks.firstOrNull { it.taskId == block.taskId }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(ink.copy(alpha = .6f)))
                Text(
                    "${block.date.format(DECK_DAY)} ${timeText(block.startMinute)}",
                    style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .78f),
                )
                Text(
                    task?.title ?: block.label ?: "队伍安排",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(memberName(state, block.memberId), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (upcoming.size == 1) {
            Text("更远的安排看「本周排期」", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StagePreview(state: TeamCollabState, revision: Long, ink: Color, onOpenBaseline: () -> Unit) {
    val today = LocalDate.now()
    val phases = remember(revision) { state.phases.sortedBy { it.start } }
    val current = remember(revision) { phases.firstOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) } }
    val next = remember(revision) { phases.firstOrNull { it.start.isAfter(today) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("比赛赛段", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = ink)
        Spacer(Modifier.weight(1f))
        Text("任务基线 ›", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .8f),
            modifier = Modifier.clickable(onClick = onOpenBaseline).padding(4.dp).testTag("team-deck-baseline"))
    }
    if (phases.isEmpty()) {
        Text("还没建赛段 · 点这里把比赛拆成阶段和任务", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpenBaseline))
        return
    }
    Row(Modifier.fillMaxWidth().padding(end = 56.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        phases.forEach { phase ->
            val span = ChronoUnit.DAYS.between(phase.start, phase.end).toInt().coerceAtLeast(1) + 1
            val done = today.isAfter(phase.end)
            val isNow = phase == current
            Box(
                Modifier.weight(span.toFloat()).height(6.dp).clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            isNow -> ink
                            done -> ink.copy(alpha = .3f)
                            else -> ink.copy(alpha = .12f)
                        },
                    ),
            )
        }
    }
    val line = when {
        current != null -> "当前 ${current.name} · 剩 ${ChronoUnit.DAYS.between(today, current.end).toInt() + 1} 天"
        next != null -> "下一段 ${next.name} · ${next.start.format(DECK_DAY)} 开始"
        else -> "赛段已全部结束"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .8f))
        next?.takeIf { current != null }?.let {
            Text(
                "　下一段 ${it.name} · ${it.start.format(DECK_DAY)}–${it.end.format(DECK_DAY)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val hardMoment = remember(revision) {
        CompetitionStages.moments(phases).firstOrNull { it.hard && !it.date.isBefore(today) }
    }
    val nextGate = remember(revision) {
        phases.firstOrNull { it.isGate && !it.end.isBefore(today) }
    }
    when {
        hardMoment != null -> Text(
            "硬时点 ${hardMoment.label} · ${hardMoment.date.format(DECK_DAY)}（${hardMoment.phaseName}）",
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = .72f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        nextGate != null -> Text(
            "关卡 ${nextGate.name} · ${nextGate.end.format(DECK_DAY)}",
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = .72f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyDeck(
    accent: com.swan1127.repland.ui.theme.ReplandAccent,
    surface: Color,
    onAdd: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = surface,
        border = BorderStroke(1.dp, accent.ink.copy(alpha = .2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.background(
                Brush.verticalGradient(
                    0f to accent.ink.copy(alpha = .34f),
                    .45f to accent.ink.copy(alpha = .12f),
                    1f to accent.ink.copy(alpha = .03f),
                ),
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 34.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("第二课堂 · 比赛窗口", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = accent.ink.copy(alpha = .74f))
                Text("把比赛收进同一个窗口", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(72.dp))
            }
            DesignClickableSurface(
                onClick = onAdd,
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(48.dp)
                    .testTag("team-deck-empty-add"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                pressedScale = .9f,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(DesignIcons.Plus, "新建比赛队伍", Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun DeadlinePill(deadline: LocalDate?, ink: Color) {
    val days = deadline?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
    val text = when {
        days == null -> "未设比赛截止"
        days < 0 -> "截止已过 ${-days} 天"
        days == 0 -> "今天截止"
        else -> "距截止 $days 天"
    }
    Surface(shape = RoundedCornerShape(999.dp), color = ink.copy(alpha = .1f)) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = ink,
        )
    }
}

private fun timeText(minute: Int): String =
    java.time.LocalTime.of((minute / 60) % 24, minute % 60).format(DECK_TIME)
