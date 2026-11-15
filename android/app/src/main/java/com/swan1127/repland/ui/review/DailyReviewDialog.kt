package com.swan1127.repland.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.R
import com.swan1127.repland.domain.model.DailyReviewDecision
import com.swan1127.repland.domain.model.DailyReviewPendingItem
import com.swan1127.repland.domain.model.DailyReviewSummary
import java.time.LocalDate

/**
 * 每日回顾 = 决策面：待确认的工作段当场三选一（完成 / 部分完成 / 顺延），每个操作都
 * 真实落库；「跳过回顾」「关闭」只是离开，不写任何表。汇总数字仍由 DailyReviewSummarizer
 * 从已记录的证据算出，这里不推断任何状态。
 */
@Composable
fun DailyReviewDialog(
    date: LocalDate,
    summary: DailyReviewSummary,
    pendingItems: List<DailyReviewPendingItem>,
    busy: Boolean,
    canAct: Boolean,
    nextSuggestedTaskName: String? = null,
    onComplete: (taskId: String) -> Unit,
    onPartial: (taskId: String) -> Unit,
    onPostpone: (taskId: String) -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.daily_review_title, date.toString())) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.daily_review_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SummaryLine(stringResource(R.string.daily_review_planned_segments), summary.plannedSegmentCount.toString())
                SummaryLine(stringResource(R.string.daily_review_pending_segments), summary.pendingSegmentCount.toString())
                SummaryLine(stringResource(R.string.daily_review_feedback_count), summary.feedbackCount.toString())
                SummaryLine(
                    stringResource(R.string.actual_duration_optional).substringBefore("（"),
                    stringResource(R.string.duration_format, summary.actualDurationMinutes),
                )

                Text(stringResource(R.string.daily_review_pending_segments), fontWeight = FontWeight.SemiBold)
                if (pendingItems.isEmpty()) {
                    Text(
                        stringResource(R.string.daily_review_no_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    pendingItems.forEach { item ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(item.taskName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${formatMinute(item.startMinute)}–${formatMinute(item.endMinute)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onComplete(item.taskId) },
                                        enabled = canAct && !busy,
                                    ) { Text(stringResource(R.string.daily_review_action_complete)) }
                                    OutlinedButton(
                                        onClick = { onPartial(item.taskId) },
                                        enabled = canAct && !busy,
                                    ) { Text(stringResource(R.string.partial_completion)) }
                                    TextButton(
                                        onClick = { onPostpone(item.taskId) },
                                        enabled = canAct && !busy,
                                    ) { Text(stringResource(R.string.daily_review_action_postpone)) }
                                }
                            }
                        }
                    }
                }

                if (summary.progressByTask.isEmpty()) {
                    Text(
                        stringResource(R.string.daily_review_no_feedback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.daily_review_recorded_feedback), fontWeight = FontWeight.Medium)
                    summary.progressByTask.forEach { progress ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                TextButton(onClick = { onOpenTask(progress.taskId) }) {
                                    Text(progress.taskName, fontWeight = FontWeight.Medium)
                                }
                                progress.progressPercent?.let { percent ->
                                    Text(stringResource(R.string.progress_format, percent))
                                }
                                progress.completionResult?.let { result -> Text(result) }
                            }
                        }
                    }
                }
                nextSuggestedTaskName?.let { taskName ->
                    Text(
                        stringResource(R.string.daily_review_next_suggestion, taskName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.skip_daily_review)) }
        },
    )
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
