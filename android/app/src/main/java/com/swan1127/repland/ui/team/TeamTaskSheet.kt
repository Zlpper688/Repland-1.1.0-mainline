package com.swan1127.repland.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignFilterChip
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val TASK_MD_FMT = DateTimeFormatter.ofPattern("M月d日")

/** 参数草稿：新建与改参数共用；标题/工时为文本，日期/小时为滚轮索引。 */
internal class TaskParamsDraft(
    initialTitle: String = "",
    initialHours: String = "4",
    initialTier: TaskTier = TaskTier.MID_TERM,
    initialPhaseId: String? = null,
    date: LocalDate = LocalDate.now().plusWeeks(1),
    hourOfDay: Int = 18,
) {
    var title by mutableStateOf(initialTitle)
    var hours by mutableStateOf(initialHours)
    var tier by mutableStateOf(initialTier)
    var phaseId by mutableStateOf(initialPhaseId)
    var year by mutableIntStateOf(date.year)
    var month by mutableIntStateOf(date.monthValue)
    var day by mutableIntStateOf(date.dayOfMonth)
    var hour by mutableIntStateOf(hourOfDay)

    val hoursValue: Double get() = hours.toDoubleOrNull()?.coerceAtLeast(0.5) ?: 1.0

    fun pickedDate(): LocalDate {
        val safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        return LocalDate.of(year, month, safeDay)
    }

    fun useDate(date: LocalDate) {
        year = date.year
        month = date.monthValue
        day = date.dayOfMonth
    }
}

/** 新任务：参数与截止一次设完，保存即按当前空闲排进个人日程。 */
@Composable
internal fun NewTaskSheet(
    vm: TeamViewModel,
    state: TeamCollabState,
    actor: MemberCard?,
    onDismiss: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val initialDeadline = state.competitionDeadline
        ?.takeIf { !it.isBefore(today) }
        ?: today.plusWeeks(1)
    val draft = remember { TaskParamsDraft(date = initialDeadline) }
    var assignee by remember { mutableStateOf<String?>(null) }
    val canCreate = actor?.hasPermission(com.swan1127.repland.domain.team.TeamPermission.CREATE_TASK) == true ||
        actor?.hasPermission(com.swan1127.repland.domain.team.TeamPermission.EDIT_BASELINE) == true
    val canSchedule = actor?.hasPermission(com.swan1127.repland.domain.team.TeamPermission.SCHEDULE_TRANSFER) == true
    val picked = draft.pickedDate()
    val overDeadline = state.competitionDeadline != null && picked > state.competitionDeadline
    val pastDeadline = picked < today

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(.94f).testTag("team-new-task-sheet"),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("新任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (canSchedule) "设好截止就按空闲排进日程，之后随时改参数"
                            else "设好截止先建任务；排期需要有排期权限的成员",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("team-new-task-close")) {
                        Text("取消")
                    }
                }
                TaskParamsEditor(
                    draft = draft,
                    state = state,
                    today = today,
                    overDeadline = overDeadline,
                    assignee = assignee,
                    onAssignee = { assignee = it },
                    suggestions = state.competitionDeadline?.let {
                        listOf("比赛截止 ${it.format(TASK_MD_FMT)}" to it)
                    }.orEmpty(),
                    titleTag = "team-new-task-title",
                )
                DesignButton(
                    onClick = {
                        vm.createScheduledTask(
                            title = draft.title,
                            tier = draft.tier,
                            phaseId = draft.phaseId,
                            estimatedHours = draft.hoursValue,
                            assignedTo = assignee,
                            deadline = picked,
                            deadlineHour = draft.hour,
                        )
                        onDismiss()
                    },
                    enabled = canCreate && draft.title.isNotBlank() && !overDeadline && !pastDeadline && ui.busy == null,
                    modifier = Modifier.fillMaxWidth().testTag("team-new-task-submit"),
                ) {
                    Text(if (ui.busy == TeamBusy.SCHEDULING) "排期中…" else "建任务并排期")
                }
            }
        }
    }
}

/** 标题/工种/阶段/人/截止 + 倒排预览；编辑已有任务时不显示指派（转派另有入口）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskParamsEditor(
    draft: TaskParamsDraft,
    state: TeamCollabState,
    today: LocalDate,
    overDeadline: Boolean,
    assignee: String?,
    onAssignee: ((String?) -> Unit)?,
    suggestions: List<Pair<String, LocalDate>>,
    titleTag: String = "team-task-title",
) {
    val picked = draft.pickedDate()
    val daysLeft = ChronoUnit.DAYS.between(today, picked).toInt()
    val perDay = draft.hoursValue / daysLeft.coerceAtLeast(1)
    val years = buildList {
        add(today.year)
        val last = (state.competitionDeadline?.year ?: today.year + 1).coerceAtLeast(today.year)
        for (year in today.year..last) if (year != today.year) add(year)
    }

    TeamField(draft.title, { draft.title = it }, label = "任务名（例：完成路演 PPT 初稿）",
        modifier = Modifier.testTag(titleTag))
    TeamField(draft.hours, { draft.hours = it.filter { ch -> ch.isDigit() || ch == '.' } },
        label = "预计工时（小时）", modifier = Modifier.testTag("team-task-hours"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskTier.entries.forEach { candidate ->
            DesignFilterChip(selected = draft.tier == candidate, onClick = { draft.tier = candidate },
                label = { Text(candidate.label) }, accentIndex = candidate.ordinal)
        }
    }
    if (draft.tier == TaskTier.SHORT_TERM) {
        Text("会议类 · 不自动排时段", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DesignFilterChip(selected = draft.phaseId == null, onClick = { draft.phaseId = null },
            label = { Text("不挂阶段") }, accentIndex = 0)
        state.phases.forEachIndexed { index, phase ->
            DesignFilterChip(selected = draft.phaseId == phase.phaseId,
                onClick = { draft.phaseId = phase.phaseId },
                label = { Text(phase.name) }, accentIndex = index + 1)
        }
    }
    if (onAssignee != null) {
        Text("指派给", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesignFilterChip(selected = assignee == null, onClick = { onAssignee(null) },
                label = { Text("自动定人") }, accentIndex = 0)
            state.members.forEachIndexed { index, member ->
                DesignFilterChip(selected = assignee == member.memberId,
                    onClick = { onAssignee(member.memberId) },
                    label = { Text(member.name) }, accentIndex = index + 1)
            }
        }
    }
    Text("截止", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (years.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            years.forEach { year ->
                DesignFilterChip(selected = draft.year == year,
                    onClick = { draft.year = year },
                    label = { Text("$year 年") }, accentIndex = 0)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WheelColumn("月", (1..12).map { "${it}月" }, draft.month - 1) { draft.month = it + 1 }
        WheelColumn("日", (1..31).map { "$it" }, draft.day - 1) { draft.day = it + 1 }
        WheelColumn("时", (0..23).map { "$it 时" }, draft.hour) { draft.hour = it }
    }
    if (overDeadline) {
        Text("超过比赛截止 ${state.competitionDeadline?.format(TASK_MD_FMT)}，换个日期再保存",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else if (picked < today) {
        Text("截止日期早于今天，换个日期再保存",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else if (daysLeft == 0) {
        Text("今天截止 · 建议 ${"%.1f".format(perDay)} 小时",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("距截止 $daysLeft 天 · 建议每日 ${"%.1f".format(perDay)} 小时",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (suggestions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { (label, date) ->
                TextButton(
                    onClick = { draft.useDate(date) },
                    modifier = Modifier.testTag("team-task-suggestion"),
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
