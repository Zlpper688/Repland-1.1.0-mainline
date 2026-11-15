package com.swan1127.repland.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.CharterRuleKind
import com.swan1127.repland.domain.team.CompetitionProfileCard
import com.swan1127.repland.domain.team.CompetitionStages
import com.swan1127.repland.domain.team.LedgerEntry
import com.swan1127.repland.domain.team.LedgerKind
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.Objection
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.ProfileSlotGroup
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamMemoryKind
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.domain.team.WeeklyReportDraft
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignFilterChip
import com.swan1127.repland.ui.components.DesignOutlinedButton
import com.swan1127.repland.ui.theme.replandAccent
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val MD_FMT = DateTimeFormatter.ofPattern("M月d日")

internal val COMPETITION_IMPORT_SAMPLE = """
    比赛名称：中国国际大学生创新大赛（2026）校赛
    主办方：教务处 · 创新创业学院
    报名截止：2026-11-15
    人数上限：5
    组队要求：3–5 人，需指定 1 名队长
    报名方式：登录校赛平台提交报名表
    校赛初评：2026-12-10
    提交截止：2027-01-03
    提交物：项目书、路演 PPT、1 分钟演示视频
    评审标准：创新性 40%、可行性 30%、路演与答辩 30%
    答辩形式：8 分钟路演 + 5 分钟问答
    其他：校赛前 3 名推荐参加市赛
""".trimIndent()

private val MEMBER_IMPORT_SAMPLE = """
    阿岚 | 技术 | 负责小程序与原型开发 | 周一19:00-22:00；周三19:00-22:00；周六09:00-12:00
    小满 | 设计 | 负责路演PPT与视觉设计 | 周二20:00-22:30；周五19:00-22:00；周日19:00-22:00
    老白 | 数据 | 负责市场调研与用户访谈 | 周一20:00-22:00；周四20:00-22:00；周六14:00-17:00
    阿橙 | 文案 | 负责项目书初稿与文案 | 周二19:00-21:30；周日14:00-18:00
""".trimIndent()

internal fun minutesText(minute: Int): String =
    LocalTime.of((minute / 60) % 24, minute % 60).format(TIME_FMT)

internal fun memberName(state: TeamCollabState, memberId: String?): String =
    memberId?.let { id -> state.members.firstOrNull { it.memberId == id }?.name } ?: "未分配"

// ============================================================
// 本周排期 · 时间轴（日视图）
// ============================================================

/** 成员 × 时段块；私有块只显示「在忙」占位（§8 时间可见、内容不可见）。 */
@Composable
internal fun DayRope(vm: TeamViewModel, state: TeamCollabState, revision: Long, onMoveBlock: (PersonalBlock) -> Unit) {
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val date = LocalDate.now().plusDays(dayOffset.toLong())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { dayOffset -= 1 }) { Text("◀") }
            Text("${date.format(MD_FMT)} 周${"一二三四五六日"[date.dayOfWeek.value - 1]}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { dayOffset += 1 }) { Text("▶") }
            TextButton(onClick = { dayOffset = 0 }) { Text("今天") }
        }
        HourRuler()
        state.members.forEach { member ->
            DayMemberRow(vm, state, member, date, revision, onMoveBlock)
        }
        Text("斜纹=私有 · 红框=冲突 · ˟=估算", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HourRuler() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val span = (23 - 8) * 60f
        listOf(8, 12, 16, 20).forEach { hour ->
            Text("$hour", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = maxWidth * ((hour - 8) * 60f / span)).padding(start = 0.dp))
        }
    }
}

