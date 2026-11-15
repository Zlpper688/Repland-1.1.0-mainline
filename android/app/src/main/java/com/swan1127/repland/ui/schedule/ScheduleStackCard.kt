package com.swan1127.repland.ui.schedule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.swan1127.repland.R
import com.swan1127.repland.domain.model.ScheduleTimeline
import com.swan1127.repland.domain.model.TimeBlockValidator
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import com.swan1127.repland.domain.model.TimelinePhase
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.EmptyScene
import com.swan1127.repland.ui.components.rememberLandscapeSurface
import com.swan1127.repland.ui.theme.LocalReducedMotion
import com.swan1127.repland.ui.theme.LocalSemanticStateColors
import java.time.LocalDateTime

/**
 * 书堆卡：今天的事默认像一叠书一样叠在一起，翻动看每一件，
 * 右下角加号随时新增日程；正在进行和下一项都从卡片顶部一行字读起。
 */
@Composable
fun ScheduleStackCard(
    entries: List<TimelineEntry>,
    now: LocalDateTime,
    onOpenEntry: (TimelineEntry) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    // 一叠卡片共用一层灯色：底色跟着时辰走（夜里偏暖沙），事项自己的类别色照旧只说它自己的事。
    val surface = rememberLandscapeSurface(MaterialTheme.colorScheme.surfaceContainerHigh)
    // 状态行的颜色说"状态"不说"类别"：进行中/计划中/未完成各归语义色，类别色继续只画块面。
    val semanticStates = LocalSemanticStateColors.current
    val nowMinute = now.hour * 60 + now.minute
    val ordered = remember(entries) {
        val (current, past) = entries.sortedWith(compareBy(TimelineEntry::startMinute, TimelineEntry::id))
            .partition { it.endMinute > nowMinute }
        current + past.asReversed()
    }
    val defaultFront = ordered.indexOfFirst { it.startMinute <= nowMinute && nowMinute < it.endMinute }
        .takeIf { it >= 0 }
        ?: ordered.indexOfFirst { it.startMinute > nowMinute }.takeIf { it >= 0 }
        ?: 0
    var front by remember(ordered) { mutableStateOf(defaultFront) }
    val count = ordered.size
    if (count == 0) {
        // 空日不再是消失的一块：山水远景落底，一句话 + 胶囊把入口留在原地。
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            EmptyScene(
                artwork = R.drawable.empty_schedule,
                title = "今天还没有具体安排",
                action = {
                    CapsuleRow(
                        capsules = listOf(CapsuleSpec("add", "记一件事", CapsuleKind.VERB)),
                        onCapsuleClick = { onAdd() },
                    )
                },
            )
        }
        return
    }
    // 倒计时只对「就是今天」那一叠有意义：翻到昨天，超时分钟数会一路涨到上千，
    // 除了吓人什么也没说。别的日子只讲开始没开始。
    val sameDay = now.toLocalDate() == ordered.first().date
    val spec = if (reducedMotion) tween<Float>(0) else spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    fun relative(index: Int) = (index - front + count) % count
    val dragAccum = remember { mutableFloatStateOf(0f) }
    val swipeModifier = if (count > 1) Modifier.pointerInput(ordered) {
        detectHorizontalDragGestures(
            onDragStart = { dragAccum.floatValue = 0f },
            onDragEnd = {
                val consumed = dragAccum.floatValue
                dragAccum.floatValue = 0f
                if (consumed < -80f) front = (front + 1) % count
                else if (consumed > 80f) front = (front - 1 + count) % count
            },
            onDragCancel = { dragAccum.floatValue = 0f },
        ) { change, dragAmount ->
            change.consume()
            dragAccum.floatValue += dragAmount
        }
    } else Modifier

    Box(modifier.fillMaxWidth().heightIn(min = 128.dp).then(swipeModifier)) {
        ordered.forEachIndexed { index, entry ->
            val relative = relative(index)
            if (relative > 2) return@forEachIndexed
            val progress by animateFloatAsState(
                targetValue = relative.toFloat(),
                animationSpec = spec,
                label = "stack-$index",
            )
            val accent = entryAccent(entry)
            val phase = ScheduleTimeline.clock(entry, now, userFinished = entry.taskClosed)
            val statusLine = if (sameDay) when (phase.phase) {
                TimelinePhase.ACTIVE -> "正在进行 · 还剩 ${phase.remainingMinutes} 分钟"
                TimelinePhase.UPCOMING -> "下一项 ${TimeBlockValidator.formatTime(entry.startMinute)} · ${phase.untilStartMinutes} 分钟后"
                // 不写「超时 N 分钟」：这个数字只会一天天变大，除提醒你来晚了什么都没说。
                TimelinePhase.OVERRUN -> "时间已过 · 还没记结果"
                TimelinePhase.ENDED -> "已结束"
            } else when (phase.phase) {
                TimelinePhase.UPCOMING -> "还没开始"
                else -> "已结束"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(count - relative.toFloat())
                    .graphicsLayer {
                        translationY = (-12.dp * progress).toPx()
                        translationX = (9.dp * progress).toPx()
                        rotationZ = 3.2f * progress
                        scaleX = 1f - 0.045f * progress
                        scaleY = 1f - 0.045f * progress
                        shadowElevation = if (progress == 0f) 10f else 4f
                    }
                    .alpha(if (progress == 0f) 1f else if (progress <= 1f) 0.9f else 0.45f)
                    .then(
                        if (relative == 0) Modifier.clickable {
                            if (count > 1) front = (front + 1) % count else onOpenEntry(entry)
                        } else Modifier
                    )
                    .testTag(if (relative == 0) "schedule-stack-front" else "schedule-stack-behind-$relative"),
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.05f))))) {
                        Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                statusLine,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (phase.phase) {
                                    TimelinePhase.ACTIVE -> semanticStates.onInProgress
                                    TimelinePhase.UPCOMING -> semanticStates.onPlanned
                                    TimelinePhase.OVERRUN -> semanticStates.onMissed
                                    TimelinePhase.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${TimeBlockValidator.formatTime(entry.startMinute)}–${TimeBlockValidator.formatTime(entry.endMinute)}${entry.kindLabel()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (relative == 0) {
                // 加号是这一屏唯一的主动作：用和助手浮标、新增卡同一支墨色。
                // 暖纸上一枚薄荷贴片会让眼睛以为它是另一类东西，按下也没反应。
                DesignClickableSurface(
                    onClick = onAdd,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(count + 1f)
                        .padding(14.dp)
                        .size(48.dp)
                        .testTag("current-event-add"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    pressedScale = .9f,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            DesignIcons.Plus,
                            "新增日程",
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun TimelineEntry.kindLabel(): String = when (kind) {
    TimelineKind.COURSE -> " · 课程"
    TimelineKind.REST -> " · 休息"
    TimelineKind.COMMITMENT -> " · 固定"
    TimelineKind.TASK -> ""
}
