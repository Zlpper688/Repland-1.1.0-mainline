package com.swan1127.repland.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.swan1127.repland.ui.components.DesignButton as Button
import com.swan1127.repland.ui.components.DesignClickableSurface as ClickableSurface
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.DesignOutlinedButton as OutlinedButton
import com.swan1127.repland.ui.components.DesignSwitch as Switch
import com.swan1127.repland.ui.components.DesignTextButton
import com.swan1127.repland.ui.components.LabeledTimeWheel
import com.swan1127.repland.ui.components.TimeWheelSteps
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import com.swan1127.repland.ui.components.rememberLandscapeSurface
import com.swan1127.repland.ui.components.DesignDialog
import com.swan1127.repland.ui.components.TaskDatePickerDialog
import com.swan1127.repland.ui.components.taskDateLabel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import com.swan1127.repland.ui.theme.LocalReducedMotion
import com.swan1127.repland.ui.theme.replandAccent
import com.swan1127.repland.ui.theme.taskAccentIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import com.swan1127.repland.domain.model.ScheduleArrangement
import com.swan1127.repland.domain.model.ScheduleTimeline
import com.swan1127.repland.domain.model.ChatDayPlanner
import com.swan1127.repland.domain.model.EngagementMode
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.TimeBlockValidator
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import com.swan1127.repland.domain.model.TimelinePhase
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskPlacementPolicy
import com.swan1127.repland.domain.model.childrenByContainer
import com.swan1127.repland.domain.model.parallelUnits
import com.swan1127.repland.domain.model.rootEntries
import java.time.LocalDateTime
import java.time.LocalDate
import kotlinx.coroutines.delay

private val CourseAccent = Color(0xFF4F65A6)
private val RestAccent = Color(0xFF7C7C9C)
private val CommitmentAccent = Color(0xFFA66B42)
/** 第二课堂（比赛队伍任务）：金色，和个人任务蓝、固定安排棕分开。 */
private val SecondClassroomAccent = Color(0xFF9A7420)

/** 物理合并的感应半径：48dp 内"张开插槽"，72dp 外才收回，避免边界抖动。 */
private val ArmRadius = 48.dp
private val ReleaseRadius = 72.dp

data class TimelineEventObject(
    val id: String,
    val title: String,
    val durationMinutes: Int?,
    val category: TaskCategory = TaskCategory.EXTRACURRICULAR,
    /** 这件事在别的日子已有的排期。不带着它，「安排已有事项」就看不出自己是在复制而不是在挪动。 */
    val alreadyPlacedOn: List<LocalDate> = emptyList(),
    /** 别处已经排进去的分钟数；跟总时长一比就知道这次是补上剩下的，还是压了超额的一段。 */
    val alreadyPlacedMinutes: Int = 0,
) {
    /** 还没排够的那部分。没填时长、或别处已经排满时没有值——这时只能让人自己定。 */
    val remainingMinutes: Int? get() = durationMinutes?.let { (it - alreadyPlacedMinutes).takeIf { left -> left > 0 } }
}

/**
 * 日视图：先是事情，做这件事的同时还能做别的事——
 * 拖进某件事的框架里就成为它的下属事件，拖出来回到顺序队列；
 * 放到中间空白处，下面的安排自动往后延迟，时间排满了就插不进去。
 * 互相重叠的安排渲染成一块合轨大块，块内各显示各的时间。
 */
