package com.swan1127.repland.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swan1127.repland.ui.theme.LocalReplandVisuals
import com.swan1127.repland.ui.theme.ReplandTheme
import com.swan1127.repland.ui.theme.pressMotion

/** 胶囊形态：动词 = 点即生效；变量 = 打开选择器，不预填内容。 */
enum class CapsuleKind { VERB, VARIABLE }

data class CapsuleSpec(
    val key: String,
    val label: String,
    val kind: CapsuleKind,
    val enabled: Boolean = true,
    val tag: String? = null,
)

/**
 * 胶囊行：回执下方的一排胶囊（规范见 docs/CAPSULE_LAYER_SPEC.md）。
 * 动词胶囊实底、变量胶囊描边；标签尽量四字以内；大字号下自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapsuleRow(
    capsules: List<CapsuleSpec>,
    onCapsuleClick: (CapsuleSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (capsules.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        capsules.forEach { spec ->
            Capsule(spec = spec, onClick = { onCapsuleClick(spec) })
        }
    }
}

@Composable
private fun Capsule(spec: CapsuleSpec, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val visuals = LocalReplandVisuals.current
    val solid = spec.kind == CapsuleKind.VERB
    val pressFeedback = pressMotion(interaction, spec.enabled)
    Surface(
        onClick = onClick,
        enabled = spec.enabled,
        shape = RoundedCornerShape(50),
        color = if (solid) visuals.mainAction else MaterialTheme.colorScheme.surface,
        contentColor = if (solid) visuals.onMainAction else MaterialTheme.colorScheme.onSurface,
        border = if (solid) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        interactionSource = interaction,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .then(if (spec.tag != null) Modifier.testTag(spec.tag!!) else Modifier)
            .then(pressFeedback),
    ) {
        Text(
            text = spec.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Preview(name = "胶囊行 · 浅色", showBackground = true)
@Composable
private fun CapsuleRowPreview() {
    ReplandTheme(darkTheme = false) {
        CapsuleRow(
            capsules = listOf(
                CapsuleSpec("postpone", "搁置", CapsuleKind.VERB),
                CapsuleSpec("next_free", "排进下一个空闲", CapsuleKind.VERB),
                CapsuleSpec("date", "排期", CapsuleKind.VARIABLE),
                CapsuleSpec("duration", "时长", CapsuleKind.VARIABLE),
                CapsuleSpec("undo", "撤销", CapsuleKind.VERB),
            ),
            onCapsuleClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
