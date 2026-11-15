package com.swan1127.repland.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.ui.theme.replandAccent
import kotlin.math.abs
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val WheelItemHeight = 40.dp

private fun wheelLabel(value: Int): String = value.toString().padStart(2, '0')

/** 停稳档位：offset=0 时 firstVisible 就是中间高亮格；偏移过半视为已滚到下一格。 */
private fun settledIndex(state: LazyListState, itemHeightPx: Float, lastIndex: Int): Int =
    if (state.firstVisibleItemScrollOffset * 2 >= itemHeightPx) {
        (state.firstVisibleItemIndex + 1).coerceAtMost(lastIndex)
    } else {
        state.firstVisibleItemIndex
    }

/**
 * 时/分轮盘的滚动状态。值只在保存时从滚动状态直接读出，因此不需要等停稳回调，
 * 也不会出现「打在框里的字符串」这种要先解析再判合法的输入。
 */
class TimeWheelHold internal constructor(
    val hour: LazyListState,
    val minute: LazyListState,
    val itemHeightPx: Float,
    internal val hourLastIndex: Int,
    internal val lastMinuteOfDay: Int,
) {
    fun settledHour(): Int = settledIndex(hour, itemHeightPx, hourLastIndex)
    fun settledMinuteStep(): Int = settledIndex(minute, itemHeightPx, 11)

    /** 5 分钟一档；允许 24:00 的轮盘会夹到当日末尾。 */
    fun settledMinuteOfDay(): Int = (settledHour() * 60 + settledMinuteStep() * 5).coerceAtMost(lastMinuteOfDay)
    fun isSettled(): Boolean = !hour.isScrollInProgress && !minute.isScrollInProgress

    /**
     * 直接落档，不走甩动。实测 84px 才挪 1 小时、慢速拖动位移为 0，靠手势改时间是负担；
     * 点选与步进都收在这里，保证「看到的那一格」就是最终档位。
     */
    suspend fun scrollToMinute(minuteOfDay: Int) {
        val target = minuteOfDay.coerceIn(0, lastMinuteOfDay)
        hour.scrollToItem(target / 60, 0)
        minute.scrollToItem((target % 60 + 2) / 5, 0)
    }

    /** 以当前停稳档位为基准挪 delta 分钟。 */
    suspend fun stepBy(deltaMinutes: Int) = scrollToMinute(settledMinuteOfDay() + deltaMinutes)
}

/**
 * 停稳后把档位写进 saveable，旋转屏幕或进程重建时仍回到用户刚选的时间；
 * 重建时轮盘正好落在该档，视觉上不会跳。
 *
 * @param allowEndOfDay 结束时间用 true，滚到 24 即当日 24:00。
 */
@Composable
fun rememberTimeWheelHold(
    initialMinute: Int,
    allowEndOfDay: Boolean = false,
    key: Any? = Unit,
): TimeWheelHold {
    val itemHeightPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val lastMinute = if (allowEndOfDay) 1_440 else 1_435
    // 存键要写死：弹窗里的读取失败横幅会插进组合最前面，按位置记的档位因此在重建后对不上号，
    // 用户挑的钟点静默回落到默认 9:00–10:00，再点保存就把这个默认值写进了库。
    var settled by rememberSaveable(key, allowEndOfDay, key = "time-wheel-settled-$key-$allowEndOfDay") {
        mutableStateOf<Int?>(null)
    }
    val from = (settled ?: initialMinute).coerceIn(0, lastMinute)
    val hour = remember(key, from, allowEndOfDay) { LazyListState(from / 60, 0) }
    val minute = remember(key, from, allowEndOfDay) { LazyListState((from % 60 + 2) / 5, 0) }
    val hold = remember(hour, minute, itemHeightPx, allowEndOfDay) {
        TimeWheelHold(hour, minute, itemHeightPx, if (allowEndOfDay) 24 else 23, lastMinute)
    }
    LaunchedEffect(hold) {
        // isSettled 必须进查询：只看档位的快照在“滚动结束”那一刻不会重新发射，
        // 停稳后读到的人选就永远记不下。第一次发射读到的是创建时的起点
        // （数据可能还没到、也可能是默认 9:00–10:00），它不算人选的档位。
        snapshotFlow { Triple(hold.settledHour(), hold.settledMinuteStep(), hold.isSettled()) }
            .filter { (_, _, settledNow) -> settledNow }
            .collect { (hourStep, minuteStep, _) ->
                val picked = (hourStep * 60 + minuteStep * 5).coerceAtMost(lastMinute)
                if (picked != from) settled = picked
            }
    }
    return hold
}

/** 双轮盘：时 0–23（或 0–24），分按 5 分钟步进；停稳自动吸附到整档。 */
@Composable
fun TimeWheelField(
    hold: TimeWheelHold,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val accent = replandAccent(0)
    val hourIndex = hold.hour.firstVisibleItemIndex
    val minuteIndex = hold.minute.firstVisibleItemIndex
    Surface(
        modifier = modifier
            .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics {
                contentDescription = "轮盘时间 ${wheelLabel(hourIndex)}:${wheelLabel(minuteIndex * 5)}"
                if (!enabled) disabled()
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .background(accent.surface, RoundedCornerShape(8.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                WheelColumn(
                    (0..hold.hourLastIndex).map(::wheelLabel),
                    hold.hour,
                    hold.itemHeightPx,
                    modifier = tag?.let { Modifier.testTag("time-wheel-$it-hour") } ?: Modifier,
                    enabled = enabled,
                    tag = tag?.let { "time-wheel-$it-hour" },
                )
                Text(
                    ":",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent.ink,
                )
                WheelColumn(
                    (0 until 12).map { wheelLabel(it * 5) },
                    hold.minute,
                    hold.itemHeightPx,
                    modifier = tag?.let { Modifier.testTag("time-wheel-$it-minute") } ?: Modifier,
                    enabled = enabled,
                    tag = tag?.let { "time-wheel-$it-minute" },
                )
            }
        }
    }
}

/** 一行「标签 + 轮盘」，用于成对出现的开始/结束时间。 */
@Composable
fun LabeledTimeWheel(
    label: String,
    hold: TimeWheelHold,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 40.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeWheelField(hold, tag = tag, enabled = enabled)
    }
}

