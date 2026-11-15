package com.swan1127.repland.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.ChatDayPlanner
import com.swan1127.repland.domain.model.TimeBlockValidator
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.DesignFilterChip
import com.swan1127.repland.ui.components.DesignSwitch
import com.swan1127.repland.ui.components.DesignTextButton
import com.swan1127.repland.ui.components.DesignTextField
import com.swan1127.repland.ui.components.DurationWheelField
import com.swan1127.repland.ui.components.LabeledTimeWheel
import com.swan1127.repland.ui.components.TaskDatePickerDialog
import com.swan1127.repland.ui.components.TimeWheelSteps
import com.swan1127.repland.ui.components.rememberDurationWheelHold
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import com.swan1127.repland.ui.components.taskDateLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

private enum class RepeatChoice { NEVER, DAILY, WEEKLY }

private enum class ExpandSection { START, END, REPEAT }

/** vivo 对齐的提前量档位：默认 10 分钟与 LocalReminderPlanner.EventLeadMinutes 同口径。 */
private val LeadOptions = listOf(5, 10, 15, 30, 60, 120)
private val DefaultLeadIndex = LeadOptions.indexOf(10)

private fun leadLabel(minutes: Int): String =
    if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时"

private val WeekdayOrder = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

private fun weekdayLabel(day: DayOfWeek): String = "周" + listOf("一", "二", "三", "四", "五", "六", "日")[day.value - 1]

/** 一键模板：只预填标题/重复/时间/提醒，点完仍可逐项改，不会自动保存。时长都落在时长梯上。 */
private data class EventTemplate(
    val key: String,
    val label: String,
    val title: String,
    val repeat: RepeatChoice,
    val weekday: DayOfWeek?,
    val startMinute: Int,
    val durationMinutes: Int,
    val reminderOn: Boolean,
)

private val EventTemplates = listOf(
    EventTemplate("morning", "晨间计划", "晨间计划", RepeatChoice.DAILY, null, 8 * 60, 10, false),
    EventTemplate("review", "睡前回顾", "睡前回顾", RepeatChoice.DAILY, null, 22 * 60, 15, true),
    EventTemplate("weekly", "每周复盘", "每周复盘", RepeatChoice.WEEKLY, DayOfWeek.SUNDAY, 20 * 60, 30, false),
)

