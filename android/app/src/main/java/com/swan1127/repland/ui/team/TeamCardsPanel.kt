package com.swan1127.repland.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swan1127.repland.domain.team.CardVisibility
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.MemberImport
import com.swan1127.repland.domain.team.PersonCard
import com.swan1127.repland.domain.team.PersonCardBook
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeammateMatch
import com.swan1127.repland.domain.team.availabilityText
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignFilterChip
import com.swan1127.repland.ui.components.DesignOutlinedButton
import com.swan1127.repland.ui.theme.replandAccent
import kotlin.math.abs

/**
 * 个人资料卡：默认卡组队前就能写，竞赛卡只由真实队伍生成（不能手填，所以可信）；
 * 空闲 + 能力既写进公开卡随邀请发出，也用于按需求匹配名册里的队友。
 */
@Composable
internal fun TeamCardsPanel(
    vm: TeamViewModel,
    ui: TeamViewModel.UiState,
    state: TeamCollabState?,
    actor: MemberCard?,
    revision: Long = 0L,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("默认资料卡")
        DefaultCardEditor(vm, ui.cards.defaultCard(), actor)
        SectionHeader("竞赛履历")
        CompetitionCards(vm, ui.cards)
        SectionHeader("按需求找队友")
        TeammateMatchList(vm, ui, state, actor)
        if (state != null) {
            SectionHeader("队里每个人的空闲", "私人日程不展示")
            TeamAvailability(state, revision)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 空态也能打开：资料卡属于个人域，建队前就该写。 */
@Composable
internal fun TeamCardsOverlay(
    vm: TeamViewModel,
    ui: TeamViewModel.UiState,
    state: TeamCollabState?,
    actor: MemberCard?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("team-cards-sheet"),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("我的资料卡", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("team-cards-close")) {
                        Text("完成")
                    }
                }
                TeamCardsPanel(
                    vm, ui, state, actor,
                    revision = ui.snapshot?.revision ?: 0L,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultCardEditor(vm: TeamViewModel, card: PersonCard, actor: MemberCard?) {
    val key = card.cardId + card.updatedAtEpochMillis
    var name by rememberSaveable(key) { mutableStateOf(card.name) }
    var headline by rememberSaveable(key) { mutableStateOf(card.headline) }
    var abilities by rememberSaveable(key) { mutableStateOf(card.abilities) }
    var expectation by rememberSaveable(key) { mutableStateOf(card.expectation) }
    var availability by rememberSaveable(key) { mutableStateOf(card.availabilityInput) }
    var capacity by rememberSaveable(key) { mutableStateOf(card.dailyCapacityHours) }
    var visibilityName by rememberSaveable(key) { mutableStateOf(card.visibility.name) }
    val parsed = remember(availability) { MemberImport.parseAvailability(availability) }
    val visibility = CardVisibility.entries.firstOrNull { it.name == visibilityName } ?: CardVisibility.PRIVATE
    val quickWindows = listOf("周一19:00-22:00", "周三19:00-22:00", "周六09:00-12:00", "周日14:00-18:00")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (actor != null && actor.name.isNotBlank()) {
                Text("当前身份：${actor.name}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TeamField(name, { name = it }, label = "名字",
                modifier = Modifier.testTag("team-card-name"))
            TeamField(headline, { headline = it }, label = "一句话定位（如：能做原型，也能写论文）",
                modifier = Modifier.testTag("team-card-headline"))
            TeamField(abilities, { abilities = it }, label = "能力 / 擅长（逗号隔开，匹配时用）",
                singleLine = false, minLines = 2, modifier = Modifier.testTag("team-card-abilities"))
            TeamField(expectation, { expectation = it }, label = "团队预期：想找什么样的队友、怎么配合",
                singleLine = false, minLines = 2, modifier = Modifier.testTag("team-card-expectation"))
            Text("每周空闲", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            TeamField(availability, { availability = it },
                label = "如 周一19:00-22:00；周三20:00-21:30",
                singleLine = false, minLines = 2, modifier = Modifier.testTag("team-card-availability"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quickWindows.forEachIndexed { index, window ->
                    DesignFilterChip(
                        selected = false,
                        onClick = {
                            availability = if (availability.isBlank()) {
                                window
                            } else {
                                availability.trimEnd('；', ';', ' ') + "；" + window
                            }
                        },
                        label = { Text(window) },
                        accentIndex = index % 3,
                    )
                }
            }
            Text(
                if (parsed.isEmpty()) {
                    "格式：周一19:00-22:00"
                } else {
                    "已识别：" + availabilityText(parsed)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("每日可投入", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 2.0, 3.0, 4.0, 6.0).forEachIndexed { index, hours ->
                    DesignFilterChip(
                        selected = abs(capacity - hours) < 0.01,
                        onClick = { capacity = hours },
                        label = { Text(if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "${hours}h") },
                        accentIndex = index % 3,
                    )
                }
            }
            Text("谁能看到", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardVisibility.entries.forEachIndexed { index, option ->
                    DesignFilterChip(
                        selected = visibility == option,
                        onClick = { visibilityName = option.name },
                        label = { Text(option.label) },
                        accentIndex = index + 1,
                    )
                }
            }
            Text(
                if (visibility == CardVisibility.PUBLIC) {
                    "公开：随邀请文案发出"
                } else {
                    "仅自己：只存本机，不随邀请发出"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DesignButton(
                onClick = { vm.saveDefaultCard(name, headline, abilities, expectation, parsed, capacity, visibility) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("team-card-save-default"),
            ) { Text("保存默认卡") }
        }
    }
}

@Composable
private fun CompetitionCards(vm: TeamViewModel, book: PersonCardBook) {
    val competitions = book.competitionCards()
    if (competitions.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("team-comp-empty"),
        ) {
            Text(
                "还没有竞赛履历卡",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    competitions.forEach { card -> CompetitionEditor(vm, card) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompetitionEditor(vm: TeamViewModel, card: PersonCard) {
    val key = card.cardId + card.updatedAtEpochMillis
    var contribution by rememberSaveable(key) { mutableStateOf(card.headline) }
    var result by rememberSaveable(key) { mutableStateOf(card.result) }
    var visibilityName by rememberSaveable(key) { mutableStateOf(card.visibility.name) }
    val visibility = CardVisibility.entries.firstOrNull { it.name == visibilityName } ?: CardVisibility.PRIVATE
    val meta = listOfNotNull(
        card.teamName?.takeIf { it.isNotBlank() },
        card.roleTag?.takeIf { it.isNotBlank() },
        card.teammateCount.takeIf { it > 0 }?.let { "同队 $it 人" },
    ).joinToString(" · ")

    Card(Modifier.fillMaxWidth().testTag("team-comp-card-${card.cardId}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Badge("来自队伍记录", MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer)
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TeamField(contribution, { contribution = it }, label = "我在这场比赛里做了什么",
                singleLine = false, minLines = 2)
            TeamField(result, { result = it }, label = "结果 / 收获（归档成绩会自动带出）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardVisibility.entries.forEachIndexed { index, option ->
                    DesignFilterChip(
                        selected = visibility == option,
                        onClick = { visibilityName = option.name },
                        label = { Text(option.label) },
                        accentIndex = index + 1,
                    )
                }
            }
            DesignOutlinedButton(
                onClick = { vm.saveCompetitionCard(card.cardId, contribution, result, visibility) },
                modifier = Modifier.fillMaxWidth().testTag("team-comp-save-${card.cardId}"),
            ) { Text("保存这张履历") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeammateMatchList(
    vm: TeamViewModel,
    ui: TeamViewModel.UiState,
    state: TeamCollabState?,
    actor: MemberCard?,
    modifier: Modifier = Modifier,
) {
    var need by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf(0.0) }
    val revision = ui.snapshot?.revision ?: 0L
    val matches = remember(need, hours, ui.roster, revision) {
        if (need.isBlank()) emptyList() else vm.matchTeammates(need, hours)
    }
    val canInvite = state != null && !state.archived &&
        actor?.hasPermission(TeamPermission.MANAGE_MEMBERS) == true

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamField(need, { need = it }, label = "缺什么 / 要做什么（如：前端开发、路演 PPT）",
            modifier = Modifier.testTag("team-match-need"))
        Text("每周需要投入", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0 to "不限", 2.0 to "2h", 3.0 to "3h", 5.0 to "5h").forEachIndexed { index, (value, label) ->
                DesignFilterChip(
                    selected = abs(hours - value) < 0.01,
                    onClick = { hours = value },
                    label = { Text(label) },
                    accentIndex = index % 3,
                )
            }
        }
        when {
            ui.roster.people.isEmpty() -> Text(
                "名册还是空的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            need.isBlank() -> Unit
            matches.isEmpty() -> Text(
                "名册里没有可匹配的人。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> matches.take(5).forEach { match ->
                MatchRow(
                    match = match,
                    canInvite = canInvite,
                    onInvite = { vm.inviteRosterPerson(match.person.key) },
                )
            }
        }
        if (state == null && ui.roster.people.isNotEmpty()) {
            Text("还没进队", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MatchRow(match: TeammateMatch, canInvite: Boolean, onInvite: () -> Unit) {
    val accent = replandAccent(2)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("team-match-row-${match.person.key}"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(accent.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(match.person.name.take(1), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = accent.ink)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(match.person.name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (match.person.hasTeamed) {
                        Text("一起 ${match.person.teamIds.size} 场",
                            style = MaterialTheme.typography.labelSmall, color = accent.ink)
                    }
                }
                Text(match.reasons.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            DesignOutlinedButton(
                onClick = onInvite,
                enabled = canInvite,
                modifier = Modifier.testTag("team-match-invite-${match.person.key}"),
            ) { Text("邀入") }
        }
    }
}

@Composable
private fun TeamAvailability(state: TeamCollabState, revision: Long) {
    val members = remember(revision) { state.members.toList() }
    members.forEachIndexed { index, member ->
        val accent = replandAccent(index % 3)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(accent.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(member.name.take(1), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = accent.ink)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(member.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        val position = if (member.isCaptain) "队长" else "队员"
                        Text(
                            buildString {
                                append(position)
                                member.roleTag?.takeIf { it.isNotBlank() && it != position }
                                    ?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (member.weeklyAvailability.isEmpty()) "空闲未登记" else availabilityText(member.weeklyAvailability),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("每日可投入 ${capacityText(member.dailyCapacityHours)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun capacityText(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "${"%.1f".format(hours)}h"