@Composable
fun TimelineDashboard(
    entries: List<TimelineEntry>,
    mode: EngagementMode,
    onOpenEntry: (String) -> Unit,
    onEditEntry: (TimelineEntry) -> Unit = {},
    eventObjects: List<TimelineEventObject> = emptyList(),
    onPlaceEvent: (TimelineEventObject, String, Int, Int) -> Unit = { _, _, _, _ -> },
    scheduleDate: LocalDate = entries.firstOrNull()?.date ?: LocalDate.now(),
    onFocusStarted: (String) -> Unit = {},
    onRemovePlacement: (String) -> Unit = {},
    onUpdateEntryTime: (TimelineEntry, Int, Int, LocalDate?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    onDropEntry: (TimelineEntry, Int, String?) -> Unit = { _, _, _ -> },
    onToggleEntryReminder: (TimelineEntry, Boolean) -> Unit = { _, _ -> },
    reminderNotice: String? = null,
    writeBusy: Boolean = false,
    writeError: String? = null,
    clockNow: LocalDateTime? = null,
    scope: String = "day",
    onScopeChange: (String) -> Unit = {},
) {
    var now by remember { mutableStateOf(clockNow ?: LocalDateTime.now()) }
    var showEventLibrary by rememberSaveable { mutableStateOf(false) }
    var pendingPlacement by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    LaunchedEffect(entries, pendingPlacement) {
        pendingPlacement?.let { requested ->
            if (entries.any { it.taskId == requested.first && it.trackId == requested.second && it.startMinute == requested.third }) {
                showEventLibrary = false
                pendingPlacement = null
            }
        }
    }
    // 旋转/进程回收后仍停在用户刚才看的详情上：正在调时间的人不该被合上浮层、连轮盘档位一起丢掉。
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedEntry = entries.firstOrNull { it.id == selectedEntryId }
    val isToday = scheduleDate == now.toLocalDate()
    // 「安排已有事项」的建议起点：翻到别的日子时不该用现在的时刻当下界，
    // 否则晚上 10 点安排明天，会一上来就建议 22:00。
    val arrangeFloor = if (isToday) TaskPlacementPolicy.floorMinuteFor(now) else 8 * 60
    LaunchedEffect(clockNow) {
        if (clockNow != null) { now = clockNow; return@LaunchedEffect }
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    val tryUpdateEntryTime: (TimelineEntry, Int, Int, LocalDate?, () -> Unit) -> Boolean = { entry, startMinute, endMinute, date, saved ->
        val validRange = startMinute in 0 until 1_440 && endMinute in 1..1_440 && startMinute < endMinute
        // 只在原地改钟点时按这一天的格子判重叠。换天时今天的格子挡不着明天的安排，跨日冲突由
        // 仓储按目标日子校验（那里看得见全部安排），否则用户会被自己没在看的每一天误拒。
        val conflicts = (date == null || date == entry.date) && entries.any { other ->
            other.id != entry.id && (entry.kind != TimelineKind.TASK || other.kind != TimelineKind.TASK) &&
                startMinute < other.endMinute && endMinute > other.startMinute
        }
        if (validRange && !conflicts && !writeBusy) onUpdateEntryTime(entry, startMinute, endMinute, date, saved)
        validRange && !conflicts && !writeBusy
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty()) {
            DayTimelineGrid(
                entries = emptyList(),
                now = now,
                onClick = {},
                onDrop = { _, _, _ -> },
                scope = scope,
                onScopeChange = onScopeChange,
                onArrange = { showEventLibrary = true },
                isToday = isToday,
            )
        } else {
            val onEntryClick: (TimelineEntry) -> Unit = { entry ->
                // A short tap always opens a stable detail sheet. Keeping actions out of the
                // scrolling canvas avoids an expanded card covering another time block.
                selectedEntryId = entry.id
                onOpenEntry(entry.id)
            }
            DayTimelineGrid(
                entries = entries,
                now = now,
                onClick = onEntryClick,
                onDrop = onDropEntry,
                scope = scope,
                onScopeChange = onScopeChange,
                onArrange = { showEventLibrary = true },
                isToday = isToday,
            )
            if (showEventLibrary) {
                EventLibrarySheet(
                    events = eventObjects,
                    occupiedEntries = entries,
                    floorMinute = arrangeFloor,
                    onDismiss = { showEventLibrary = false },
                    onPlace = { event, trackId, startMinute, endMinute ->
                        pendingPlacement = Triple(event.id, trackId, startMinute)
                        onPlaceEvent(event, trackId, startMinute, endMinute)
                    },
                )
            }
        }
        if (entries.isEmpty() && showEventLibrary) {
            EventLibrarySheet(
                events = eventObjects,
                occupiedEntries = entries,
                floorMinute = arrangeFloor,
                onDismiss = { showEventLibrary = false },
                onPlace = { event, trackId, startMinute, endMinute ->
                    pendingPlacement = Triple(event.id, trackId, startMinute)
                    onPlaceEvent(event, trackId, startMinute, endMinute)
                },
            )
        }
        selectedEntry?.let { entry ->
            TimelineEntryDetailSheet(
                entry = entry,
                now = now,
                onDismiss = { selectedEntryId = null },
                onEdit = {
                    selectedEntryId = null
                    onEditEntry(entry)
                },
                onToggleReminder = if (entry.id.startsWith("segment:") ||
                    entry.id.startsWith("weekly:") || entry.id.startsWith("override:")
                ) {
                    { enabled -> onToggleEntryReminder(entry, enabled) }
                } else {
                    null
                },
                onFocus = entry.taskId?.takeIf { !entry.taskClosed && entry.id.startsWith("segment:") }?.let {
                    {
                        selectedEntryId = null
                        onFocusStarted(entry.id.removePrefix("segment:"))
                    }
                },
                onUpdateTime = { startMinute, endMinute, date, saved ->
                    tryUpdateEntryTime(entry, startMinute, endMinute, date) { selectedEntryId = null; saved() }
                },
                writeBusy = writeBusy,
                writeError = writeError,
                reminderNotice = reminderNotice,
                onRemovePlacement = entry.id.removePrefix("segment:")
                    .takeIf { entry.id.startsWith("segment:") }
                    ?.let { segmentId ->
                        {
                            selectedEntryId = null
                            onRemovePlacement(segmentId)
                        }
                    },
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventLibrarySheet(
    events: List<TimelineEventObject>,
    occupiedEntries: List<TimelineEntry>,
    floorMinute: Int,
    onDismiss: () -> Unit,
    onPlace: (TimelineEventObject, String, Int, Int) -> Unit,
) {
    // 按 id 记住正在安排的事项：重建后弹窗和里面的轮盘都能一起回来。
    var placingEventId by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("安排已有事项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (events.isEmpty()) {
                Text("没有待安排的事项。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // 整批建议一次算完：每行领到互不相同的空闲档，点一行就排一行，
                // 不需要为每件事项再走一遍轮盘弹窗。
                // 没填过长度的事项除外：凭空造的 30 分钟会把「不知道这事多大」
                // 伪装成用户已经拍板过的安排，这类事项必须自己定时间和长度。
                // 建议的长度取「还没排够的那部分」：别处排过的事照总长再压一段就是超额的一段。
                val taken = mutableListOf<Pair<Int, Int>>()
                val suggestions = events.mapNotNull { event ->
                    val duration = event.remainingMinutes?.let { TaskPlacementPolicy.durationForTask(it) } ?: return@mapNotNull null
                    TaskPlacementPolicy.nextFreeMinute(occupiedEntries, floorMinute, duration, taken)?.let { start ->
                        val slot = start to (start + duration).coerceAtMost(1_440)
                        taken += slot
                        event.id to slot
                    }
                }.toMap()
                events.forEach { event ->
                    val slot = suggestions[event.id]
                    val duration = event.remainingMinutes?.let { TaskPlacementPolicy.durationForTask(it) }
                    val accent = replandAccent(taskAccentIndex(event.category))
                    ClickableSurface(
                        onClick = {
                            if (slot != null) {
                                onPlace(event, "focus", slot.first, slot.second)
                            } else {
                                placingEventId = event.id
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("event-object-${event.id}"),
                        color = accent.surface,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    slot?.let {
                                        "${TimeBlockValidator.formatTime(it.first)}–${TimeBlockValidator.formatTime(it.second)} · 点一下排入"
                                    } ?: when {
                                        // 别处早排满了 ≠ 没填时长，也 ≠ 这天排满：这里给的是「再加一段」的决定权，不是代填。
                                        event.remainingMinutes == null && event.durationMinutes != null ->
                                            "已排 ${event.alreadyPlacedMinutes}/${event.durationMinutes} 分钟 · 点一下再加一段"
                                        duration == null -> "点一下定时间和长度"
                                        // 醒来时间里塞不下这件事 ≠ 这天排满：白天已经过完，剩下的是该睡觉的钟点。
                                        floorMinute + duration > ChatDayPlanner.SLEEP_START_MINUTE ->
                                            "白天不够 · 点一下挑时间"
                                        else -> "这天排满了 · 点一下挑时间"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accent.ink,
                                )
                                if (event.alreadyPlacedOn.isNotEmpty()) {
                                    // 这一列只按「今天有没有排过」过滤，昨天排过一半的事在这儿看着像全新事项。
                                    // 不说清日期，用户点下去会以为自己在把它挪过来。
                                    Text(
                                        "已排在 ${event.alreadyPlacedOn.joinToString("、") { taskDateLabel(it) }} · 点一下今天再加一段",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(event.category.shortLabel(), style = MaterialTheme.typography.labelSmall, color = accent.ink)
                        }
                    }
                }
            }
        }
    }
    events.firstOrNull { it.id == placingEventId }?.let { event ->
        val duration = TaskPlacementPolicy.durationForTask(event.remainingMinutes) ?: 30
        // 白天塞不下时轮盘也不能停在 23 点以后：默认值一压进睡眠窗口，
        // 用户看到的是自己「选」了半夜，而实际上是工具替他挑的。
        val suggested = TaskPlacementPolicy.nextFreeMinute(occupiedEntries, floorMinute, duration)
            ?: floorMinute.coerceAtMost(ChatDayPlanner.SLEEP_START_MINUTE - duration)
        TimelineTimeEditorDialog(
            title = "安排 ${event.title}",
            initialStartMinute = suggested,
            initialEndMinute = suggested + duration,
            tagKey = event.id,
            busy = false,
            saveError = null,
            onDismiss = { placingEventId = null },
            onSave = { startMinute, endMinute, _, saved ->
                onPlace(event, "focus", startMinute, endMinute)
                placingEventId = null
                saved()
                true
            },
        )
    }
}

private data class DragState(val entryId: String, val deltaPx: Float, val previewMinute: Int)

/**
 * 当天值得展开的那几个小时，至少 5 小时，返回 [起, 止) 的整点。
 * 今天以现在为锚（过去的安排只留最近两小时内结束的）；翻到别的日期时，锚换成那一天的安排。
 */
private fun timelineWindow(roots: List<TimelineEntry>, nowMinute: Int, today: Boolean): Pair<Int, Int> {
    val now = nowMinute.coerceIn(0, 1_439)
    val anchors = if (today) {
        listOf(now - 60, now + 120) +
            roots.filter { it.endMinute > now - 120 }.map { it.startMinute } +
            roots.filter { it.endMinute > now }.map { it.endMinute }
    } else {
        roots.flatMap { listOf(it.startMinute, it.endMinute) }.ifEmpty { listOf(8 * 60, 12 * 60) }
    }
    var startHour = anchors.min().coerceAtLeast(0) / 60
    var endHour = (anchors.max().coerceAtMost(1_440) + 59) / 60
    while (endHour - startHour < 5) {
        when {
            endHour < 24 -> endHour += 1
            startHour > 0 -> startHour -= 1
            else -> break
        }
    }
    return startHour to endHour
}

private fun Modifier.dashedBorder(color: Color, cornerRadius: Dp): Modifier = drawBehind {
    val radius = cornerRadius.toPx()
    val path = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
    drawPath(
        path,
        color = color,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
    )
}

/** 一组互斥选项收进一个小模块：一枚胶囊内选完，不再平铺成几颗按钮。 */
@Composable
fun ScheduleSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String? = null,
    accentIndex: Int = 0,
) {
    val accent = replandAccent(accentIndex)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                ClickableSurface(
                    onClick = { if (!isSelected) onSelect(value) },
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        // 现在是哪一格不能只靠颜色说：读屏念不出「日」被选中，
                        // 这格也就在无障碍里根本不存在。选中状态要进语义树。
                        .semantics { this[SemanticsProperties.Selected] = isSelected }
                        .then(tagPrefix?.let { Modifier.testTag("$it-$value") } ?: Modifier),
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) accent.surface else Color.Transparent,
                    pressedScale = .96f,
                ) {
                    Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) accent.ink else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 日/周/月 三个入口收进一个分段模块，不再单独占一行。 */
@Composable
fun ScheduleScopeSwitch(
    scope: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScheduleSegmented(
        options = listOf("day" to "日", "week" to "周", "month" to "月"),
        selected = scope,
        onSelect = onChange,
        modifier = modifier,
        tagPrefix = "schedule-scope",
    )
}

@Composable
private fun DayTimelineGrid(
    entries: List<TimelineEntry>,
    now: LocalDateTime,
    onClick: (TimelineEntry) -> Unit,
    onDrop: (TimelineEntry, Int, String?) -> Unit,
    scope: String,
    onScopeChange: (String) -> Unit,
    onArrange: () -> Unit,
    isToday: Boolean,
) {
    val reducedMotion = LocalReducedMotion.current
    val animDuration = if (reducedMotion) 0 else 220
    val roots = rootEntries(entries).sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::endMinute, TimelineEntry::id))
    val childrenByParent = childrenByContainer(entries)
    val hourHeight = 48.dp
    val totalHeight = hourHeight * 24
    val density = LocalDensity.current
    val minutePerPixel = with(density) { 60f / hourHeight.toPx() }

    // 合轨大块：互相重叠的根级事件渲染成一块；顺序事件各自成块。
    val units = remember(entries) { parallelUnits(entries) }

    // 空着的清晨不再占版面：一上来就停在「当前时刻 + 今天真正有安排的时段」，需要时仍可往上翻。
    val nowMinute = now.hour * 60 + now.minute
    val (windowStart, windowEnd) = timelineWindow(roots, nowMinute, isToday)
    // 卡片最多露六小时：再长的时段也能一屏看完，剩下的靠上滑。
    val bodyHeight = minOf(hourHeight * (windowEnd - windowStart), hourHeight * 6)
    val scrollState = rememberScrollState(initial = with(density) { (hourHeight * windowStart).roundToPx() })

    var drag by remember(entries) { mutableStateOf<DragState?>(null) }
    var dragToken by remember { mutableStateOf(0) }
    var endedAtToken by remember { mutableStateOf(0) }
    // 松手后保留预览位移，等新数据到达（remember(entries) 重置）或 700ms 兜底清除，
    // 否则块会先弹回原位、再跳到新位置，闪一下。
    LaunchedEffect(endedAtToken) {
        if (endedAtToken != 0) {
            val token = endedAtToken
            delay(700)
            if (dragToken == token) drag = null
            endedAtToken = 0
        }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) { delay(3_500); hint = null }
    }
    val draggedEntry = drag?.let { state -> entries.firstOrNull { it.id == state.entryId } }
    val draggedDuration = draggedEntry?.let { it.endMinute - it.startMinute } ?: 0
    val dropMinute = drag?.previewMinute

    // 物理合并：接近任务块 → 它像容器一样张开一个插槽；离开 → 收回。48/72dp 迟滞防抖。
    var armedId by remember(entries) { mutableStateOf<String?>(null) }
    val armProgress = remember { Animatable(0f) }
    LaunchedEffect(armedId, reducedMotion) {
        val target = if (armedId != null) 1f else 0f
        if (reducedMotion) armProgress.snapTo(target)
        else armProgress.animateTo(
            target,
            animationSpec = if (armedId != null) spring(dampingRatio = 0.65f, stiffness = 360f) else tween(150),
        )
    }
    var shakeTick by remember { mutableStateOf(0) }
    var lastShakeTarget by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeTick, reducedMotion) {
        if (shakeTick == 0 || reducedMotion) return@LaunchedEffect
        shakeOffset.snapTo(0f)
        repeat(3) {
            shakeOffset.animateTo(7f, tween(50))
            shakeOffset.animateTo(-7f, tween(50))
        }
        shakeOffset.animateTo(0f, tween(40))
    }

    fun unitGapMinutes(unit: List<TimelineEntry>, draggedSpanMinutes: Int, preview: Int): Float {
        val dragStart = preview.toFloat()
        val dragEnd = (preview + draggedSpanMinutes).toFloat()
        val start = unit.minOf { it.startMinute }.toFloat()
        val end = unit.maxOf { it.endMinute }.toFloat()
        return when {
            dragEnd <= start -> start - dragEnd
            end <= dragStart -> dragStart - end
            else -> 0f
        }
    }

    fun resolveArmed(previewMinute: Int, current: String?, dragged: TimelineEntry?): String? {
        if (dragged == null) return null
        val pxPerMinute = with(density) { hourHeight.toPx() / 60f }
        val armPx = with(density) { ArmRadius.toPx() }
        val releasePx = with(density) { ReleaseRadius.toPx() }
        var bestUnit: List<TimelineEntry>? = null
        var bestGap = Float.MAX_VALUE
        for (unit in units) {
            if (unit.any { it.id == dragged.id }) continue
            val gap = unitGapMinutes(unit, dragged.endMinute - dragged.startMinute, previewMinute) * pxPerMinute
            if (gap < bestGap) { bestGap = gap; bestUnit = unit }
        }
        val nearest = bestUnit ?: return null
        val nearestId = nearest.first().id
        val armable = nearest.size == 1 && nearest.first().kind == TimelineKind.TASK
        // 不可并入的块（课程/休息/固定事项/大块）接近时摇一下表示拒绝。
        if (!armable && bestGap <= armPx && lastShakeTarget != nearestId) {
            lastShakeTarget = nearestId
            shakeTick++
        }
        if (bestGap > releasePx) lastShakeTarget = null
        return when {
            current != null && nearestId == current && bestGap <= releasePx -> current
            armable && bestGap <= armPx -> nearestId
            else -> null
        }
    }

    // 拖到空白 → 下面的安排像水一样让路（真实后延链的预览）；接近任务块 → 进入合并模式。
    val preview: ScheduleArrangement.ShiftPreview = if (drag != null && draggedEntry != null && dropMinute != null) {
        val shims = roots.map { root ->
            PlannedSegment(id = root.id, taskId = "preview", date = root.date, startMinute = root.startMinute, endMinute = root.endMinute)
        }
        val candidate = PlannedSegment(
            id = draggedEntry.id, taskId = "preview", date = draggedEntry.date,
            startMinute = dropMinute, endMinute = dropMinute + draggedDuration,
        )
        ScheduleArrangement.previewShifts(candidate, shims, movedId = draggedEntry.id)
    } else ScheduleArrangement.ShiftPreview(emptyMap(), valid = true)
    val displayDeltas = if (armedId != null) emptyMap() else preview.deltas

    val armedTarget = armedId?.let { id -> units.firstOrNull { it.size == 1 && it.first().id == id }?.first() }
    val nestFits = armedTarget?.let { it.endMinute - it.startMinute >= draggedDuration } ?: true
    val nestPreviewMinute = if (armedTarget != null && dropMinute != null) {
        val clamped = dropMinute.coerceIn(armedTarget.startMinute, (armedTarget.endMinute - draggedDuration).coerceAtLeast(armedTarget.startMinute))
        (clamped / 15) * 15
    } else null
    val slotHeight = with(density) {
        (draggedDuration * hourHeight.toPx() / 60f * armProgress.value).toDp()
    }
    fun displayStart(entry: TimelineEntry): Int = entry.startMinute + (displayDeltas[entry.id] ?: 0)
    fun pushDown(entry: TimelineEntry): Dp =
        if (armedTarget != null && displayStart(entry) >= armedTarget.endMinute) slotHeight else 0.dp
    fun snapped(minute: Int) = ((minute / 15f).roundToInt() * 15).coerceIn(0, 1_440 - draggedDuration.coerceAtLeast(15))

    // 满课日的让位扫描：一节课 45 分钟只有 36dp，装不下一张带下属的卡，卡片会往下压住下一张。
    // 顶部只向下挪、绝不叠压；拖拽中由预览位移负责让位，两套不叠加。
    val stackedTops: List<Dp> = if (drag != null) emptyList() else run {
        val tops = ArrayList<Dp>(units.size)
        var floor = 0.dp
        for (unit in units) {
            val top = maxOf(hourHeight * (unit.minOf { it.startMinute } / 60f), floor)
            val height = if (unit.size == 1) {
                blockHeight(unit.first(), childrenByParent[unit.first().id]?.size ?: 0, hourHeight)
            } else {
                groupHeight(unit, unit.sumOf { childrenByParent[it.id]?.size ?: 0 }, hourHeight)
            }
            tops += top
            floor = top + height + 4.dp
        }
        tops
    }

    fun dropInto(entry: TimelineEntry, state: DragState) {
        // 写库后 entries 重建会重启手势检测器并重放 onDragCancel；同一手势只投放一次，
        // 否则第二次会带已失效的 segment id 走普通投放分支报“原安排已变化”。
        if (endedAtToken == dragToken) return
        android.util.Log.i("PlanDrop", "dropInto entry=${entry.id} preview=${state.previewMinute} armed=$armedId")
        endedAtToken = dragToken
        val nest = armedId?.let { id -> units.firstOrNull { it.size == 1 && it.first().id == id }?.first() }
        armedId = null
        lastShakeTarget = null
        val target = snapped(state.previewMinute)
        val duration = entry.endMinute - entry.startMinute
        when {
            nest != null && nest.endMinute - nest.startMinute >= duration ->
                onDrop(entry, target.coerceIn(nest.startMinute, (nest.endMinute - duration).coerceAtLeast(nest.startMinute)), nest.id)
            nest != null -> hint = "「${nest.title}」的这个时段放不下，试一处更空的时间。"
            !preview.valid -> hint = "今天的时间排满了，这段插不进去。"
            // 原地松手不算调整，也不触发写入。
            state.previewMinute == entry.startMinute -> Unit
            else -> onDrop(entry, target, null)
        }
    }

    // 这块纸和上面那叠卡、和窗外的天是同一个时辰的：白天是本色纸，入夜压成暖沙。
    Surface(color = rememberLandscapeSurface(MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScheduleScopeSwitch(scope = scope, onChange = onScopeChange)
                Spacer(Modifier.weight(1f))
                if (roots.isNotEmpty()) {
                    Text(
                        "${roots.size} 件事",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onArrange, modifier = Modifier.testTag("schedule-arrange-task")) {
                    Icon(Icons.Outlined.Add, "安排已有事项", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.fillMaxWidth().height(bodyHeight).verticalScroll(scrollState).testTag("schedule-day-scroll")) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(totalHeight).padding(vertical = 10.dp),
                ) {
                    val rulerWidth = 56.dp
                    val gridWidth = maxWidth - rulerWidth

                    // The ruler deliberately sits outside the scroller and stays visible while panning.
                    for (hour in 0..24) {
                        val y = hourHeight * hour
                        Text(
                            text = String.format("%02d:00", hour.coerceAtMost(23)),
                            modifier = Modifier
                                .offset(y = (y - 10.dp).coerceAtLeast(0.dp))
                                .width(rulerWidth)
                                .height(20.dp)
                                .padding(end = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .padding(start = rulerWidth)
                            .width(gridWidth)
                            .height(totalHeight),
                    ) {
                        for (hour in 0..24) {
                            Box(
                                Modifier
                                    .offset(y = hourHeight * hour)
                                    .width(gridWidth)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                            )
                        }
                        units.forEachIndexed { index, unit ->
                            val remaining = unit.filter { member -> member.id != draggedEntry?.id }
                            val sortedRemaining = remaining.sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::id))
                            val stackedTop = stackedTops.getOrNull(index)
                            if (unit.size == 1) {
                                val entry = unit.first()
                                val isDragged = drag?.entryId == entry.id
                                TimelineBlock(
                                    entry = entry,
                                    children = childrenByParent[entry.id].orEmpty(),
                                    now = now,
                                    width = gridWidth - 8.dp,
                                    y = stackedTop ?: hourHeight * (entry.startMinute / 60f),
                                    pushDown = pushDown(entry),
                                    hourHeight = hourHeight,
                                    accent = entryAccent(entry),
                                    isDragged = isDragged,
                                    dragDeltaPx = if (isDragged) drag?.deltaPx ?: 0f else 0f,
                                    previewStartMinute = if (isDragged) drag?.previewMinute else null,
                                    invalidDrop = isDragged && armedId == null && !preview.valid,
                                    isArmedTarget = armedTarget?.id == entry.id,
                                    nestFits = nestFits,
                                    slotHeight = slotHeight,
                                    ghostMinute = if (armedTarget?.id == entry.id) nestPreviewMinute else null,
                                    ghostTitle = draggedEntry?.title,
                                    shakePx = if (lastShakeTarget == entry.id) shakeOffset.value else 0f,
                                    zIndexValue = if (isDragged) 4f else if (armedTarget?.id == entry.id) 2f else if (childrenByParent.containsKey(entry.id)) 1.5f else 1f,
                                    onClick = { onClick(entry) },
                                    onChildClick = { onClick(it) },
                                    onDragStart = {
                                        dragToken += 1
                                        // 起步死区：还没真正拖动时不感应合并，原地拿起不惊动相邻块。
                                        armedId = null
                                        drag = DragState(entry.id, 0f, entry.startMinute)
                                    },
                                    onDragDelta = { dy ->
                                        val state = drag ?: return@TimelineBlock
                                        val nextPx = state.deltaPx + dy
                                        val previewMinute = (entry.startMinute + nextPx * minutePerPixel)
                                            .roundToInt()
                                            .coerceIn(0, 1_440 - (entry.endMinute - entry.startMinute))
                                        armedId = if (abs(nextPx) > 24f) resolveArmed(previewMinute, armedId, entry) else null
                                        drag = state.copy(deltaPx = nextPx, previewMinute = previewMinute)
                                    },
                                    onDragEnd = {
                                        val state = drag
                                        if (state != null && state.entryId == entry.id) dropInto(entry, state)
                                    },
                                )
                            } else if (sortedRemaining.isNotEmpty()) {
                                val front = sortedRemaining.first()
                                GroupBlock(
                                    members = sortedRemaining,
                                    childrenByParent = childrenByParent,
                                    now = now,
                                    width = gridWidth - 8.dp,
                                    y = stackedTop ?: hourHeight * (displayStart(front) / 60f),
                                    pushDown = pushDown(front),
                                    hourHeight = hourHeight,
                                    shakePx = if (lastShakeTarget == front.id) shakeOffset.value else 0f,
                                    zIndexValue = 1.5f,
                                    onClick = onClick,
                                    onChildClick = { onClick(it) },
                                    onDragStart = {
                                        dragToken += 1
                                        armedId = null
                                        drag = DragState(front.id, 0f, front.startMinute)
                                    },
                                    onDragDelta = { dy ->
                                        val state = drag ?: return@GroupBlock
                                        val dragged = entries.firstOrNull { it.id == state.entryId } ?: return@GroupBlock
                                        val nextPx = state.deltaPx + dy
                                        val previewMinute = (dragged.startMinute + nextPx * minutePerPixel)
                                            .roundToInt()
                                            .coerceIn(0, 1_440 - (dragged.endMinute - dragged.startMinute))
                                        armedId = if (abs(nextPx) > 24f) resolveArmed(previewMinute, armedId, dragged) else null
                                        drag = state.copy(deltaPx = nextPx, previewMinute = previewMinute)
                                    },
                                    onDragEnd = {
                                        val state = drag ?: return@GroupBlock
                                        val dragged = entries.firstOrNull { it.id == state.entryId } ?: return@GroupBlock
                                        dropInto(dragged, state)
                                    },
                                )
                            }
                        }
                        // 从合轨大块里拖出来的成员浮在网格之上；手势仍留在 GroupBlock 上。
                        if (draggedEntry != null && units.any { it.size > 1 && it.any { member -> member.id == draggedEntry.id } }) {
                            TimelineBlock(
                                entry = draggedEntry,
                                children = childrenByParent[draggedEntry.id].orEmpty(),
                                now = now,
                                width = gridWidth - 8.dp,
                                y = hourHeight * (draggedEntry.startMinute / 60f),
                                pushDown = 0.dp,
                                hourHeight = hourHeight,
                                accent = entryAccent(draggedEntry),
                                isDragged = true,
                                dragDeltaPx = drag?.deltaPx ?: 0f,
                                previewStartMinute = drag?.previewMinute,
                                invalidDrop = armedId == null && !preview.valid,
                                isArmedTarget = false,
                                nestFits = true,
                                slotHeight = 0.dp,
                                ghostMinute = null,
                                ghostTitle = null,
                                shakePx = 0f,
                                zIndexValue = 4f,
                                onClick = {},
                                onChildClick = {},
                                onDragStart = {},
                                onDragDelta = {},
                                onDragEnd = {},
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut()) {
                hint?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

// 卡片占用的竖向空间：下限是时间段本身，装不下属行时随内容长高。
// 渲染与让位扫描共用这两个估算，满课日的挪位才和实际画出来的一致。
private fun blockHeight(entry: TimelineEntry, childCount: Int, hourHeight: Dp): Dp =
    maxOf(
        hourHeight * ((entry.endMinute - entry.startMinute) / 60f),
        if (childCount == 0) 26.dp else 42.dp + 27.dp * childCount,
    )

private fun groupHeight(members: List<TimelineEntry>, childRows: Int, hourHeight: Dp): Dp =
    maxOf(
        hourHeight * ((members.maxOf { it.endMinute } - members.minOf { it.startMinute }) / 60f),
        34.dp + 30.dp * members.size + 28.dp * childRows,
    )

@Composable
private fun TimelineBlock(
    entry: TimelineEntry,
    children: List<TimelineEntry>,
    now: LocalDateTime,
    width: Dp,
    y: Dp,
    pushDown: Dp,
    hourHeight: Dp,
    accent: Color,
    isDragged: Boolean,
    dragDeltaPx: Float,
    previewStartMinute: Int?,
    invalidDrop: Boolean,
    isArmedTarget: Boolean,
    nestFits: Boolean,
    slotHeight: Dp,
    ghostMinute: Int?,
    ghostTitle: String?,
    shakePx: Float,
    zIndexValue: Float,
    onClick: () -> Unit,
    onChildClick: (TimelineEntry) -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val animDuration = if (reducedMotion) 0 else 220
    val phase = ScheduleTimeline.clock(entry, now, userFinished = entry.taskClosed)
    val durationMinutes = entry.endMinute - entry.startMinute
    // 卡片高度贴住时间段本身：不到一小时的块用单行，双行内容约 44dp，而 45 分钟的时段只有 36dp，
    // 一天全是课时就变成每张卡都压住下一张的墙。
    val compact = children.isEmpty() && durationMinutes < 60
    // 课程/固定/休息是"已经定好的背景时间"，不是用户能改的事：去掉渐变与描边，让任务自己跳出来。
    val backdrop = entry.kind != TimelineKind.TASK && children.isEmpty()
    val baseHeight = blockHeight(entry, children.size, hourHeight)
    // 高度只设下限：下属行内容高估算不准时让卡片随内容长高，绝不再把子行裁没。
    val totalHeight = if (isArmedTarget) baseHeight + slotHeight else baseHeight
    val previewStart = previewStartMinute ?: entry.startMinute
    // A quick press opens detail; a long press keeps the object under the finger and moves it.
    // The click modifier deliberately comes first so the short-tap path remains available.
    // rememberUpdatedState 让 pointerInput 在键不变时也总是调用最新的回调。
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val directManipulationModifier = Modifier.pointerInput(entry.id, entry.startMinute, entry.endMinute) {
        detectDragGesturesAfterLongPress(
            onDragStart = { currentOnDragStart() },
            onDrag = { change, amount ->
                change.consume()
                currentOnDragDelta(amount.y)
            },
            onDragCancel = { currentOnDragEnd() },
            onDragEnd = { currentOnDragEnd() },
        )
    }
    val animatedY by animateDpAsState(
        targetValue = y,
        animationSpec = tween(animDuration),
        label = "block-y-${entry.id}",
    )
    Box(
        modifier = Modifier
            .offset(y = animatedY + pushDown)
            .offset { IntOffset(0, if (isDragged) dragDeltaPx.roundToInt() else 0) }
            .width(width)
            .zIndex(zIndexValue)
            .graphicsLayer { translationX = shakePx }
            .clickable(onClick = onClick)
            .then(directManipulationModifier)
            .testTag("timeline-${entry.id}"),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = totalHeight)
                .graphicsLayer {
                    scaleX = if (isDragged) 1.03f else 1f
                    scaleY = if (isDragged) 1.03f else 1f
                    shadowElevation = if (isDragged) 16f else 0f
                },
            shape = RoundedCornerShape(if (backdrop) 12.dp else 16.dp),
            border = BorderStroke(
                width = when {
                    backdrop && !isDragged && !isArmedTarget -> 0.dp
                    isDragged || isArmedTarget -> 1.5.dp
                    else -> 1.dp
                },
                color = when {
                    invalidDrop || (isArmedTarget && !nestFits) -> MaterialTheme.colorScheme.error
                    isDragged || isArmedTarget -> accent
                    else -> accent.copy(alpha = 0.32f)
                },
            ),
        ) {
            Box(
                if (backdrop) {
                    Modifier.background(accent.copy(alpha = 0.075f))
                } else {
                    Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.06f))))
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 5.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (compact) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.width(3.dp).height(16.dp).background(accent.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${TimeBlockValidator.formatTime(previewStart)}–${TimeBlockValidator.formatTime(previewStart + durationMinutes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                maxLines = 1,
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(28.dp).background(accent.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                            Column(Modifier.padding(start = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${TimeBlockValidator.formatTime(previewStart)}–${TimeBlockValidator.formatTime(previewStart + durationMinutes)}".withPhase(
                                        phase.shortLabel(entry.date == now.toLocalDate()),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    children.forEach { child ->
                        val childPhase = ScheduleTimeline.clock(child, now, userFinished = child.taskClosed)
                        // 放入的连续感：下属行以缩放浮现，而不是凭空出现。
                        val appear = remember(child.id) { MutableTransitionState(false).apply { targetState = true } }
                        AnimatedVisibility(
                            visibleState = appear,
                            enter = scaleIn(initialScale = 0.8f, animationSpec = tween(animDuration)) + fadeIn(tween(animDuration)),
                        ) {
                            Surface(
                                color = accent.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onChildClick(child) }.testTag("timeline-${child.id}"),
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(DesignIcons.SubEvent, "同时进行", modifier = Modifier.size(13.dp), tint = accent)
                                    Text(child.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(
                                        "${TimeBlockValidator.formatTime(child.startMinute)}".withPhase(
                                            childPhase.shortLabel(child.date == now.toLocalDate()),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    // 合并插槽：目标块像容器一样张开的空间，虚线框 + 幽灵标题。
                    if (isArmedTarget && slotHeight > 2.dp) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(slotHeight)
                                .dashedBorder(
                                    color = if (nestFits) accent else MaterialTheme.colorScheme.error,
                                    cornerRadius = 10.dp,
                                ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (slotHeight > 22.dp && ghostMinute != null) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(DesignIcons.SubEvent, null, modifier = Modifier.size(13.dp), tint = accent)
                                    Text(
                                        "${ghostTitle.orEmpty()} · ${TimeBlockValidator.formatTime(ghostMinute)} 同时进行",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (nestFits) accent else MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupBlock(
    members: List<TimelineEntry>,
    childrenByParent: Map<String, List<TimelineEntry>> = emptyMap(),
    now: LocalDateTime,
    width: Dp,
    y: Dp,
    pushDown: Dp,
    hourHeight: Dp,
    shakePx: Float,
    zIndexValue: Float,
    onClick: (TimelineEntry) -> Unit,
    onChildClick: (TimelineEntry) -> Unit = {},
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val animDuration = if (reducedMotion) 0 else 220
    val unionStart = members.minOf { it.startMinute }
    val unionEnd = members.maxOf { it.endMinute }
    // 下属行只算本块成员自己的，多算了会让合轨块凭空长高、把后面的块挤更远。
    val childRows = members.sumOf { childrenByParent[it.id]?.size ?: 0 }
    val height = groupHeight(members, childRows, hourHeight)
    val accent = entryAccent(members.first())
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // 键只含 id：拖拽过程中联合时段会收缩，若把它算进键会让手势中途重启。
    val dragModifier = Modifier.pointerInput(members.first().id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { currentOnDragStart() },
            onDrag = { change, amount ->
                change.consume()
                currentOnDragDelta(amount.y)
            },
            onDragCancel = { currentOnDragEnd() },
            onDragEnd = { currentOnDragEnd() },
        )
    }
    val animatedY by animateDpAsState(
        targetValue = y,
        animationSpec = tween(animDuration),
        label = "group-y-${members.first().id}",
    )
    Box(
        modifier = Modifier
            .offset(y = animatedY + pushDown)
            .width(width)
            .zIndex(zIndexValue)
            .graphicsLayer { translationX = shakePx }
            .testTag("timeline-group-${members.first().id}"),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = height)
                .clickable { onClick(members.first()) }
                .then(dragModifier),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        ) {
            Box(Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.05f))))) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(DesignIcons.SubEvent, "同时进行", modifier = Modifier.size(14.dp), tint = accent)
                        Text(
                            "${members.size} 件同时进行",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${TimeBlockValidator.formatTime(unionStart)}–${TimeBlockValidator.formatTime(unionEnd)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                        )
                    }
                    members.forEach { member ->
                        val memberAccent = entryAccent(member)
                        val phase = ScheduleTimeline.clock(member, now, userFinished = member.taskClosed)
                        val memberChildren = childrenByParent[member.id].orEmpty()
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onClick(member) }.testTag("timeline-${member.id}"),
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.width(3.dp).height(20.dp).background(memberAccent.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                                    Text(
                                        member.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${TimeBlockValidator.formatTime(member.startMinute)}–${TimeBlockValidator.formatTime(member.endMinute)}".withPhase(
                                            phase.shortLabel(member.date == now.toLocalDate()),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = memberAccent,
                                        maxLines = 1,
                                    )
                                }
                                memberChildren.forEach { child ->
                                    val childAccent = entryAccent(child)
                                    val childPhase = ScheduleTimeline.clock(child, now, userFinished = child.taskClosed)
                                    Surface(
                                        color = childAccent.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .padding(start = 10.dp, end = 6.dp, bottom = 5.dp)
                                            .fillMaxWidth()
                                            .clickable { onChildClick(child) }
                                            .testTag("timeline-${child.id}"),
                                    ) {
                                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Icon(DesignIcons.SubEvent, "同时进行", modifier = Modifier.size(13.dp), tint = childAccent)
                                            Text(child.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(
                                                "${TimeBlockValidator.formatTime(child.startMinute)}".withPhase(
                                            childPhase.shortLabel(child.date == now.toLocalDate()),
                                        ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = childAccent,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun entryAccent(entry: TimelineEntry): Color = when (entry.kind) {
    TimelineKind.COURSE -> CourseAccent
    TimelineKind.REST -> RestAccent
    TimelineKind.COMMITMENT -> CommitmentAccent
    TimelineKind.TASK -> if (entry.secondClassroom) SecondClassroomAccent else Color(0xFF4E64B5)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineEntryDetailSheet(
    entry: TimelineEntry,
    now: LocalDateTime,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleReminder: ((Boolean) -> Unit)?,
    onFocus: (() -> Unit)?,
    onUpdateTime: (Int, Int, LocalDate?, () -> Unit) -> Boolean,
    writeBusy: Boolean,
    writeError: String?,
    reminderNotice: String?,
    onRemovePlacement: (() -> Unit)?,
) {
    val accent = entryAccent(entry)
    val phase = ScheduleTimeline.clock(entry, now, userFinished = entry.taskClosed)
    val note = entry.note?.trim().takeIf { !it.isNullOrBlank() }
    var showTimeEditor by rememberSaveable(entry.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("timeline-entry-detail-${entry.id}"),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("今日安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(entry.kind.displayLabel(), style = MaterialTheme.typography.labelLarge, color = accent)
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${TimeBlockValidator.formatTime(entry.startMinute)}–${TimeBlockValidator.formatTime(entry.endMinute)} · ${phase.copyForDisplay()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onToggleReminder?.let { toggle ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (entry.reminder) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            DesignIcons.Bell,
                            contentDescription = null,
                            tint = if (entry.reminder) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("开始时提醒我", style = MaterialTheme.typography.labelLarge)
                            Text(
                                // 开着但发不出去时，「到点会震动并弹出通知」是假话——说清缺的是哪一步。
                                reminderNotice
                                    ?: if (entry.reminder) "到点会震动并弹出通知" else "关闭中；开启后到点像闹钟一样提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reminderNotice != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = entry.reminder,
                            onCheckedChange = toggle,
                            enabled = !writeBusy,
                            modifier = Modifier.testTag("timeline-reminder-${entry.id}"),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("备注", style = MaterialTheme.typography.labelLarge)
                    Text(
                        note ?: "暂无备注",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DesignTextButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("timeline-note-${entry.id}"),
                    ) { Text(if (note == null) "添加备注" else "编辑备注") }
                }
            }
            Text("时间", style = MaterialTheme.typography.labelLarge)
            writeError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = { showTimeEditor = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timeline-time-editor-${entry.id}"),
            ) { Text("调整时间") }
            if (onFocus != null) {
                Button(
                    onClick = onFocus,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timeline-focus-${entry.id}"),
                ) { Text("进入专注") }
            }
            onRemovePlacement?.let { removePlacement ->
                DesignTextButton(
                    onClick = removePlacement,
                    modifier = Modifier.testTag("timeline-remove-${entry.id}"),
                ) { Text("移出今日安排") }
            }
        }
    }
    if (showTimeEditor) {
        TimelineTimeEditorDialog(
            title = "调整 ${entry.title}",
            initialStartMinute = entry.startMinute,
            initialEndMinute = entry.endMinute,
            tagKey = entry.id,
            onDismiss = { showTimeEditor = false },
            busy = writeBusy,
            saveError = writeError,
            // 只有排得动的安排开放换天：课程和固定事项按星期走，改日子等于改课表，那是时间页的事。
            initialDate = entry.date.takeIf { entry.id.startsWith("segment:") },
            onSave = { startMinute, endMinute, date, saved ->
                onUpdateTime(startMinute, endMinute, date) { showTimeEditor = false; saved() }
            },
        )
    }
}

/** 任务详情页也用它定时间：手动路上「几点开始、做多久」必须和日视图拿到同一个控件。 */
@Composable
internal fun TimelineTimeEditorDialog(
    title: String,
    initialStartMinute: Int,
    initialEndMinute: Int,
    tagKey: String,
    onDismiss: () -> Unit,
    onSave: (Int, Int, LocalDate?, () -> Unit) -> Boolean,
    busy: Boolean,
    saveError: String?,
    /** 传进来就开放换天：详情页挪安排不再困在当天，与对话侧的跨日挪动对齐。 */
    initialDate: LocalDate? = null,
) {
    val startWheel = rememberTimeWheelHold(initialStartMinute, key = tagKey)
    // 结束轮盘解耦是「结束要晚于开始」这类被拒保存的唯一来源：只想把一件事挪到下午的人，
    // 从不指望自己同时挪对第二轮。改成锁住时长，结束永远等于开始 + 时长，这类拒绝不再存在。
    var durationMinutes by rememberSaveable(tagKey) { mutableStateOf((initialEndMinute - initialStartMinute).coerceIn(5, 720)) }
    var errorMessage by remember(tagKey) { mutableStateOf<String?>(null) }
    var dateEpochDay by rememberSaveable(tagKey) { mutableStateOf(initialDate?.toEpochDay()) }
    var showDatePicker by remember(tagKey) { mutableStateOf(false) }
    val startMinute = startWheel.settledMinuteOfDay()
    val endMinute = (startMinute + durationMinutes).coerceAtMost(1_440)
    val selectedDate = dateEpochDay?.let(LocalDate::ofEpochDay)
    DesignDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initialDate != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "日期",
                            modifier = Modifier.widthIn(min = 40.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DesignTextButton(
                            onClick = { showDatePicker = true },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("timeline-date-$tagKey"),
                        ) { Text(taskDateLabel(selectedDate ?: LocalDate.now())) }
                    }
                }
                LabeledTimeWheel("开始", startWheel, tag = "timeline-start-$tagKey", enabled = !busy)
                TimeWheelSteps(startWheel, tag = "timeline-start-$tagKey", enabled = !busy)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "时长",
                        modifier = Modifier.widthIn(min = 40.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DesignTextButton(
                        onClick = { durationMinutes = (durationMinutes - 15).coerceAtLeast(5) },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("timeline-duration-minus-$tagKey"),
                    ) { Text("−15") }
                    Text(
                        "$durationMinutes 分钟",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("timeline-duration-$tagKey"),
                    )
                    DesignTextButton(
                        onClick = { durationMinutes = (durationMinutes + 15).coerceAtMost(720) },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("timeline-duration-plus-$tagKey"),
                    ) { Text("+15") }
                }
                Text(
                    (selectedDate?.let { "${taskDateLabel(it)} · " } ?: "") +
                        "${TimeBlockValidator.formatTime(startMinute)}–${TimeBlockValidator.formatTime(endMinute)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("timeline-range-preview-$tagKey"),
                )
                // 钟点是人在轮盘上自己指的，工具不改写他的话；但压在睡眠窗口上这件事必须当场说出口，
                // 否则「排上了」和「排得守得住」在界面上成了同一句话。
                if (ChatDayPlanner.overlapsSleepWindow(startMinute, endMinute)) {
                    Text(
                        "压在 23:00–07:00 睡眠窗口上",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("timeline-sleep-warning-$tagKey"),
                    )
                }
                (saveError ?: errorMessage)?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            DesignTextButton(
                onClick = {
                    errorMessage = if (!onSave(startMinute, endMinute, selectedDate) {}) {
                        "这个时间与受保护安排冲突，请调整时间。"
                    } else {
                        null
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("timeline-time-save-$tagKey"),
            ) { Text("保存时间") }
        },
        dismissButton = { DesignTextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
    if (showDatePicker && selectedDate != null) {
        TaskDatePickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onDateSelected = { dateEpochDay = it.toEpochDay(); showDatePicker = false },
        )
    }
}

private fun TimelineKind.displayLabel(): String = when (this) {
    TimelineKind.COURSE -> "课程"
    TimelineKind.REST -> "休息"
    TimelineKind.COMMITMENT -> "固定事项"
    TimelineKind.TASK -> "任务"
}

private fun TaskCategory.shortLabel(): String = when (this) {
    TaskCategory.UNSPECIFIED -> "未分类"
    TaskCategory.COURSE -> "课程"
    TaskCategory.EXTRACURRICULAR -> "课外"
    TaskCategory.OFFICE -> "事务"
    TaskCategory.LEISURE -> "生活"
}

// 倒计时/超时分钟数只对「就是今天」有意义：翻到昨天，每张卡都写「超时 1461 分钟」，
// 一天全是课时整面墙都是这种数字，读不出任何信息。别的日子只留时间段本身。
//
// 过期不写「超时 N 分钟」：那个数字一天比一天大，除了提醒你来晚了什么都不说，
// 正是同类软件被投诉最多的「积压不敢看」来源。过点就只讲过点了，接下来做什么由空档讲。
private fun com.swan1127.repland.domain.model.TimelineClock.shortLabel(sameDay: Boolean): String = when (phase) {
    TimelinePhase.UPCOMING -> if (sameDay) "${untilStartMinutes} 分钟后开始" else ""
    TimelinePhase.ACTIVE -> "正在进行 · 还剩 ${remainingMinutes} 分钟"
    TimelinePhase.OVERRUN -> "已结束"
    TimelinePhase.ENDED -> "已结束"
}

/** 时段与状态拼一行；状态没得说时不留孤零零的连接符。 */
private fun String.withPhase(label: String): String = if (label.isBlank()) this else "$this · $label"

private fun com.swan1127.repland.domain.model.TimelineClock.copyForDisplay(): String = when (phase) {
    TimelinePhase.UPCOMING -> "还没开始 · 约 ${untilStartMinutes} 分钟后"
    TimelinePhase.ACTIVE -> "进行中 · 已过 ${elapsedMinutes} 分钟，剩 ${remainingMinutes} 分钟"
    TimelinePhase.OVERRUN -> "已过计划时间"
    TimelinePhase.ENDED -> "已结束"
}
