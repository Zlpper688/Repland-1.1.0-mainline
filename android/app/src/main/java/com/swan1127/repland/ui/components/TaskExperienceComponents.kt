package com.swan1127.repland.ui.components

import androidx.activity.compose.BackHandler

import com.swan1127.repland.ui.theme.MotionPage
import com.swan1127.repland.ui.theme.LocalReducedMotion
import com.swan1127.repland.ui.theme.ReplandMotion
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import com.swan1127.repland.ui.components.DesignButton as Button
import androidx.compose.material3.ButtonDefaults
import com.swan1127.repland.ui.components.DesignCheckbox as Checkbox
import androidx.compose.material3.LinearProgressIndicator
import com.swan1127.repland.ui.components.DesignDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.swan1127.repland.ui.components.DesignFilterChip as FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import com.swan1127.repland.ui.components.DesignOutlinedButton as OutlinedButton
import com.swan1127.repland.ui.components.DesignTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import com.swan1127.repland.ui.components.DesignTextButton as TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskInputSource
import com.swan1127.repland.domain.model.TaskCaptureDraft
import com.swan1127.repland.domain.model.TaskCaptureStage
import com.swan1127.repland.domain.model.TaskName
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import java.time.LocalDate
import kotlinx.coroutines.launch
import com.swan1127.repland.ui.theme.replandAccent
import com.swan1127.repland.ui.theme.taskAccentIndex
import com.swan1127.repland.ui.theme.ReplandAccent