/** 时长档位（分钟）。写入门槛是 5–720，所以每一档都直接写得进去，不会出现"时长不合法"。 */
internal val DurationLadder = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600, 720)

internal fun closestDuration(minutes: Int): Int = DurationLadder.minByOrNull { abs(it - minutes) } ?: 60

/** 时长轮盘只有一列：改时长不需要再算一遍结束时间。 */
class DurationWheelHold internal constructor(
    val column: LazyListState,
    val itemHeightPx: Float,
) {
    fun settledIndex(): Int = settledIndex(column, itemHeightPx, DurationLadder.size - 1)
    fun settledMinutes(): Int = DurationLadder[settledIndex()]

    suspend fun scrollToMinutes(minutes: Int) =
        column.scrollToItem(DurationLadder.indexOf(closestDuration(minutes)))
}

@Composable
fun rememberDurationWheelHold(initialMinutes: Int, key: Any? = Unit): DurationWheelHold {
    val itemHeightPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    var settled by rememberSaveable(key, key = "duration-wheel-settled-$key") { mutableStateOf<Int?>(null) }
    val index = settled?.let(DurationLadder::indexOf) ?: DurationLadder.indexOf(closestDuration(initialMinutes))
    val column = remember(key, index) { LazyListState(index, 0) }
    val hold = remember(column, itemHeightPx) { DurationWheelHold(column, itemHeightPx) }
    val startIndex = index
    LaunchedEffect(hold) {
        // 同 rememberTimeWheelHold：停稳事件必须随查询一起发射，初始起点不算人选的档位。
        snapshotFlow { hold.settledIndex() to !column.isScrollInProgress }
            .filter { (_, settledNow) -> settledNow }
            .collect { (settledAt, _) ->
                if (settledAt != startIndex) settled = DurationLadder[settledAt]
            }
    }
    return hold
}

@Composable
fun DurationWheelField(
    hold: DurationWheelHold,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val accent = replandAccent(0)
    val minutes = DurationLadder[hold.settledIndex()]
    Surface(
        modifier = modifier
            .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics {
                contentDescription = "轮盘时长 $minutes 分钟"
                if (!enabled) disabled()
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .background(accent.surface, RoundedCornerShape(8.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                WheelColumn(
                    DurationLadder.map(Int::toString),
                    hold.column,
                    hold.itemHeightPx,
                    modifier = tag?.let { Modifier.testTag("duration-wheel-$it") } ?: Modifier,
                    enabled = enabled,
                    tag = tag?.let { "duration-wheel-$it" },
                )
                Text("分钟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accent.ink)
            }
        }
    }
}

@Composable
private fun WheelColumn(
    values: List<String>,
    state: LazyListState,
    itemHeightPx: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, values.size) {
        snapshotFlow { state.isScrollInProgress }
            .filter { !it }
            .collect {
                val target = settledIndex(state, itemHeightPx, values.size - 1)
                if (target != state.firstVisibleItemIndex || state.firstVisibleItemScrollOffset != 0) {
                    state.animateScrollToItem(target)
                }
            }
    }
    // 列宽必须写死：档位自己带 fillMaxWidth，若这里只给 minWidth，LazyColumn 会按可用的
    // 最大宽度量出第一列，时轮盘把整行吃光，分轮盘连同冒号一起被压成 0 宽（真机上只剩小时）。
    LazyColumn(
        state = state,
        userScrollEnabled = enabled,
        modifier = modifier.height(WheelItemHeight * 3).width(64.dp),
        contentPadding = PaddingValues(vertical = WheelItemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(values.size) { index ->
            val selected = index == state.firstVisibleItemIndex
            Text(
                values[index],
                style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) replandAccent(0).ink else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .height(WheelItemHeight)
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.CenterVertically)
                    .then(tag?.let { Modifier.testTag("$it-item-$index") } ?: Modifier)
                    .clickable(enabled = enabled) { scope.launch { state.animateScrollToItem(index) } },
            )
        }
    }
}

/**
 * 步进胶囊：挪半小时不需要甩轮盘。挂在轮盘下方，不额外占入口，
 * 也不改变「时间只有轮盘这一种写法」的既定约束。
 */
@Composable
fun TimeWheelSteps(
    hold: TimeWheelHold,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: List<Int> = listOf(-60, -15, 15, 60),
) {
    val scope = rememberCoroutineScope()
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { delta ->
            DesignTextButton(
                onClick = { scope.launch { hold.stepBy(delta) } },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("time-wheel-step-$tag-${if (delta < 0) "m" else "p"}${abs(delta)}"),
            ) { Text(if (delta < 0) "−${abs(delta)}" else "+$delta") }
        }
    }
}
