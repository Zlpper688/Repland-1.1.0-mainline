package com.swan1127.repland.ui.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.data.AssistantJournal
import com.swan1127.repland.data.JournalEntry
import com.swan1127.repland.ui.components.DesignTextButton
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 助手日记：助手"主动"说过的话（目前就是每日简报）按主题成链、只读留痕，以及用户有没有回话。
 * 这是问责面不是记忆推断——没回话就如实写"没有后续依据"，绝不据此编造进度（借鉴 Today AI #5）。
 * 不提供编辑/删除：主动行为的留痕不该被助手自己改写，也不该被当成可勾选的记忆信号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDiarySheet(
    journal: AssistantJournal,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = Modifier.testTag("assistant-diary-sheet"),
        onDismissRequest = { if (!sheetState.isVisible) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        BackHandler {
            scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("助手日记", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                DesignTextButton(onClick = {
                    scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
                }, modifier = Modifier.testTag("assistant-diary-close")) { Text("收起") }
            }
            if (journal.entries.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("diary-empty"),
                ) {
                    Text(
                        "还没有主动记录",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 按主题成链：同一主题（今日简报）的多次刷新归到一组，最新的在前（编辑视角的历史）。
                journal.entries.groupBy { it.topic }.forEach { (topic, entries) ->
                    Text(topic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    entries.sortedByDescending { it.at }.forEach { entry -> DiaryEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun DiaryEntryRow(entry: JournalEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("diary-entry"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${entry.date.format(DATE)} ${entry.at.format(TIME)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = if (entry.replied) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("diary-reply-status"),
                ) {
                    Text(
                        if (entry.replied) "已回复" else "未回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.replied) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(entry.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            entry.evidence.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("MM-dd")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")