/** 长按进多选、按住继续勾选所需的最小回调；由列表持有，行只负责把手势转成坐标。 */
class SelectionDrag(
    val onStart: (String, Float) -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * The default list representation is intentionally compact. Full task information belongs
 * in the detail screen, not in every row users scan throughout the day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: TaskRowEmphasis = TaskRowEmphasis.NORMAL,
    planningSummary: String? = null,
    selected: Boolean? = null,
    onToggleSelect: () -> Unit = {},
    selectionDrag: SelectionDrag? = null,
) {
    val inSelection = selected != null
    val latestDrag by rememberUpdatedState(selectionDrag)
    var rowTopPx by remember { mutableFloatStateOf(0f) }
    val statusColor = when {
        task.status == TaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        task.status == TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        task.dueDate?.isBefore(LocalDate.now()) == true && task.status.isActive -> MaterialTheme.colorScheme.error
        task.scheduledForDate?.isBefore(LocalDate.now()) == true && task.status.isActive -> MaterialTheme.colorScheme.tertiary
        emphasis == TaskRowEmphasis.ATTENTION -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = planningSummary ?: task.summaryLabel()
    // 第二课堂（比赛队伍任务）用统一金色；个人第一课堂仍按任务分类取色。
    val categoryAccent = replandAccent(if (task.isTeamMirror) 3 else taskAccentIndex(task.category))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (selected == true) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = if (inSelection) onToggleSelect else onOpen)
            // 短按仍然开详情：长按手势排在点击之后，两条路径互不吞掉对方。
            .then(
                if (selectionDrag == null) Modifier
                else Modifier.onGloballyPositioned { rowTopPx = it.boundsInRoot().top },
            )
            .then(
                if (selectionDrag == null) Modifier else Modifier.pointerInput(task.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { press -> latestDrag?.onStart?.invoke(task.id, rowTopPx + press.y) },
                        onDrag = { change, _ ->
                            change.consume()
                            latestDrag?.onDrag?.invoke(rowTopPx + change.position.y)
                        },
                        onDragEnd = { latestDrag?.onEnd?.invoke() },
                        onDragCancel = { latestDrag?.onEnd?.invoke() },
                    )
                },
            )
            .testTag("task-card-${task.displayName}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (inSelection) {
            Checkbox(
                checked = selected == true,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("task-select-${task.id}"),
            )
        } else {
            IconButton(
                onClick = if (task.status == TaskStatus.NOT_STARTED || task.status == TaskStatus.POSTPONED) onStart else onOpen,
                modifier = Modifier
                    .size(48.dp)
                    .background(categoryAccent.surface, RoundedCornerShape(12.dp))
                    .testTag("task-start-${task.id}")
                    .semantics { contentDescription = if (task.status == TaskStatus.NOT_STARTED || task.status == TaskStatus.POSTPONED) "开始 ${task.displayName}" else "查看 ${task.displayName}" },
            ) {
                Icon(
                    imageVector = when (task.status) {
                        TaskStatus.COMPLETED -> Icons.Outlined.CheckCircle
                        TaskStatus.IN_PROGRESS -> Icons.Outlined.PlayArrow
                        else -> Icons.Outlined.PlayArrow
                    },
                    contentDescription = null,
                    tint = statusColor,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (task.status == TaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.inputSources.duration == com.swan1127.repland.domain.model.TaskInputSource.PLANNING_ESTIMATE) {
                Text("规划估算 · ${task.totalDurationMinutes ?: 30} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.isTeamMirror) TaskMetaLabel(
                    task.originLabel?.let { "第二课堂 · $it" } ?: "第二课堂",
                    accent = replandAccent(3),
                )
                if (task.category != TaskCategory.UNSPECIFIED) TaskMetaLabel(task.category.captureLabel())
                if (task.userPriority != TaskPriority.UNSPECIFIED) TaskMetaLabel(task.userPriority.captureLabel())
            }
            val progress = when (task.status) {
                TaskStatus.COMPLETED -> 1f
                else -> task.progressPercent?.takeIf { it in 0..100 }?.div(100f)
            }
            if (progress != null) LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Icon(
            imageVector = if (emphasis == TaskRowEmphasis.ATTENTION) Icons.Outlined.Warning else Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
        )
    }
}

@Composable
private fun TaskMetaLabel(label: String, accent: ReplandAccent? = null) {
    Surface(color = accent?.surface ?: MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent?.ink ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

enum class TaskRowEmphasis { NORMAL, ATTENTION }

private val CaptureDraftSaver = listSaver<TaskCaptureDraft, String>(
    save = { listOf(it.id, it.text, it.stage.name, it.dueDate?.toString().orEmpty(), it.scheduledForDate?.toString().orEmpty(),
        it.duration.toString(), it.category.name, it.priority.name, it.isCustomDuration.toString(), it.customDurationText,
        it.categorySource.name, it.prioritySource.name) },
    restore = { values ->
        runCatching {
            TaskCaptureDraft(values[0], values[1], TaskCaptureStage.CAPTURE, values[3].takeIf(String::isNotEmpty)?.let(LocalDate::parse),
                values[4].takeIf(String::isNotEmpty)?.let(LocalDate::parse), values[5].toInt(), TaskCategory.valueOf(values[6]),
                TaskPriority.valueOf(values[7]), values[8].toBoolean(), values[9],
                values.getOrNull(10)?.let(com.swan1127.repland.domain.model.TaskInputSource::valueOf)
                    ?: com.swan1127.repland.domain.model.TaskInputSource.LEGACY_UNVERIFIED,
                values.getOrNull(11)?.let(com.swan1127.repland.domain.model.TaskInputSource::valueOf)
                    ?: com.swan1127.repland.domain.model.TaskInputSource.LEGACY_UNVERIFIED)
        }.getOrElse { TaskCaptureDraft() }
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskCaptureSheet(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
    onVoice: (() -> Unit)? = null,
    draft: TaskCaptureDraft? = null,
    onDraftChange: (TaskCaptureDraft) -> Unit = {},
    saving: Boolean = false,
    saveError: String? = null,
    loadFailed: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    var localDraft by rememberSaveable(stateSaver = CaptureDraftSaver) { mutableStateOf(TaskCaptureDraft(text = initialText)) }
    val current = draft ?: localDraft
    val imeBridge = remember { CaptureImeBridge() }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    var exitApproved by remember { mutableStateOf(false) }
    fun update(value: TaskCaptureDraft) { if (!saving) { localDraft = value; onDraftChange(value) } }
    LaunchedEffect(initialText) {
        if (initialText.isNotBlank() && initialText != current.text) update(current.copy(text = initialText))
    }
    // 退出本身就是意图：有未保存内容就问一次，确认即整体放弃，不留「保留草稿」的暗道。
    val dirty = current.copy(stage = TaskCaptureStage.CAPTURE) != TaskCaptureDraft(id = current.id)
    val latestSaving by rememberUpdatedState(saving)
    val latestDirty by rememberUpdatedState(dirty)
    fun finishExit() {
        exitApproved = true
        imeBridge.hide()
        onDiscard?.invoke()
        onDismiss()
    }
    fun requestExit() {
        if (saving) return
        if (dirty) exitConfirm = true else finishExit()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                latestSaving -> false
                imeBridge.visible -> { imeBridge.hide(); false }
                latestDirty && !exitApproved -> { exitConfirm = true; false }
                else -> true
            }
        })

    fun save(value: TaskCaptureDraft = current) {
        if (saving || value.text.isBlank() || !value.durationIsValid) return
        imeBridge.hide()
        if (value != current) update(value)
        onSave(value.toTaskDraft())
    }

    ModalBottomSheet(
        modifier = Modifier.testTag("task-capture-sheet"),
        // Material 3 1.3.0 invokes this after a Back-triggered hide even when vetoed.
        onDismissRequest = { if (!saving && !sheetState.isVisible) finishExit() },
        sheetState = sheetState,
        // 1.3.0's overlay Back callback bypasses confirmValueChange via hide().
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        val sheetFocus = LocalFocusManager.current
        val sheetKeyboard = LocalSoftwareKeyboardController.current
        val sheetImeVisible = WindowInsets.isImeVisible
        BackHandler(enabled = !exitConfirm) {
            if (!saving) {
                if (sheetImeVisible) { sheetFocus.clearFocus(force = true); sheetKeyboard?.hide() }
                else requestExit()
            }
        }
        SideEffect {
            // Read controllers/insets from the dialog, not the underlying Activity window.
            imeBridge.visible = sheetImeVisible
            // Leaving an input must clear even a transient dialog focus path. In Compose
            // 1.7, non-forced custom-exit traversal can see a parent whose child detached.
            imeBridge.hide = { sheetFocus.clearFocus(force = true); sheetKeyboard?.hide() }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactHeight = maxHeight < 480.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .then(if (compactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("新任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { requestExit() }, enabled = !saving, modifier = Modifier.testTag("task-capture-close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
            if (loadFailed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("草稿读取失败，输入不受影响。", modifier = Modifier.weight(1f).testTag("task-capture-load-error"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    onRetryLoad?.let { retry ->
                        TextButton(onClick = retry, enabled = !saving, modifier = Modifier.testTag("task-capture-retry-load")) { Text("重试读取") }
                    }
                }
            }
            CaptureInputStep(
                draft = current,
                onChange = ::update,
                onVoice = onVoice?.let { action -> { imeBridge.hide(); action() } },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().then(if (compactHeight) Modifier else
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())),
            )
            saveError?.let { Text(it, modifier = Modifier.testTag("task-capture-error"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(onClick = { save() }, enabled = current.text.isNotBlank() && current.durationIsValid && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-capture-save-inbox")) {
                Text(if (saving) "正在保存…" else if (current.scheduledForDate == LocalDate.now()) "保存到今天" else "保存任务")
            }
        }
        }
    }
    if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, title = { Text("退出编辑？") },
        text = { Text("未保存的内容将消失。") },
        confirmButton = { TextButton(onClick = { exitConfirm = false; finishExit() },
            modifier = Modifier.testTag("task-capture-confirm-exit")) { Text("退出并放弃") } },
        dismissButton = { TextButton(onClick = { exitConfirm = false },
            modifier = Modifier.testTag("task-capture-continue-edit")) { Text("继续编辑") } })
}

/** Event-only bridge across the ModalBottomSheet's separate window/composition. */
private class CaptureImeBridge {
    var visible: Boolean = false
    var hide: () -> Unit = {}
}

@Composable
private fun CaptureInputStep(
    draft: TaskCaptureDraft,
    onChange: (TaskCaptureDraft) -> Unit,
    onVoice: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var showSchedulePicker by rememberSaveable { mutableStateOf(false) }
    var showDuePicker by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.text,
            enabled = enabled,
            onValueChange = { onChange(draft.copy(text = it)) },
            modifier = Modifier.fillMaxWidth().testTag("task-capture-input"),
            placeholder = { Text("想做的事写下来，比如：周五前交数据结构实验报告") },
            label = { Text("任务内容") },
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (draft.text.isNotEmpty()) {
                    IconButton(onClick = { onChange(draft.copy(text = "")) }, enabled = enabled,
                        modifier = Modifier.testTag("task-capture-clear")) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空内容")
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureDropdown("类别", draft.category.captureLabel(), "task-capture-category", enabled, Modifier.weight(1f)) { dismiss ->
                TaskCategory.entries.forEach { value ->
                    CaptureMenuItem(value.captureLabel(), "task-capture-category-${value.name}", value == draft.category) {
                        onChange(draft.copy(category = value,
                            categorySource = if (value == TaskCategory.UNSPECIFIED) TaskInputSource.UNKNOWN else TaskInputSource.USER_INPUT))
                        dismiss()
                    }
                }
            }
            CaptureDropdown("优先级", draft.priority.captureLabel(), "task-capture-priority", enabled, Modifier.weight(1f)) { dismiss ->
                TaskPriority.entries.forEach { value ->
                    CaptureMenuItem(value.captureLabel(), "task-capture-priority-${value.name}", value == draft.priority) {
                        onChange(draft.copy(priority = value,
                            prioritySource = if (value == TaskPriority.UNSPECIFIED) TaskInputSource.UNKNOWN else TaskInputSource.USER_INPUT))
                        dismiss()
                    }
                }
            }
            onVoice?.let { voice ->
                IconButton(onClick = voice, enabled = enabled, modifier = Modifier.testTag("voice-capture")) {
                    Icon(PlannerIcons.Voice, contentDescription = "语音输入", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureDropdown("计划", draft.scheduledForDate?.let(::taskDateLabel) ?: "未排期", "task-capture-date", enabled, Modifier.weight(1f)) { dismiss ->
                CaptureDateMenuItems(selected = draft.scheduledForDate, today = today, tagPrefix = "task-capture-date",
                    onPick = { onChange(draft.copy(scheduledForDate = it)); dismiss() },
                    onCustom = { showSchedulePicker = true; dismiss() },
                    onClear = { onChange(draft.copy(scheduledForDate = null)); dismiss() })
            }
            CaptureDropdown("时长", durationLabel(draft), "task-capture-duration", enabled, Modifier.weight(1f)) { dismiss ->
                listOf(15, 30, 60, 90).forEach { minute ->
                    CaptureMenuItem("$minute 分钟", "task-capture-duration-$minute", !draft.isCustomDuration && draft.duration == minute) {
                        onChange(draft.copy(isCustomDuration = false, duration = minute)); dismiss()
                    }
                }
                CaptureMenuItem("不确定", "task-capture-duration-unknown", !draft.isCustomDuration && draft.duration == 0) {
                    onChange(draft.copy(isCustomDuration = false, duration = 0)); dismiss()
                }
                CaptureMenuItem("自定义…", "task-capture-duration-custom", draft.isCustomDuration) {
                    onChange(draft.copy(isCustomDuration = true)); dismiss()
                }
            }
            CaptureDropdown("截止", draft.dueDate?.let(::taskDateLabel) ?: "无", "task-capture-deadline", enabled, Modifier.weight(1f)) { dismiss ->
                CaptureDateMenuItems(selected = draft.dueDate, today = today, tagPrefix = "task-capture-deadline",
                    onPick = { onChange(draft.copy(dueDate = it)); dismiss() },
                    onCustom = { showDuePicker = true; dismiss() },
                    onClear = { onChange(draft.copy(dueDate = null)); dismiss() })
            }
        }
        if (draft.isCustomDuration) {
            OutlinedTextField(
                value = draft.customDurationText,
                enabled = enabled,
                onValueChange = { onChange(draft.copy(customDurationText = it)) },
                modifier = Modifier.fillMaxWidth().testTag("task-capture-duration-custom-input"),
                label = { Text("自定义时长（分钟）") },
                placeholder = { Text("1–1440") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = draft.customDurationText.isNotBlank() && !draft.durationIsValid,
                supportingText = {
                    if (draft.customDurationText.isBlank()) Text("例如 45 分钟")
                    else if (!draft.durationIsValid) Text("请输入 1–1440 的整数分钟")
                },
            )
        }
        if (!draft.durationIsValid) {
            Text("自定义时长尚未有效，请修改，或在时长里选“不确定”。",
                modifier = Modifier.testTag("task-capture-duration-error"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showSchedulePicker) {
        TaskDatePickerDialog(initialDate = draft.scheduledForDate, onDismiss = { showSchedulePicker = false },
            onDateSelected = { showSchedulePicker = false; onChange(draft.copy(scheduledForDate = it)) }, title = "选择计划日期")
    }
    if (showDuePicker) {
        TaskDatePickerDialog(initialDate = draft.dueDate, onDismiss = { showDuePicker = false },
            onDateSelected = { showDuePicker = false; onChange(draft.copy(dueDate = it)) }, title = "选择截止日期")
    }
}

@Composable
private fun CaptureDropdown(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }.testTag(tag),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

@Composable
private fun CaptureMenuItem(text: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
        } else null,
    )
}

@Composable
private fun CaptureDateMenuItems(
    selected: LocalDate?,
    today: LocalDate,
    tagPrefix: String,
    onPick: (LocalDate) -> Unit,
    onCustom: () -> Unit,
    onClear: () -> Unit,
) {
    CaptureMenuItem("今天", "$tagPrefix-today", selected == today) { onPick(today) }
    CaptureMenuItem("明天", "$tagPrefix-tomorrow", selected == today.plusDays(1)) { onPick(today.plusDays(1)) }
    CaptureMenuItem("一周后", "$tagPrefix-week", selected == today.plusDays(7)) { onPick(today.plusDays(7)) }
    CaptureMenuItem("选择其他日期", "$tagPrefix-custom", false, onCustom)
    if (selected != null) CaptureMenuItem("清除日期", "$tagPrefix-clear", false, onClear)
}

private fun durationLabel(draft: TaskCaptureDraft): String = when {
    draft.isCustomDuration -> draft.selectedDuration?.let { "$it 分钟" } ?: "待填写"
    draft.duration > 0 -> "${draft.duration} 分钟"
    else -> "不确定"
}

private fun Task.summaryLabel(): String = when {
    status == TaskStatus.IN_PROGRESS -> "正在进行"
    status == TaskStatus.COMPLETED -> "已完成"
    status == TaskStatus.POSTPONED -> "已延期"
    dueDate?.isBefore(LocalDate.now()) == true && status.isActive -> "已逾期 · ${taskDateLabel(dueDate)}"
    scheduledForDate?.isBefore(LocalDate.now()) == true && status.isActive -> "待重新安排 · ${taskDateLabel(scheduledForDate)}"
    dueDate != null && scheduledForDate != null && totalDurationMinutes != null -> "计划 ${taskDateLabel(scheduledForDate)} · 截止 ${taskDateLabel(dueDate)} · $totalDurationMinutes 分钟"
    dueDate != null && scheduledForDate != null -> "计划 ${taskDateLabel(scheduledForDate)} · 截止 ${taskDateLabel(dueDate)}"
    scheduledForDate != null && totalDurationMinutes != null -> "计划 ${taskDateLabel(scheduledForDate)} · $totalDurationMinutes 分钟"
    scheduledForDate != null -> "计划 ${taskDateLabel(scheduledForDate)}"
    dueDate != null && totalDurationMinutes != null -> "截止 ${taskDateLabel(dueDate)} · $totalDurationMinutes 分钟"
    dueDate != null -> "截止 ${taskDateLabel(dueDate)}"
    totalDurationMinutes != null -> "待安排 · $totalDurationMinutes 分钟"
    else -> "待安排"
}

private fun TaskCategory.captureLabel(): String = when (this) {
    TaskCategory.UNSPECIFIED -> "未分类"
    TaskCategory.COURSE -> "课程"
    TaskCategory.EXTRACURRICULAR -> "课外"
    TaskCategory.OFFICE -> "事务"
    TaskCategory.LEISURE -> "生活"
}

private fun TaskPriority.captureLabel(): String = when (this) {
    TaskPriority.UNSPECIFIED -> "未设置"
    TaskPriority.REQUIRED -> "不可避免"
    TaskPriority.HIGH -> "高"
    TaskPriority.MEDIUM -> "中"
    TaskPriority.LOW -> "低"
}
