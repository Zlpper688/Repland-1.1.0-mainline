package com.swan1127.repland.ui.team

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swan1127.repland.domain.team.CompetitionImport
import com.swan1127.repland.domain.team.CompetitionImportResult
import com.swan1127.repland.domain.team.CompetitionProfileCard
import com.swan1127.repland.domain.team.ProfileSlotGroup
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignDialog
import com.swan1127.repland.ui.components.DesignOutlinedButton
import com.swan1127.repland.ui.theme.replandAccent
import java.time.LocalDate

/**
 * 队伍入口：先选身份（队长登记 / 队员凭码加入），队长登记在同一個窗口里
 * 完成「队伍 → 比赛 → 材料 → 档案卡」，创建后直接进管理界面。
 */
@Composable
internal fun TeamEntryChooser(
    onDismiss: () -> Unit,
    onCaptain: () -> Unit,
    onMember: () -> Unit,
) {
    DesignDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始比赛队伍") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EntryChoice(
                    title = "我是队长 · 登记比赛",
                    badge = "队",
                    tag = "team-entry-captain",
                    onClick = onCaptain,
                )
                EntryChoice(
                    title = "我是队员 · 输入队伍码",
                    badge = "员",
                    tag = "team-entry-member",
                    onClick = onMember,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("暂不开始") }
        },
    )
}

