package com.swan1127.repland.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate

/** 周一起排：课表、学期、周视图都按周一开周，日历不能自己换成周日。 */
private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

private fun weekdayLabel(date: LocalDate): String = WeekdayLabels[date.dayOfWeek.value - 1]

/**
 * A date-only control shared by capture and editing.
 *
 * 自己画的月历而不是 Material 日历弹窗：一整套控件要长成同一种东西；而翻月就能点到任意一天，
 * 不需要为了精确改日子再回去开键盘打字。
 */
@Composable
fun TaskDatePickerDialog(
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    mondaysOnly: Boolean = false,
    title: String = "选择日期",
) {
    val today = LocalDate.now()
    var selectedEpochDay by rememberSaveable { mutableStateOf(initialDate?.toEpochDay()) }
    var displayedEpochDay by rememberSaveable {
        mutableStateOf((initialDate ?: today).withDayOfMonth(1).toEpochDay())
    }
    val selected = selectedEpochDay?.let(LocalDate::ofEpochDay)
    val displayedMonth = LocalDate.ofEpochDay(displayedEpochDay)

    fun select(date: LocalDate) {
        if (!mondaysOnly || date.dayOfWeek == DayOfWeek.MONDAY) selectedEpochDay = date.toEpochDay()
    }

    DesignDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesignTextButton(
                        onClick = { displayedEpochDay = displayedMonth.minusMonths(1).withDayOfMonth(1).toEpochDay() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("task-date-picker-prev-month"),
                    ) { Text("‹") }
                    Text(
                        "${displayedMonth.year} 年 ${displayedMonth.monthValue} 月",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    DesignTextButton(
                        onClick = { displayedEpochDay = displayedMonth.plusMonths(1).withDayOfMonth(1).toEpochDay() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("task-date-picker-next-month"),
                    ) { Text("›") }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    WeekdayLabels.forEach {
                        Text(
                            it,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                val lead = displayedMonth.withDayOfMonth(1).dayOfWeek.value - 1
                (List(lead) { null as LocalDate? } + (1..displayedMonth.lengthOfMonth()).map { displayedMonth.withDayOfMonth(it) })
                    .chunked(7)
                    .forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { date ->
                                // 月首前的空位照样占一格，星期表和列才对得齐。
                                val blocked = mondaysOnly && date != null && date.dayOfWeek != DayOfWeek.MONDAY
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp)
                                        .padding(2.dp)
                                        .then(
                                            date?.let {
                                                Modifier
                                                    .clickable(role = Role.Button) { select(it) }
                                                    .testTag("task-date-cell-${it.isoDayOfYearTag()}")
                                            } ?: Modifier,
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (date != null && date == selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    border = when {
                                        date == null -> null
                                        date == today -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        blocked -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                                        else -> null
                                    },
                                ) {
                                    Text(
                                        date?.dayOfMonth?.toString() ?: "",
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (date == selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = when {
                                            date == selected -> MaterialTheme.colorScheme.onPrimary
                                            blocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                Text(
                    selected?.let { "已选 ${taskDateLabel(it)} · 周${weekdayLabel(it)}" }
                        ?: if (mondaysOnly) "学期只能从周一开始：点周一那一格" else "还没选日子",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            DesignTextButton(
                onClick = { selected?.let(onDateSelected) },
                enabled = selected != null,
                modifier = Modifier.testTag("task-date-picker-confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            DesignTextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("task-date-picker-dismiss"),
            ) { Text(dismissLabel) }
        },
    )
}

/** 格子标签不带前导零，和格面上显示的数字一致。 */
private fun LocalDate.isoDayOfYearTag(): String = "$year-$monthValue-$dayOfMonth"

fun taskDateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> if (date.year == today.year) "${date.monthValue} 月 ${date.dayOfMonth} 日" else "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}
