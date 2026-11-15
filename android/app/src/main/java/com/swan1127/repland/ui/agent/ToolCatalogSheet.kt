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
import com.swan1127.repland.domain.model.AgentToolKind
import com.swan1127.repland.domain.model.AgentToolSpec
import com.swan1127.repland.ui.components.DesignTextButton
import kotlinx.coroutines.launch

/**
 * 技能清单：助手当前能做的全部动作，只读的行为契约（触发描述给模型看，这里原样给用户看）。
 * 不提供开关——关掉任何一项都会打破「手动=对话等价」；写入类动作本来就必经确认卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCatalogSheet(
    specs: List<AgentToolSpec>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = Modifier.testTag("tool-catalog-sheet"),
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
                Text("技能清单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                DesignTextButton(onClick = {
                    scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
                }, modifier = Modifier.testTag("tool-catalog-close")) { Text("收起") }
            }
            val visible = specs.filter { !it.hidden }
            val reads = visible.filter { it.kind == AgentToolKind.READ }
            val writes = visible.filter { it.kind == AgentToolKind.WRITE }
            if (reads.isNotEmpty()) {
                Text("读 · 直接做", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                reads.forEach { ToolContractRow(it) }
            }
            if (writes.isNotEmpty()) {
                Text("写 · 先确认后落库", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                writes.forEach { ToolContractRow(it) }
            }
        }
    }
}

@Composable
private fun ToolContractRow(spec: AgentToolSpec) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("tool-catalog-${spec.name}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (spec.kind == AgentToolKind.READ) "读" else "写",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(spec.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}