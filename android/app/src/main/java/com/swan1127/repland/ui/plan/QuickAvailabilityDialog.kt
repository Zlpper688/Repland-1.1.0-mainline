package com.swan1127.repland.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.components.DesignCheckbox as Checkbox
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignDialog as AlertDialog
import com.swan1127.repland.ui.components.DesignFilterChip as FilterChip
import com.swan1127.repland.ui.components.DesignTextButton as TextButton
import com.swan1127.repland.ui.components.DesignTextField
import com.swan1127.repland.ui.components.LabeledTimeWheel
import com.swan1127.repland.ui.components.TaskDatePickerDialog
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import com.swan1127.repland.ui.components.taskDateLabel
import java.time.LocalDate

@Composable
fun QuickAvailabilityDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (QuickAvailability) -> Unit,
    canSave: Boolean = true, readNotice: (@Composable () -> Unit)? = null) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var free by rememberSaveable { mutableStateOf(true) }
    var title by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val startWheel = rememberTimeWheelHold(18 * 60)
    val endWheel = rememberTimeWheelHold(20 * 60, allowEndOfDay = true)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (free) "补充一段可用时间" else "登记一段占用") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                readNotice?.invoke()
                DesignClickableSurface(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quick-availability-date"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(taskDateLabel(date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("改日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                LabeledTimeWheel("开始", startWheel, tag = "quick-availability-start")
                LabeledTimeWheel("结束", endWheel, tag = "quick-availability-end")
                // 对话里一句「明天晚上七点到九点要值班」就能登记占用；不想说话的人得在同一张卡上
                // 拿到同样的选择，否则手动路只能往日程里加空闲，永远锁不住一段时间。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = free,
                        onClick = { free = true },
                        label = { Text("空闲，能排事") },
                        enabled = !busy,
                        modifier = Modifier.testTag("quick-availability-free"),
                    )
                    FilterChip(
                        selected = !free,
                        onClick = { free = false },
                        label = { Text("占用，别排事") },
                        enabled = !busy,
                        accentIndex = 2,
                        modifier = Modifier.testTag("quick-availability-busy"),
                    )
                }
                DesignTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (free) "用途（可留空）" else "什么事（如：班会）") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.testTag("quick-availability-title"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = weekly, onCheckedChange = { weekly = it }, enabled = !busy)
                    Text(if (free) "每周同一天都可用" else "每周同一天都这样", Modifier.padding(top = 12.dp))
                }
                (error ?: localError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
                val value = QuickAvailability(
                    date,
                    startWheel.settledMinuteOfDay(),
                    endWheel.settledMinuteOfDay(),
                    weekly,
                    free = free,
                    title = title.trim().takeIf { it.isNotEmpty() },
                )
                localError = when {
                    value.startMinute >= value.endMinute -> "结束要晚于开始。"
                    !value.isValid() -> "这段日期已经过去，换一天再补充。"
                    else -> null
                }
                if (localError == null) onSave(value)
            }, enabled = !busy && canSave,
            modifier = Modifier.heightIn(min = 48.dp).testTag("quick-availability-save")) { Text(if (busy) "正在保存…" else "保存并生成预览") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("暂不补充") } })
    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = date,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date = it; showDatePicker = false },
        )
    }
}
