package com.swan1127.repland.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * 任务页顶栏只留标题。排序不再是一个常驻入口：重新排序走「制定计划」的预览确认，
 * 撤销则跟在应用后的回执条上，用户不需要在右上角对着两个常驻按钮做决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskPageAppBar(title: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(horizontal = 16.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).testTag("task-page-title"))
        }
    }
}

@Composable
internal fun TaskCaptureButton(
    onClick: () -> Unit,
    insets: WindowInsets = WindowInsets.safeDrawing,
) {
    PlannerFloatingButton(onClick, com.swan1127.repland.ui.components.DesignIcons.Plus,
        "添加任务", "add-task", "task-add-safe-area", insets)
}

@Composable
private fun PlannerFloatingButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    safeAreaTag: String,
    insets: WindowInsets,
) {
    Box(Modifier.testTag(safeAreaTag).windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))) {
        androidx.compose.material3.FloatingActionButton(onClick = onClick, modifier = Modifier.testTag(tag),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            androidx.compose.material3.Icon(icon, contentDescription = description)
        }
    }
}
