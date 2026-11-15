package com.swan1127.repland.ui.team

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.RosterPerson
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamRosterBook
import com.swan1127.repland.domain.team.inviteCode
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignOutlinedButton
import com.swan1127.repland.ui.theme.replandAccent

/**
 * 组队面：一张表回答「谁能来」——直接发码、共同参赛过、你想了解。
 * 名册是个人域，换队/归档都留着；点「邀入」把分工与空闲时段一起带进当前队伍。
 */
@Composable
internal fun TeamInviteSheet(
    vm: TeamViewModel,
    state: TeamCollabState,
    actor: MemberCard?,
    onDismiss: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val roster = ui.roster
    val canInvite = actor?.hasPermission(TeamPermission.MANAGE_MEMBERS) == true && !state.archived
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    var wantName by rememberSaveable { mutableStateOf("") }
    var wantNote by rememberSaveable { mutableStateOf("") }

    val publicSummary = ui.cards.publicSummary()
    val shareText = remember(state.teamName, state.profileCard?.competitionName, state.inviteCode, publicSummary) {
        buildString {
            append("我在「${state.teamName}」")
            state.profileCard?.competitionName?.takeIf { it.isNotBlank() }?.let { append("参加$it") }
            append("，队伍码 ${state.inviteCode}。\n")
            append("打开 Repland → 团队协作 → 我是队员 → 输入队伍码就能加入。")
            publicSummary?.takeIf { it.isNotBlank() }?.let { append("\n\n$it") }
        }
    }
    // 域聚合是就地变更的：key 必须挂 revision，挂 members 会命中同一个可变列表实例而不刷新。
    val inTeam = remember(ui.snapshot?.revision) {
        state.members.map { TeamRosterBook.keyOf(it.name) }.toSet()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f).testTag("team-invite-sheet"),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("组队 · 邀请队员", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("发码、翻历史队友、记下想拉的人", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("team-invite-close")) {
                        Text("完成")
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    InviteSection("直接发码") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.inviteCode,
                                Modifier.weight(1f).testTag("team-invite-code"),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = replandAccent(1).ink,
                            )
                            DesignOutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(state.inviteCode)) },
                                modifier = Modifier.testTag("team-invite-copy-code"),
                            ) { Text("复制码") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DesignOutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            },
                                            "邀请队员",
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("team-invite-share"),
                            ) { Text("分享邀请") }
                            DesignOutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(shareText)) },
                                modifier = Modifier.weight(1f).testTag("team-invite-copy-text"),
                            ) { Text("复制邀请文案") }
                        }
                        Text(
                            "跨设备加入需两端都连接协作后端",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (publicSummary == null) {
                            Text(
                                "邀请文案未含你的公开资料卡",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(publicSummary, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    InviteSection("按需求找人") {
                        TeammateMatchList(vm, ui, state, actor)
                    }

                    InviteSection("共同参赛过") {
                        val teammates = roster.teammates()
                        if (teammates.isEmpty()) {
                            Text("还没有历史队友",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            teammates.forEach { person ->
                                RosterRow(
                                    person = person,
                                    accentIndex = 1,
                                    inTeam = person.key in inTeam,
                                    canInvite = canInvite,
                                    onInvite = { vm.inviteRosterPerson(person.key) },
                                    onForget = null,
                                )
                            }
                        }
                    }

                    InviteSection("你想了解") {
                        val candidates = roster.candidates()
                        if (candidates.isEmpty()) {
                            Text("还没有记下的人",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            candidates.forEach { person ->
                                RosterRow(
                                    person = person,
                                    accentIndex = 2,
                                    inTeam = person.key in inTeam,
                                    canInvite = canInvite,
                                    onInvite = { vm.inviteRosterPerson(person.key) },
                                    onForget = { vm.forgetRosterPerson(person.key) },
                                )
                            }
                        }
                        TeamField(wantName, { wantName = it }, label = "姓名",
                            modifier = Modifier.testTag("team-roster-name"))
                        TeamField(wantNote, { wantNote = it }, label = "备注（想让他负责什么 / 怎么认识的）",
                            modifier = Modifier.testTag("team-roster-note"))
                        DesignButton(
                            onClick = {
                                vm.pinRosterPerson(wantName, wantNote)
                                wantName = ""
                                wantNote = ""
                            },
                            enabled = wantName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("team-roster-pin"),
                        ) { Text("记进「你想了解」") }
                    }

                    InviteSection("其他邀请方式") {
                        DesignOutlinedButton(
                            onClick = { showImport = true },
                            modifier = Modifier.fillMaxWidth().testTag("team-invite-import"),
                        ) { Text("批量粘贴导入（姓名 | 角色 | 分工 | 空闲）") }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                TeamField(addName, { addName = it }, label = "姓名 角色（角色可省略）",
                                    modifier = Modifier.testTag("team-invite-add-name"))
                            }
                            DesignButton(
                                onClick = {
                                    val parts = addName.trim().split(Regex("\\s+"), limit = 2)
                                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                                        vm.addMember(parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() })
                                        addName = ""
                                    }
                                },
                                enabled = canInvite && addName.isNotBlank(),
                                modifier = Modifier.testTag("team-invite-add-member"),
                            ) { Text("直接加进本队") }
                        }
                        if (!canInvite) {
                            Text(
                                if (state.archived) "已归档 · 只能记进名册"
                                else "没有邀请权限",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showImport) MemberImportDialog(vm, onDismiss = { showImport = false })
}

@Composable
private fun InviteSection(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun RosterRow(
    person: RosterPerson,
    accentIndex: Int,
    inTeam: Boolean,
    canInvite: Boolean,
    onInvite: () -> Unit,
    onForget: (() -> Unit)?,
) {
    val accent = replandAccent(accentIndex)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(accent.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(person.name.take(1), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = accent.ink)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(person.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = person.subtitle
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (person.note.isNotBlank()) {
                Text(person.note, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (inTeam) {
            Badge("已在队", MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DesignOutlinedButton(
                onClick = onInvite,
                enabled = canInvite,
                modifier = Modifier.testTag("team-roster-invite-${person.key}"),
            ) { Text("邀入") }
        }
        if (onForget != null) {
            TextButton(
                onClick = onForget,
                modifier = Modifier.testTag("team-roster-forget-${person.key}"),
            ) { Text("移除") }
        }
    }
}
