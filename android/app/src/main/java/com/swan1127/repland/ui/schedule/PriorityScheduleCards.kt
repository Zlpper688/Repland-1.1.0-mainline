package com.swan1127.repland.ui.schedule

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.swan1127.repland.R
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.EmptyScene

/**
 * 新增日程卡：事情都没排的时候，它是这一屏的入口。
 * 远山色带落底 + [记一件事] 胶囊——空态给出口，入口留在原地（DESIGN_LANGUAGE §4 / CAPSULE_LAYER_SPEC）。
 */
@Composable
fun ScheduleCreateCard(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    EmptyScene(
        artwork = R.drawable.empty_schedule,
        title = "今天还没有具体安排",
        modifier = modifier
            .fillMaxWidth()
            .testTag("schedule-create-plus"),
        action = {
            CapsuleRow(
                capsules = listOf(CapsuleSpec("add", "记一件事", CapsuleKind.VERB, tag = "schedule-create-add")),
                onCapsuleClick = { onAdd() },
            )
        },
    )
}

