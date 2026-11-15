package com.swan1127.repland.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.ScheduleTimeline
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.theme.replandAccent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val WeekdayFormatter = DateTimeFormatter.ofPattern("E", Locale.CHINA)

/** 顶部日期标题：先给相对关系再给日子，比 yyyy/MM/dd 更容易一眼确认自己在看哪天。 */
fun scheduleDateHeadline(date: LocalDate, today: LocalDate): String {
    val label = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日"
        else "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
    return "$label ${date.format(WeekdayFormatter)}"
}

/**
 * 顶部日期点开的面板。日/周/月读同一个日期，所以这里只要把日子选走，三个视图一起跟上。
 * 格子下方的小点来自真实安排数量，不用先切月视图才知道哪天是空的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDateSheet(
    date: LocalDate,
    weeklyBlocks: List<WeeklyTimeBlock>,
    dateOverrides: List<DateOverride>,
    planSegments: List<PlannedSegment>,
    tasks: List<Task>,
    semesterFirstWeekMonday: LocalDate?,
    onDismiss: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    var shownMonth by remember(date) { mutableStateOf(YearMonth.from(date)) }
    val firstCell = shownMonth.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0 until 42).map { firstCell.plusDays(it.toLong()) }
    val counts = days.associateWith { day ->
        ScheduleTimeline.entries(day, weeklyBlocks, dateOverrides, planSegments, tasks, semesterFirstWeekMonday).size
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag("schedule-date-sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { shownMonth = shownMonth.minusMonths(1) },
                    modifier = Modifier.testTag("schedule-date-prev-month"),
                ) { Icon(DesignIcons.ChevronLeft, "上一月", modifier = Modifier.size(20.dp)) }
                Text(
                    "${shownMonth.year}年${shownMonth.monthValue}月",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = { shownMonth = shownMonth.plusMonths(1) },
                    modifier = Modifier.testTag("schedule-date-next-month"),
                ) { Icon(DesignIcons.ChevronRight, "下一月", modifier = Modifier.size(20.dp)) }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(
                        it,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        ScheduleDayCell(
                            date = day,
                            inMonth = YearMonth.from(day) == shownMonth,
                            selected = day == date,
                            isToday = day == today,
                            entryCount = counts.getValue(day),
                            onClick = {
                                onSelectDate(day)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            if (date != today) {
                Spacer(Modifier.height(4.dp))
                DesignClickableSurface(
                    onClick = {
                        onSelectDate(today)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("schedule-date-back-today"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("回到今天", style = MaterialTheme.typography.labelLarge, color = replandAccent(0).ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ScheduleDayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    entryCount: Int,
    onClick: () -> Unit,
) {
    val accent = replandAccent(0)
    DesignClickableSurface(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("schedule-date-pick-$date"),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.surface else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> accent.ink
                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    isToday -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (entryCount > 0) {
                Box(
                    Modifier.size(4.dp).background(
                        if (selected) accent.ink.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        CircleShape,
                    ),
                )
            } else {
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}
