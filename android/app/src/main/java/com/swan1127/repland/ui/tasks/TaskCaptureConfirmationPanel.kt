package com.swan1127.repland.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TimeBlockValidator

/**
 * 捕获后置确认面：任务此刻已经落进事项库（用户逐字录入，USER_INPUT、未排期），
 * 这里当面把去向分成三组——
 * - 「排入今天」：接受一条具体时段的建议（suggestedSlot 由调用方按当日实况算出），
 *   确认后 scheduledForDate=今天、来源记为 ACCEPTED_SUGGESTION，日程出现新时段。
 * - 「自己选时间」：今天没空或想排到别的日子，就地打开手动时间编辑器挑一段。
 * - 「只记入事项库」：维持现状，不排日程。
 * 没有第四条静默的路；关掉对话框等同「只记入」。
 */
@Composable
fun TaskCaptureConfirmationPanel(
    task: TaskDraft,
    suggestedSlot: Pair<Int, Int>?,
    slotUnavailableReason: String?,
    busy: Boolean,
    onScheduleToday: () -> Unit,
    onPickTime: () -> Unit,
    onLibraryOnly: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onLibraryOnly() },
        modifier = Modifier.testTag("capture-confirm-panel"),
        title = { Text("「${task.displayName}」已记入事项库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("capture-confirm-schedule-today-group"),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("排入今天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            when {
                                slotUnavailableReason != null -> slotUnavailableReason
                                suggestedSlot != null ->
                                    "建议 ${TimeBlockValidator.formatTime(suggestedSlot.first)}–" +
                                        TimeBlockValidator.formatTime(suggestedSlot.second)
                                else -> "今天没有可直接排入的空档。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(
                            onClick = onScheduleToday,
                            enabled = suggestedSlot != null && !busy,
                            modifier = Modifier.fillMaxWidth().testTag("capture-confirm-schedule-today"),
                        ) { Text(if (busy) "正在排入…" else "排入今天") }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("capture-confirm-pick-time-group"),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("自己选时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = onPickTime,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("capture-confirm-pick-time"),
                        ) { Text("自己选时间") }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("capture-confirm-undecided-group"),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("只记入事项库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = onLibraryOnly,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("capture-confirm-library-only"),
                        ) { Text("只记入事项库") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (!busy) onLibraryOnly() }, enabled = !busy) { Text("稍后再说") }
        },
    )
}