@Composable
private fun EntryChoice(
    title: String,
    badge: String,
    tag: String,
    onClick: () -> Unit,
) {
    val accent = replandAccent(if (badge == "队") 1 else 2)
    DesignClickableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(accent.surface, CircleShape), contentAlignment = Alignment.Center) {
                Text(badge, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = accent.ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text("›", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun JoinTeamDialog(vm: TeamViewModel, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    DesignDialog(
        onDismissRequest = onDismiss,
        title = { Text("凭队伍码加入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TeamField(
                    value = code,
                    onValueChange = { value -> code = value.uppercase().filter { it.isLetterOrDigit() }.take(8) },
                    label = "队伍码",
                    modifier = Modifier.testTag("team-join-code"),
                )
                TeamField(
                    value = name,
                    onValueChange = { name = it },
                    label = "你的名字",
                    modifier = Modifier.testTag("team-join-name"),
                )
                Text("队伍码由队长提供", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            DesignButton(
                onClick = {
                    vm.joinTeam(code, name)
                    onDismiss()
                },
                enabled = code.isNotBlank() && name.isNotBlank(),
                modifier = Modifier.testTag("team-join-submit"),
            ) { Text("加入队伍") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun CaptainCreateSheet(vm: TeamViewModel, onDismiss: () -> Unit) {
    var teamName by rememberSaveable { mutableStateOf("") }
    var captainName by rememberSaveable { mutableStateOf("") }
    var competitionName by rememberSaveable { mutableStateOf("") }
    var deadline by rememberSaveable { mutableStateOf(LocalDate.now().plusWeeks(6).toString()) }
    var material by rememberSaveable { mutableStateOf("") }
    var card by remember { mutableStateOf<CompetitionProfileCard?>(null) }
    var intakeNote by remember { mutableStateOf<String?>(null) }
    var missingValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scroll = rememberScrollState()

    fun parseMaterial() {
        if (material.isBlank()) {
            intakeNote = "先把比赛通知或文件里的文字粘进来"
            return
        }
        val parsed = CompetitionImport.parse(material)
        if (parsed.recognized == 0) {
            intakeNote = "没识别到字段：每行写「字段：内容」，例如「报名截止：2026-11-15」"
            return
        }
        if (competitionName.isBlank()) competitionName = parsed.competitionName.orEmpty()
        card = parsed.toCard(competitionName.ifBlank { parsed.competitionName ?: "未命名比赛" })
        parsed.nearestDeadline()?.let { deadline = it.toString() }
        intakeNote = "识别 ${parsed.recognized} 行，已生成档案卡；缺的关键项在下面手动补"
    }

    fun startManualCard() {
        card = CompetitionProfileCard(
            competitionName = competitionName.ifBlank { teamName.ifBlank { "未命名比赛" } },
            fields = emptyMap(),
        )
        intakeNote = "已建空白档案卡：先补报名截止和人数上限，其余可进去再补"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f).testTag("team-create-sheet"),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("登记比赛队伍", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("team-create-close")) { Text("关闭") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IntakeSection("1 · 队伍")
                    TeamField(
                        value = teamName,
                        onValueChange = { teamName = it },
                        label = "队伍名称",
                        modifier = Modifier.testTag("team-create-name"),
                    )
                    TeamField(
                        value = captainName,
                        onValueChange = { captainName = it },
                        label = "你的名字（队长）",
                        modifier = Modifier.testTag("team-create-captain"),
                    )
                    Text("队伍码生成后可复制给队员", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    IntakeSection("2 · 比赛")
                    TeamField(
                        value = competitionName,
                        onValueChange = { competitionName = it },
                        label = "比赛名称",
                        modifier = Modifier.testTag("team-create-competition"),
                    )
                    TeamField(
                        value = deadline,
                        onValueChange = { deadline = it },
                        label = "比赛截止（yyyy-MM-dd）",
                        modifier = Modifier.testTag("team-create-deadline"),
                    )

                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    IntakeSection("3 · 比赛材料 → 档案卡")
                    Text("每行「字段：内容」", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TeamField(
                        value = material,
                        onValueChange = { material = it },
                        label = "比赛信息",
                        singleLine = false,
                        minLines = 5,
                        maxLines = 9,
                        modifier = Modifier.testTag("team-create-material"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { material = COMPETITION_IMPORT_SAMPLE },
                            modifier = Modifier.testTag("team-create-sample")) { Text("填入示例") }
                        TextButton(onClick = ::parseMaterial,
                            modifier = Modifier.testTag("team-create-parse")) { Text("解析成档案卡") }
                        TextButton(onClick = ::startManualCard,
                            modifier = Modifier.testTag("team-create-manual")) { Text("手动建卡") }
                    }
                    intakeNote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    card?.let { current ->
                        ProfileIntakeCard(
                            card = current,
                            missingValues = missingValues,
                            onMissingChange = { key, value -> missingValues = missingValues + (key to value) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (teamName.isBlank() || captainName.isBlank()) {
                            Text("队伍名和队长名必填", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        DesignButton(
                            onClick = {
                                // 没解析材料也允许建卡：填了比赛名就先给一张空卡，进档案卡再补。
                                var finalCard = card ?: competitionName.trim().takeIf { it.isNotEmpty() }?.let {
                                    CompetitionProfileCard(competitionName = it, fields = emptyMap())
                                }
                                val base = finalCard
                                finalCard = base?.copy(
                                    competitionName = competitionName.ifBlank { base.competitionName },
                                )
                                missingValues.forEach { (key, value) ->
                                    if (value.isNotBlank()) {
                                        finalCard = (finalCard ?: CompetitionProfileCard(
                                            competitionName = competitionName.ifBlank { teamName },
                                            fields = emptyMap(),
                                        )).withField(
                                            ProfileSlotGroup.BASIC_INFO,
                                            key,
                                            if (key == "signup_deadline") "报名截止" else "人数上限",
                                            value.trim(),
                                        )
                                    }
                                }
                                vm.createTeam(
                                    name = teamName,
                                    captainName = captainName,
                                    deadline = runCatching { LocalDate.parse(deadline.trim()) }.getOrNull(),
                                    profileCard = finalCard,
                                )
                                onDismiss()
                            },
                            enabled = teamName.isNotBlank() && captainName.isNotBlank(),
                            modifier = Modifier.testTag("team-create-submit"),
                        ) { Text("创建并进入") }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntakeSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

/** 登记窗里的输入框统一成纸面圆角，不再用裸 Material 方框。 */
@Composable
internal fun TeamField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProfileIntakeCard(
    card: CompetitionProfileCard,
    missingValues: Map<String, String>,
    onMissingChange: (String, String) -> Unit,
) {
    val missing = remember(card) {
        listOf("signup_deadline" to "报名截止", "member_limit" to "人数上限")
            .filter { card.field(it.first).isNullOrBlank() }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("team-create-profile-preview"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.competitionName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ProfileSlotGroup.entries.forEach { group ->
                val fields = card.visibleFields(group)
                if (fields.isEmpty()) return@forEach
                Text(group.label, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fields.forEach { field ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(field.label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp))
                        Text(field.value.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            if (card.extras.isNotEmpty()) {
                Text("补充区", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.extras.forEach { extra ->
                    Text("${extra.key}：${extra.value}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (missing.isNotEmpty()) {
                Text("还缺关键项", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
                missing.forEach { (key, label) ->
                    TeamField(
                        value = missingValues[key].orEmpty(),
                        onValueChange = { onMissingChange(key, it) },
                        label = label,
                        modifier = Modifier.testTag("team-create-missing-$key"),
                    )
                }
            }
        }
    }
}

private fun CompetitionImportResult.toCard(name: String): CompetitionProfileCard =
    CompetitionProfileCard(
        competitionName = name.ifBlank { competitionName ?: "未命名比赛" },
        fields = fields.mapValues { (_, list) -> list.toList() },
        extras = extras,
    )

/** 从材料里取最靠近比赛终点的一个日期：提交截止 > 决赛 > 初赛 > 报名截止。 */
private fun CompetitionImportResult.nearestDeadline(): LocalDate? {
    val ordered = listOf("submission_deadline", "final", "preliminary", "signup_deadline")
    ordered.forEach { key ->
        val text = fields.values.flatten().firstOrNull { it.key == key }?.value ?: return@forEach
        val match = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})""").find(text) ?: return@forEach
        val (year, month, day) = match.destructured
        runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()?.let { return it }
    }
    return null
}