/** 一行「标签左、值右」；整行可点，收起时整页仍是全貌。 */
@Composable
private fun ScheduleFormRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 一页式新增日程（对齐 vivo 日历的排布）：标题、开始、结束、提醒、重复全部同屏，
 * 收起时每行只显示当前值；点行才把轮盘/选项就地展开。只填名字也能存（默认「今天 · 下一档 · 1 小时」）。
 * 单次写成一条任务 + 一段 USER_PINNED 时段；每天/每周写成每周时间块（每天=七块，每周=勾选的星期各一块）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEventSheet(
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSaveOnce: (title: String, date: LocalDate, startMinute: Int, endMinute: Int, reminderEnabled: Boolean, leadMinutes: Int?) -> Unit,
    onSaveRepeat: (title: String, weekdays: Set<DayOfWeek>, startMinute: Int, endMinute: Int, reminderEnabled: Boolean, leadMinutes: Int?) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val latestSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !latestSaving })

    val initialStart = remember {
        val now = LocalTime.now()
        (now.hour * 60 + (now.minute + 4) / 5 * 5).coerceAtMost(1_435)
    }
    var title by rememberSaveable { mutableStateOf("") }
    var repeatName by rememberSaveable { mutableStateOf(RepeatChoice.NEVER.name) }
    var reminderOn by rememberSaveable { mutableStateOf(false) }
    var leadIndex by rememberSaveable { mutableStateOf(DefaultLeadIndex) }
    var dateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var weekdayStorage by rememberSaveable { mutableStateOf("") }
    var expandedName by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var templateStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var templateDuration by rememberSaveable { mutableStateOf<Int?>(null) }
    var templateVersion by rememberSaveable { mutableStateOf(0) }

    val repeatChoice = RepeatChoice.valueOf(repeatName)
    val expanded = expandedName?.let { runCatching { ExpandSection.valueOf(it) }.getOrNull() }
    val date = dateEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    val weekdaySet = weekdayStorage.split(',').filter(String::isNotBlank).map(String::toInt).toSet()

    val startHold = rememberTimeWheelHold(templateStart ?: initialStart, key = "schedule-event-start-v$templateVersion")
    val durationHold = rememberDurationWheelHold(templateDuration ?: 60, key = "schedule-event-duration-v$templateVersion")
    val startMinute = startHold.settledMinuteOfDay()
    val durationMinutes = durationHold.settledMinutes()
    val endMinute = startMinute + durationMinutes
    val startText = TimeBlockValidator.formatTime(startMinute)
    val endText = TimeBlockValidator.formatTime(endMinute.coerceAtMost(1_440))

    fun collapse() { expandedName = null; keyboard?.hide() }
    fun toggle(section: ExpandSection) {
        expandedName = if (expanded == section) null else section.name
        keyboard?.hide()
    }
    fun toggleWeekday(day: DayOfWeek) {
        weekdayStorage = (if (day.value in weekdaySet) weekdaySet - day.value else weekdaySet + day.value)
            .sorted().joinToString(",")
    }

    fun submit() {
        if (saving) return
        keyboard?.hide()
        val blocked = when {
            endMinute > 1_440 -> "结束时间越过了午夜，把开始或时长调小一点。"
            repeatChoice == RepeatChoice.WEEKLY && weekdaySet.isEmpty() -> "选一下要重复的星期。"
            else -> null
        }
        localError = blocked
        if (blocked != null) return
        val finalTitle = title.trim().ifBlank { "日程" }
        val lead = if (reminderOn) LeadOptions[leadIndex.coerceIn(LeadOptions.indices)] else null
        when (repeatChoice) {
            RepeatChoice.NEVER -> onSaveOnce(finalTitle, date, startMinute, endMinute, reminderOn, lead)
            RepeatChoice.DAILY -> onSaveRepeat(finalTitle, WeekdayOrder.toSet(), startMinute, endMinute, reminderOn, lead)
            RepeatChoice.WEEKLY -> onSaveRepeat(finalTitle,
                weekdaySet.map(DayOfWeek::of).toSet(), startMinute, endMinute, reminderOn, lead)
        }
    }

    val repeatValue = when (repeatChoice) {
        RepeatChoice.NEVER -> "不重复"
        RepeatChoice.DAILY -> "每天"
        RepeatChoice.WEEKLY -> if (weekdaySet.isEmpty()) "每周" else
            weekdaySet.sorted().joinToString("、") { weekdayLabel(DayOfWeek.of(it)) }
    }
    val startValue = if (repeatChoice == RepeatChoice.NEVER) "${taskDateLabel(date)} · $startText" else startText
    val endValue = "$endText（时长 $durationMinutes 分钟）"
    val preview = (if (repeatChoice == RepeatChoice.NEVER) "${taskDateLabel(date)} " else "$repeatValue ") +
        "$startText–${if (endMinute <= 1_440) endText else "次日 " + TimeBlockValidator.formatTime(endMinute - 1_440)}"

    ModalBottomSheet(
        modifier = Modifier.testTag("schedule-event-sheet"),
        onDismissRequest = { if (!saving && !sheetState.isVisible) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        BackHandler(enabled = !saving) {
            scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("新增日程", style = MaterialTheme.typography.titleLarge)
                    Text("名字和时间填好就能存；提醒、重复想改随时再改",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DesignTextButton(
                    onClick = ::submit,
                    enabled = !saving && endMinute <= 1_440 &&
                        (repeatChoice != RepeatChoice.WEEKLY || weekdaySet.isNotEmpty()),
                    modifier = Modifier.testTag("schedule-event-save"),
                ) { Text(if (saving) "正在保存…" else "保存") }
            }

            DesignTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().testTag("schedule-event-title"),
                placeholder = { Text("考试、活动、会议、自习都能记") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("一键模板", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CapsuleRow(
                    capsules = EventTemplates.map { template ->
                        CapsuleSpec(template.key, template.label, CapsuleKind.VARIABLE,
                            tag = "schedule-event-template-${template.key}")
                    },
                    onCapsuleClick = { spec ->
                        val template = EventTemplates.first { it.key == spec.key }
                        title = template.title
                        repeatName = template.repeat.name
                        weekdayStorage = template.weekday?.value?.toString() ?: ""
                        reminderOn = template.reminderOn
                        templateStart = template.startMinute
                        templateDuration = template.durationMinutes
                        templateVersion += 1
                        expandedName = null
                        localError = null
                        keyboard?.hide()
                    },
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    ScheduleFormRow("开始", startValue,
                        onClick = { toggle(ExpandSection.START) }, tag = "schedule-event-start-row")
                    AnimatedVisibility(expanded == ExpandSection.START) {
                        Column(
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (repeatChoice == RepeatChoice.NEVER) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("日期", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    DesignTextButton(
                                        onClick = { showDatePicker = true },
                                        enabled = !saving,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.testTag("schedule-event-date"),
                                    ) { Text(taskDateLabel(date)) }
                                }
                            }
                            LabeledTimeWheel("开始", startHold, tag = "schedule-event-start", enabled = !saving)
                            TimeWheelSteps(startHold, tag = "schedule-event-start", enabled = !saving)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    ScheduleFormRow("结束", endValue,
                        onClick = { toggle(ExpandSection.END) }, tag = "schedule-event-end-row")
                    AnimatedVisibility(expanded == ExpandSection.END) {
                        Column(
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("改时长，结束时间跟着开始走",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DurationWheelField(durationHold, Modifier.fillMaxWidth(), tag = "schedule-event-duration", enabled = !saving)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("提醒", style = MaterialTheme.typography.bodyLarge)
                            Text(if (reminderOn) "开始前 ${leadLabel(LeadOptions[leadIndex.coerceIn(LeadOptions.indices)])}响" else "只排在日程里，不响铃",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DesignSwitch(
                            checked = reminderOn,
                            onCheckedChange = { reminderOn = it; localError = null },
                            modifier = Modifier.testTag("schedule-event-reminder"),
                            enabled = !saving,
                        )
                    }
                    AnimatedVisibility(reminderOn) {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("提前多久响", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LeadOptions.forEachIndexed { index, minutes ->
                                    DesignFilterChip(
                                        selected = leadIndex == index,
                                        onClick = { leadIndex = index },
                                        label = { Text(leadLabel(minutes)) },
                                        modifier = Modifier.testTag("schedule-event-lead-$minutes"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    ScheduleFormRow("重复", repeatValue,
                        onClick = { toggle(ExpandSection.REPEAT) }, tag = "schedule-event-repeat-row")
                    AnimatedVisibility(expanded == ExpandSection.REPEAT) {
                        Column(
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesignFilterChip(
                                    selected = repeatChoice == RepeatChoice.NEVER,
                                    onClick = { repeatName = RepeatChoice.NEVER.name; localError = null },
                                    label = { Text("不重复") },
                                    modifier = Modifier.testTag("schedule-event-repeat-never"),
                                )
                                DesignFilterChip(
                                    selected = repeatChoice == RepeatChoice.DAILY,
                                    onClick = { repeatName = RepeatChoice.DAILY.name; localError = null },
                                    label = { Text("每天") },
                                    modifier = Modifier.testTag("schedule-event-repeat-daily"),
                                )
                                DesignFilterChip(
                                    selected = repeatChoice == RepeatChoice.WEEKLY,
                                    onClick = {
                                        repeatName = RepeatChoice.WEEKLY.name
                                        if (weekdaySet.isEmpty()) weekdayStorage = date.dayOfWeek.value.toString()
                                        localError = null
                                    },
                                    label = { Text("每周") },
                                    modifier = Modifier.testTag("schedule-event-repeat-weekly"),
                                )
                            }
                            if (repeatChoice == RepeatChoice.WEEKLY) {
                                Text("挑要重复的星期（可多选）", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WeekdayOrder.forEach { day ->
                                        DesignFilterChip(
                                            selected = day.value in weekdaySet,
                                            onClick = { toggleWeekday(day) },
                                            label = { Text(weekdayLabel(day)) },
                                            modifier = Modifier.testTag("schedule-event-weekday-${day.value}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(preview, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("schedule-event-preview"))
            if (endMinute > 1_440) {
                Text("结束时间越过午夜",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("schedule-event-midnight-warning"))
            }
            if (ChatDayPlanner.overlapsSleepWindow(startMinute, endMinute.coerceAtMost(1_440))) {
                Text("压在 23:00–07:00 睡眠窗口上",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            (saveError ?: localError)?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("schedule-event-error"))
            }
        }
    }
    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = date,
            onDismiss = { showDatePicker = false },
            onDateSelected = { dateEpochDay = it.toEpochDay(); showDatePicker = false },
            title = "选择开始日期",
        )
    }
}