@Composable
private fun DayMemberRow(vm: TeamViewModel, state: TeamCollabState, member: MemberCard, date: LocalDate, revision: Long, onMoveBlock: (PersonalBlock) -> Unit) {
    val (dayBlocks, conflictIds) = remember(revision) {
        val blocks = state.blocks.filter { it.memberId == member.memberId && it.date == date }
        blocks to blocks.filter { a -> blocks.any { b -> b !== a && b.overlaps(a) } }.map { it.blockId }.toSet()
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            member.name + if (member.lowConfidence) "˟" else "",
            Modifier.width(52.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (member.lowConfidence) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
        BoxWithConstraints(Modifier.weight(1f).height(34.dp)) {
            val span = (23 - 8) * 60f
            fun frac(minute: Int): Float = ((minute - 8 * 60).coerceIn(0, 1439)) / span
            // 空闲窗口底色
            member.weeklyAvailability[date.dayOfWeek].orEmpty().forEach { window ->
                Box(
                    Modifier
                        .offset(x = maxWidth * frac(window.startMinute))
                        .width(maxWidth * ((window.minutes.coerceAtMost(1439 - 8 * 60 + 1)).toFloat() / span))
                        .height(34.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                )
            }
            dayBlocks.forEach { block ->
                val isConflict = block.blockId in conflictIds
                val isPrivate = block.visibility == BlockVisibility.PRIVATE_BUSY
                val color = when {
                    isConflict -> MaterialTheme.colorScheme.errorContainer
                    block.sourceTeamId == state.teamId -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Surface(
                    color = color,
                    shape = RoundedCornerShape(5.dp),
                    border = if (isConflict) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
                    modifier = Modifier
                        .offset(x = maxWidth * frac(block.startMinute))
                        .width(maxWidth * ((block.endMinute - block.startMinute).toFloat() / span))
                        .height(32.dp)
                        .clickable {
                            if (block.memberId == vm.state.value.actorId && block.sourceTeamId == state.teamId) {
                                onMoveBlock(block)
                            }
                        },
                ) {
                    Text(
                        block.publicLabel() ?: "在忙",
                        Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPrivate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 队员改自己的时间（§5）：±30 分钟平移，越界（不在自己的空闲窗口内）会被领域层拒绝并上浮提示。 */
@Composable
internal fun BlockMoveDialog(vm: TeamViewModel, block: PersonalBlock, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整「${block.publicLabel() ?: "安排"}」") },
        text = { Text("只能在自己的空闲窗口内平移；越界会被拒绝。") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.moveOwnBlock(block.blockId, block.startMinute - 30); onDismiss() }) { Text("提前 30 分钟") }
                TextButton(onClick = { vm.moveOwnBlock(block.blockId, block.startMinute + 30); onDismiss() }) { Text("推后 30 分钟") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

// ============================================================
// 档案卡（§3）：六组槽位 + 补充区 + 队员卡
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfilePanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, revision: Long) {
    val (card, members) = remember(revision) { state.profileCard to state.members.toList() }
    var showImport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (card == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("档案卡未建", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var showCreate by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesignButton(onClick = { showCreate = true }) { Text("手动建档") }
                        DesignOutlinedButton(onClick = { showImport = true }, modifier = Modifier.testTag("team-profile-import")) {
                            Text("粘贴比赛材料")
                        }
                    }
                    if (showCreate) TextFieldDialog(
                        title = "比赛全称", initial = "", hint = "如：中国国际大学生创新大赛校赛",
                        onConfirm = { vm.createProfileCard(it) }, onDismiss = { showCreate = false },
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.competitionName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { showImport = true }, modifier = Modifier.testTag("team-profile-import")) {
                    Text("粘贴/更新")
                }
            }
            MissingCriticalBanner(card)
            ProfileSlotGroup.entries.forEach { group -> ProfileGroupCard(vm, group, card) }
            ExtraArea(vm, card)
        }
        if (showImport) CompetitionImportDialog(vm, onDismiss = { showImport = false })
        // 队员卡（§3.2）：负责说明（项目维度），队长和本人都可改
        SectionHeader("队员卡 · 负责说明")
        members.forEach { member ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(member.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            member.roleTag?.let { Badge(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (member.lowConfidence) Badge("课表未导入", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text(
                            member.responsibility.ifBlank { "还没写负责说明" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (member.responsibility.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (actor != null && (actor.isCaptain || actor.memberId == member.memberId)) {
                        var showEdit by remember { mutableStateOf(false) }
                        TextButton(onClick = { showEdit = true }) { Text("改") }
                        if (showEdit) TextFieldDialog(
                            title = "${member.name} 的负责说明", initial = member.responsibility, hint = "这个项目里 TA 管什么",
                            onConfirm = { vm.updateResponsibility(member.memberId, it) }, onDismiss = { showEdit = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MissingCriticalBanner(card: CompetitionProfileCard) {
    val missing = card.missingCritical()
    if (missing.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "待补充：${missing.joinToString("、")}",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ProfileGroupCard(vm: TeamViewModel, group: ProfileSlotGroup, card: CompetitionProfileCard) {
    val fields = card.visibleFields(group)
    val known = fields.filter { !it.isUnknown }
    val unknownCount = fields.count { it.isUnknown }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (unknownCount > 0) Badge("未提取 $unknownCount 项", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (known.isEmpty() && unknownCount == 0) {
                Text("暂无信息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            known.forEach { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(field.value.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            var showAdd by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdd = true }) { Text("＋ 补一条「${group.label}」") }
            if (showAdd) ProfileFieldDialog(vm, group, onDismiss = { showAdd = false })
        }
    }
}

@Composable
private fun ProfileFieldDialog(vm: TeamViewModel, group: ProfileSlotGroup, initialKey: String? = null, initialLabel: String? = null, initialValue: String? = null, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initialKey ?: "") }
    var label by remember { mutableStateOf(initialLabel ?: "") }
    var value by remember { mutableStateOf(initialValue ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补「${group.label}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("字段名（如 signup_deadline）") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("显示名（如 报名截止）") }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("内容") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (key.isNotBlank() && label.isNotBlank() && value.isNotBlank()) {
                    vm.saveProfileCardField(group, key.trim(), label.trim(), value.trim())
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CompetitionImportDialog(vm: TeamViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴比赛材料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("一行「字段：内容」", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("team-profile-import-input"),
                    placeholder = { Text("比赛名称：…\n报名截止：2026-11-15\n人数上限：5") },
                )
                TextButton(onClick = { text = COMPETITION_IMPORT_SAMPLE },
                    modifier = Modifier.testTag("team-profile-import-sample")) { Text("填入示例") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.importCompetition(text); onDismiss() },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("team-profile-import-confirm"),
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraArea(vm: TeamViewModel, card: CompetitionProfileCard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("补充区（预设外的关键信息）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            card.extras.forEach { extra ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${extra.key}：${extra.value}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.promoteExtra(ProfileSlotGroup.BASIC_INFO, extra.key) }) { Text("升级为槽位") }
                }
            }
            var showAdd by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdd = true }) { Text("＋ 补充区新增") }
            if (showAdd) TextFieldDialog(
                title = "新增补充信息", initial = "", hint = "格式：键 值（空格分隔）",
                onConfirm = { text ->
                    val parts = text.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) vm.addProfileExtra(parts[0], parts[1])
                },
                onDismiss = { showAdd = false },
            )
        }
    }
}

// ============================================================
// 基线（§4 硬约束层）+ 确认面板（§7 对象化操作）
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BaselinePanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, revision: Long, onOpenTask: (TeamTask) -> Unit) {
    val canEditBaseline = actor?.hasPermission(TeamPermission.EDIT_BASELINE) == true
    val canCreate = canEditBaseline || actor?.hasPermission(TeamPermission.CREATE_TASK) == true
    val canSchedule = actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true
    var showAddPhase by remember { mutableStateOf(false) }
    var showNewTask by remember { mutableStateOf(false) }
    val (phases, grouped) = remember(revision) { state.phases.toList() to state.tasks.groupBy { it.phaseId } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canCreate) {
            DesignButton(onClick = { showNewTask = true },
                modifier = Modifier.fillMaxWidth().testTag("team-new-task")) {
                Text("＋ 新任务（设截止即排期）")
            }
        }
        SectionHeader("阶段", if (canEditBaseline) null else "仅队长或有权限成员可改")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            phases.forEach { phase ->
                DesignFilterChip(selected = false, onClick = {}, label = {
                    Text("${phase.name} ${phase.start.format(MD_FMT)}–${phase.end.format(MD_FMT)}", maxLines = 1)
                }, accentIndex = phases.indexOf(phase))
            }
            if (canEditBaseline) TextButton(onClick = { showAddPhase = true }) { Text("＋ 阶段") }
        }
        SectionHeader("任务包")
        phases.forEach { phase ->
            Text("${phase.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            (grouped[phase.phaseId].orEmpty() + grouped[null].orEmpty().take(0)).forEach { task ->
                TaskRow(state, task, onClick = { onOpenTask(task) })
            }
        }
        (grouped[null].orEmpty()).forEach { task -> TaskRow(state, task, onClick = { onOpenTask(task) }) }
        if (canSchedule) {
            TextButton(
                onClick = { vm.planWeek() },
                enabled = vm.state.value.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("team-plan-week"),
            ) {
                Text(if (vm.state.value.busy == TeamBusy.SCHEDULING) "排期中…" else "整体重排本周（先出草案，确认后生效）")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showAddPhase) AddPhaseDialog(vm, onDismiss = { showAddPhase = false })
    if (showNewTask) NewTaskSheet(vm, state, actor, onDismiss = { showNewTask = false })
}

@Composable
private fun TaskRow(state: TeamCollabState, task: TeamTask, onClick: () -> Unit) {
    val deadline = task.effectiveDeadline(state.phases, state.competitionDeadline)
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (task.status == TeamTaskStatus.WONT) TextDecoration.LineThrough else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(task.tier.label, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.rank == com.swan1127.repland.domain.team.TaskRank.MUST) {
                        Badge("MUST", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Text(memberName(state, task.assignedTo), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(deadline?.format(MD_FMT) ?: "无期限", style = MaterialTheme.typography.labelMedium,
                    color = if (deadline == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(task.status.label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddPhaseDialog(vm: TeamViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf(LocalDate.now().plusWeeks(2).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增阶段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("套用比赛环节模板", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                CompetitionStages.all.forEach { template ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val startDate = runCatching { LocalDate.parse(start.trim()) }.getOrDefault(LocalDate.now())
                                vm.applyStageTemplate(template.id, startDate)
                                onDismiss()
                            }
                            .testTag("team-template-${template.id}"),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(template.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "共 ${CompetitionStages.totalDays(template)} 天 · " +
                                    template.stages.joinToString(" → ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(name, { name = it }, label = { Text("阶段名") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("开始（yyyy-MM-dd）") }, singleLine = true)
                OutlinedTextField(end, { end = it }, label = { Text("结束（yyyy-MM-dd）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    vm.addPhase(name, LocalDate.parse(start.trim()), LocalDate.parse(end.trim()))
                }
                onDismiss()
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 任务面板（§7）：参数与截止一次改完即重排 + 取消（MoSCoW）+ 接手人推荐。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TaskConfirmSheet(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, task: TeamTask, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val initial = task.hardDeadline ?: task.computedDeadline ?: today.plusWeeks(1)
    val hoursText = if (task.estimatedHours % 1.0 == 0.0) {
        task.estimatedHours.toInt().toString()
    } else {
        task.estimatedHours.toString()
    }
    val draft = remember(task.taskId) {
        TaskParamsDraft(
            initialTitle = task.title,
            initialHours = hoursText,
            initialTier = task.tier,
            initialPhaseId = task.phaseId,
            date = initial,
            hourOfDay = task.deadlineHour ?: 18,
        )
    }
    val picked = draft.pickedDate()
    val overDeadline = state.competitionDeadline != null && picked > state.competitionDeadline
    val pastDeadline = picked < today
    val canEdit = actor?.hasPermission(TeamPermission.EDIT_BASELINE) == true
    val suggestions = buildList {
        task.computedDeadline?.let { add("AI 倒算 ${it.format(MD_FMT)}" to it) }
        task.hardDeadline?.let { add("当前 ${it.format(MD_FMT)}" to it) }
        task.phaseId?.let { id ->
            state.phases.firstOrNull { it.phaseId == id }?.end?.let { add("阶段截止 ${it.format(MD_FMT)}" to it) }
        }
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 没人接的任务，先给「指派给谁」，不让人滚过整张表才找到接手人
            if (task.assignedTo == null) {
                AssigneeSection(vm, state, actor, task, onDismiss)
                HorizontalDivider()
            }
            TaskParamsEditor(
                draft = draft,
                state = state,
                today = today,
                overDeadline = overDeadline,
                assignee = task.assignedTo,
                onAssignee = null,
                suggestions = suggestions,
                titleTag = "team-task-title",
            )
            if (!canEdit) {
                Text("没有「改基线与截止」权限", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DesignButton(
                onClick = {
                    vm.saveTaskParameters(
                        taskId = task.taskId,
                        title = draft.title,
                        tier = draft.tier,
                        phaseId = draft.phaseId,
                        estimatedHours = draft.hoursValue,
                        deadline = picked,
                        deadlineHour = draft.hour,
                    )
                    onDismiss()
                },
                enabled = canEdit && draft.title.isNotBlank() && !overDeadline && !pastDeadline && vm.state.value.busy == null,
                modifier = Modifier.fillMaxWidth().testTag("team-task-save"),
            ) {
                Text(if (vm.state.value.busy == TeamBusy.SCHEDULING) "重排中…" else "保存参数并重排")
            }

            HorizontalDivider()
            // 取消（内化 MoSCoW）：Should/Could 可降为 Won't；Must 不可取消
            Text("取消", style = MaterialTheme.typography.titleSmall)
            when {
                task.status == TeamTaskStatus.WONT -> DesignOutlinedButton(
                    onClick = { vm.restoreTask(task.taskId); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                ) { Text("撤销取消（恢复为待办）") }
                task.cancellable -> DesignOutlinedButton(
                    onClick = { vm.cancelTask(task.taskId); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("取消任务（${task.rank.label} → Won't）") }
                else -> Text("MUST 不可取消", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (task.assignedTo != null) {
                HorizontalDivider()
                AssigneeSection(vm, state, actor, task, onDismiss)
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.assignedTo == actor?.memberId && task.status != TeamTaskStatus.DONE) {
                    DesignButton(
                        onClick = { vm.reportDone(task.taskId, note = "在任务面板标记完成"); onDismiss() },
                        modifier = Modifier.weight(1f),
                    ) { Text("报告完成") }
                }
                var link by remember { mutableStateOf("") }
                if (actor?.hasPermission(TeamPermission.MANAGE_EXTERNAL_LINK) == true) {
                    OutlinedTextField(link, { link = it }, label = { Text("作品外链（登记进台账）") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.addExternalLink(task.taskId, link); link = "" }) { Text("登记") }
                }
            }
        }
    }
}

/** 接手人：推荐按钮（按适配度排序）+ 一键转派；转派自动生效 + 对方可一键异议。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssigneeSection(
    vm: TeamViewModel,
    state: TeamCollabState,
    actor: MemberCard?,
    task: TeamTask,
    onDismiss: () -> Unit,
) {
    Text(if (task.assignedTo == null) "指派给谁" else "接手人", style = MaterialTheme.typography.titleSmall)
    Text("当前：${memberName(state, task.assignedTo)}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true) {
        val others = state.members.filter { it.memberId != task.assignedTo }
            .sortedByDescending { it.affinityWith(task.title) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEachIndexed { index, candidate ->
                DesignFilterChip(
                    selected = false,
                    onClick = { vm.transferTask(task.taskId, candidate.memberId); onDismiss() },
                    label = { Text(if (index == 0) "推荐 · ${candidate.name}" else candidate.name) },
                    accentIndex = if (index == 0) 1 else 0,
                )
            }
        }
    }
}

/** 单列滚轮：点击选中（T2 无惯性吸附，保证确定性）。 */
@Composable
internal fun WheelColumn(label: String, items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState(selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(86.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.height(148.dp)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                items(items.size) { index ->
                    val selected = index == selectedIndex
                    Surface(
                        color = if (selected) replandAccent(0).surface else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp).clickable {
                            onSelect(index)
                            scope.launch { listState.animateScrollToItem(index) }
                        },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(items[index], style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 台账与周报（§9/§11）：只客观留证，不打分不排名
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerPanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, weeklyReport: WeeklyReportDraft?, revision: Long) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val financeTotal = remember(revision) {
        state.ledger.filter { it.kind == LedgerKind.FINANCE }.sumOf { it.amountCents ?: 0 }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 周报卡
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("周报", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (actor?.hasPermission(TeamPermission.WEEKLY_REPORT_TOGGLE) == true) {
                        DesignOutlinedButton(onClick = { vm.generateWeeklyReport() }, enabled = vm.state.value.busy == null) {
                            Text(if (vm.state.value.busy == TeamBusy.REPORTING) "生成中…" else "生成本周周报")
                        }
                    }
                }
                val report = weeklyReport
                if (report != null) {
                    ReportSection("进展摘要", report.progressSummary)
                    ReportSection("风险雷达", report.riskRadar)
                    ReportSection("下周重点", report.nextWeekFocus)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        report.stats.forEach { (key, value) ->
                            Badge("$key $value", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("客观留证 ${report.evidence.size} 条",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 财务记账（§9）
        if (actor?.hasPermission(TeamPermission.FINANCE_BOOKKEEPING) == true) {
            FinanceRow(vm)
        }
        if (financeTotal != 0L) {
            Text("累计流水 ¥${financeTotal / 100.0}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 台账流水
        SectionHeader("贡献台账")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "全部", "EXECUTION" to "执行", "FLOW" to "流转", "EXTERNAL_LINK" to "外链", "FINANCE" to "财务").forEach { (key, label) ->
                DesignFilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) }, accentIndex = 0)
            }
        }
        val entries = remember(revision, filter) {
            state.ledger.sortedByDescending { it.at }.filter { entry ->
                when (filter) {
                    "EXECUTION" -> entry.kind == LedgerKind.EXECUTION_BLOCK || entry.kind == LedgerKind.AUTO_SAYING
                    "FLOW" -> entry.kind == LedgerKind.TRANSFER || entry.kind == LedgerKind.OBJECTION || entry.kind == LedgerKind.STATUS_CHANGE
                    "EXTERNAL_LINK" -> entry.kind == LedgerKind.EXTERNAL_LINK
                    "FINANCE" -> entry.kind == LedgerKind.FINANCE
                    else -> true
                }
            }
        }
        if (entries.isEmpty()) Text("还没有台账条目", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEach { entry -> LedgerRow(state, entry) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReportSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        lines.forEach { line -> Text("· $line", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FinanceRow(vm: TeamViewModel) {
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("财务记账", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.width(120.dp))
                OutlinedTextField(reason, { reason = it }, label = { Text("用途") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val yuan = amount.toDoubleOrNull()
                    if (yuan != null) vm.recordFinance(yuan, reason)
                    amount = ""; reason = ""
                }) { Text("记一笔") }
            }
        }
    }
}

@Composable
private fun LedgerRow(state: TeamCollabState, entry: LedgerEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(entry.kind.label, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.evidence, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${memberName(state, entry.memberId)} · ${entry.at.format(DateTimeFormatter.ofPattern("M/d HH:mm"))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.externalUrl?.let { Text("外链", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
    }
}

// ============================================================
// 权限控制台（§10）：队长按人配置；头衔只是标签不自带权限
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PermissionPanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, revision: Long) {
    var showInvite by remember { mutableStateOf(false) }
    var removingMemberId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("队员与权限", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            DesignButton(onClick = { showInvite = true }, modifier = Modifier.testTag("team-open-invite")) {
                Text("组队邀请")
            }
        }
        Text("队长默认全权限；点选即授予或收回，全部动作留痕。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showInvite) TeamInviteSheet(vm, state, actor, onDismiss = { showInvite = false })
        val teamMembers = remember(revision) { state.members.filterNot { it.isCaptain } }
        if (teamMembers.isEmpty()) {
            Text("还没有队员：可以单独加，也可以批量导入——一行一人，姓名、分工、空闲时段一次粘完。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        teamMembers.forEach { member ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(member.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        member.roleTag?.let { Badge(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { removingMemberId = member.memberId }) { Text("移出") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamPermission.entries.forEach { permission ->
                            val granted = member.hasPermission(permission)
                            DesignFilterChip(
                                selected = granted,
                                onClick = { vm.setPermission(member.memberId, permission, !granted) },
                                label = { Text(permission.label) },
                                accentIndex = permission.ordinal,
                            )
                        }
                    }
                }
            }
        }
        removingMemberId?.let { memberId ->
            state.members.firstOrNull { it.memberId == memberId }?.let { member ->
                val activeCount = state.tasks.count { it.assignedTo == memberId && it.active }
                AlertDialog(
                    onDismissRequest = { removingMemberId = null },
                    title = { Text("移出 ${member.name}？") },
                    text = {
                        Text(
                            if (activeCount > 0) {
                                "移出后，名下 $activeCount 项在办任务自动转派给队长，台账与历史记录保留。"
                            } else {
                                "移出后，该队员不再出现在排期里，台账与历史记录保留。"
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.removeMember(memberId); removingMemberId = null }) { Text("移出") }
                    },
                    dismissButton = { TextButton(onClick = { removingMemberId = null }) { Text("取消") } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 队伍大局与队规：队长对全局的把控。保守底线恒在，策略可开关，规矩可自定义；全员可读，队长可改。 */
@Composable
internal fun CharterPanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, revision: Long) {
    val charter = remember(revision) { state.charter }
    val canEdit = actor?.isCaptain == true && !state.archived
    var goalDraft by remember(revision) { mutableStateOf(charter.goal) }
    var showAddRule by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("队伍大局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!canEdit) {
            Text(
                if (state.archived) "队伍已归档，只读。" else "只有队长能调整大局；你可以查看当前生效的目标与队规。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(Modifier.fillMaxWidth().testTag("charter-goal-card")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("队伍目标", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (canEdit) {
                    OutlinedTextField(
                        value = goalDraft,
                        onValueChange = { goalDraft = it },
                        label = { Text("一句话说清这支队伍要达成什么") },
                        modifier = Modifier.fillMaxWidth().testTag("charter-goal-input"),
                        minLines = 2,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DesignButton(
                            onClick = { vm.updateCharter(charter.withGoal(goalDraft)) },
                            enabled = goalDraft.trim() != charter.goal,
                            modifier = Modifier.testTag("charter-save-goal"),
                        ) { Text("保存目标") }
                    }
                } else {
                    Text(
                        charter.goal.ifBlank { "队长还没写下目标" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (charter.goal.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Text("保守底线 · 不可关闭", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        charter.rules.filter { it.kind == CharterRuleKind.LOCKED }.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Badge("底线", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(rule.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Text("协作策略 · 队长可开关", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        charter.toggleRules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rule.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { if (canEdit) vm.updateCharter(charter.withToggle(rule.id, it)) },
                        enabled = canEdit,
                        modifier = Modifier.testTag("charter-toggle-${rule.id}"),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("队长的规矩", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (canEdit) {
                DesignButton(onClick = { showAddRule = true }, modifier = Modifier.testTag("charter-add-rule")) { Text("加一条") }
            }
        }
        if (charter.customRules.isEmpty()) {
            Text(
                "还没有自定义规矩",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        charter.customRules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rule.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (canEdit) {
                        TextButton(onClick = { vm.updateCharter(charter.removeCustom(rule.id)) }) { Text("删除") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showAddRule) {
        TextFieldDialog(
            title = "加一条队规",
            initial = "",
            hint = "如：外部引用必须标来源；每周五晚集中评审",
            onConfirm = { text -> vm.updateCharter(charter.addCustom("custom-" + UUID.randomUUID().toString().take(8), text)) },
            onDismiss = { showAddRule = false },
        )
    }
}

/** 批量邀请：一行一人；角色/负责说明/空闲时段都可省略，粘贴后一次入队。 */
@Composable
internal fun MemberImportDialog(vm: TeamViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量邀请队员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("一行一人：姓名 | 角色 | 负责说明 | 空闲时段。后两列可省略；空行和 # 注释会跳过。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("team-member-import-input"),
                    placeholder = { Text("阿岚 | 技术 | 负责小程序开发 | 周一19:00-22:00；周三19:00-22:00") },
                )
                TextButton(onClick = { text = MEMBER_IMPORT_SAMPLE },
                    modifier = Modifier.testTag("team-member-import-sample")) { Text("填入示例") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.importMembers(text); onDismiss() },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("team-member-import-confirm"),
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============================================================
// 记忆库（§12 T5）：团队域全队可读 + 参赛归档 + 个人域仅本人可见
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryPanel(vm: TeamViewModel, state: TeamCollabState, actor: MemberCard?, revision: Long) {
    val canWrite = actor?.hasPermission(TeamPermission.MEMORY_WRITE) == true
    val canArchive = actor?.hasPermission(TeamPermission.DISSOLVE_ARCHIVE) == true
    var kind by rememberSaveable { mutableStateOf(TeamMemoryKind.MEETING.name) }
    var content by rememberSaveable { mutableStateOf("") }
    var showExport by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.archived) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("队伍已归档（只读）。参赛档案可在下方个人记忆域查看与导出。",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        // 团队域：档案要点 / 负责说明 / 会议结论
        SectionHeader("团队记忆", "写入需「记忆库写入」权限")
        if (canWrite && !state.archived) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamMemoryKind.entries.forEach { candidate ->
                            DesignFilterChip(selected = kind == candidate.name, onClick = { kind = candidate.name },
                                label = { Text(candidate.label) }, accentIndex = candidate.ordinal)
                        }
                    }
                    OutlinedTextField(content, { content = it }, label = { Text("记点什么（结论、约定、要点）") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    DesignButton(
                        onClick = { vm.addTeamMemory(TeamMemoryKind.valueOf(kind), content); content = "" },
                        enabled = content.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("team-memory-add"),
                    ) { Text("写入团队记忆") }
                }
            }
        } else if (!canWrite && !state.archived) {
            Text("你没有「记忆库写入」权限，可浏览全队记忆。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val memories = remember(revision) { state.memories.sortedByDescending { it.at } }
        if (memories.isEmpty()) {
            Text("还没有团队记忆。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        memories.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(entry.kind.label, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("${memberName(state, entry.authorId)} · ${entry.at.format(DateTimeFormatter.ofPattern("M/d HH:mm"))}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.content, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canWrite && !state.archived) {
                        TextButton(onClick = { vm.removeTeamMemory(entry.memoryId) }) { Text("删") }
                    }
                }
            }
        }

        // 参赛归档（§16）：一键归档 → 沉淀个人域 → 队伍只读
        SectionHeader("参赛归档")
        when {
            state.archived -> Text("本队已归档。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            canArchive -> {
                var result by rememberSaveable { mutableStateOf("") }
                var confirming by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(result, { result = it }, label = { Text("比赛成绩（如：校赛一等奖）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        DesignButton(onClick = { confirming = true }, enabled = result.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("team-archive")) {
                            Text("一键归档（队伍转只读）")
                        }
                    }
                }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("确认归档？") },
                        text = { Text("归档会把成绩、最终台账与作品外链沉淀进你的个人记忆域，队伍随即转为只读，不可撤销。") },
                        confirmButton = { TextButton(onClick = { vm.archiveTeam(result); confirming = false }) { Text("归档") } },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
                    )
                }
            }
            else -> Text("归档需「解散归档」权限（默认队长）。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 个人域（仅本人可见，跨队复用 §16）
        SectionHeader("个人记忆域", "仅本人可见，跨队共用")
        vm.state.value.humanProfile?.let { profile ->
            var fact by rememberSaveable { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(fact, { fact = it }, label = { Text("加一条画像（技能 / 擅长 / 偏好）") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.addProfileFact(fact); fact = "" }, enabled = fact.isNotBlank()) { Text("沉淀") }
            }
            if (profile.facts.isEmpty() && profile.archives.isEmpty()) {
                Text("个人记忆还是空的。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile.facts.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("· ${item.content}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.removeProfileFact(item.factId) }) { Text("删") }
                }
            }
            profile.archives.sortedByDescending { it.archivedAt }.forEach { archive ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(archive.teamName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Badge("参赛档案", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text("成绩：${archive.result}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "台账 ${archive.stats["ledger"] ?: 0} 条 · 外链 ${archive.stats["externalLinks"] ?: 0} 个 · " +
                                "归档于 ${archive.archivedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showExport = archive.archiveId }) { Text("导出纯文本") }
                    }
                }
            }

            // §12 mem0 云同步（可选远端层）：已配置后端 + 登录后可用；本地 JSON 仍是主副本
            if (vm.backendConfigured) {
                val scope = rememberCoroutineScope()
                var cloudMsg by remember { mutableStateOf<String?>(null) }
                var cloudQuery by rememberSaveable { mutableStateOf("") }
                var cloudHits by remember { mutableStateOf<List<String>>(emptyList()) }
                var cloudBusy by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("云端记忆（mem0）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesignButton(
                                onClick = {
                                    cloudBusy = true
                                    scope.launch {
                                        val result = vm.syncPersonalMemory(vm.backendBaseUrl)
                                        cloudMsg = result.message
                                        cloudBusy = false
                                    }
                                },
                                enabled = !cloudBusy,
                                modifier = Modifier.weight(1f).testTag("memory-cloud-sync"),
                            ) { Text(if (cloudBusy) "同步中…" else "同步个人记忆到云端") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                cloudQuery, { cloudQuery = it },
                                label = { Text("搜索云端记忆（如：擅长）") },
                                singleLine = true, modifier = Modifier.weight(1f).testTag("memory-cloud-query"),
                            )
                            TextButton(
                                onClick = {
                                    cloudBusy = true
                                    scope.launch {
                                        val result = vm.searchCloudMemory(vm.backendBaseUrl, cloudQuery)
                                        cloudHits = result.memories
                                        cloudMsg = result.message
                                        cloudBusy = false
                                    }
                                },
                                enabled = !cloudBusy && cloudQuery.isNotBlank(),
                            ) { Text("搜索") }
                        }
                        cloudHits.forEach { hit ->
                            Text("· $hit", style = MaterialTheme.typography.bodySmall)
                        }
                        cloudMsg?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    showExport?.let { archiveId ->
        val text = vm.exportArchiveText(archiveId).orEmpty()
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showExport = null },
            title = { Text("参赛档案导出") },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    showExport = null
                }) { Text("复制全文") }
            },
            dismissButton = { TextButton(onClick = { showExport = null }) { Text("关闭") } },
        )
    }
}

// ============================================================
// 通用小件
// ============================================================

@Composable
internal fun SectionHeader(title: String, hint: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun Badge(text: String, container: Color, content: Color) {
    Surface(color = container, shape = RoundedCornerShape(999.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
internal fun TextFieldDialog(title: String, initial: String, hint: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
