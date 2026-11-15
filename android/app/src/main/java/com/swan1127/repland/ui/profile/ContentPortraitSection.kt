package com.swan1127.repland.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.model.ContentPortrait
import com.swan1127.repland.domain.model.PortraitEvidence
import com.swan1127.repland.domain.model.PortraitMaterialLine
import com.swan1127.repland.domain.model.PortraitPoint
import com.swan1127.repland.ui.components.EditorSheet

/**
 * 助手对这个用户的理解。模型写结论，材料是依据，用户随时更正。
 * 授权前能先看到将被发送的每一条材料；界面不解释画像是什么，结论和依据自己说话。
 */
@Composable
fun ContentPortraitSection(
    portrait: ContentPortrait,
    material: List<PortraitMaterialLine>,
    busy: Boolean,
    canGenerate: Boolean,
    error: String?,
    onConsentAndGenerate: () -> Unit,
    onGenerate: () -> Unit,
    onRevokeConsent: () -> Unit,
    onAddCorrection: (String) -> Unit,
    onRemoveCorrection: (Int) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().testTag("portrait-open"),
    ) { Text("助手怎么理解我") }
    if (!open) return

    EditorSheet(
        onDismissRequest = { open = false },
        saving = false,
        title = { Text("助手怎么理解我") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("portrait-scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("portrait-error"))
                }
                if (!portrait.hasContentConsent) {
                    MaterialPreview(material)
                    Button(
                        onClick = onConsentAndGenerate,
                        enabled = !busy && material.size >= 3 && canGenerate,
                        modifier = Modifier.fillMaxWidth().testTag("portrait-consent-generate"),
                    ) {
                        Text(
                            when {
                                busy -> "正在读你的记录…"
                                !canGenerate -> "先连接模型"
                                else -> "同意发送这些内容并生成"
                            },
                        )
                    }
                } else {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Text("正在读你的记录…")
                        }
                    }
                    portrait.points.forEachIndexed { index, point ->
                        PortraitPointCard(portrait, point, "portrait-point-$index")
                    }
                    if (portrait.points.isEmpty() && !busy) {
                        Button(
                            onClick = onGenerate,
                            enabled = material.size >= 3 && canGenerate,
                            modifier = Modifier.fillMaxWidth().testTag("portrait-generate"),
                        ) { Text(if (canGenerate) "开始理解" else "先连接模型") }
                    }
                    HorizontalDivider()
                    Text("我的更正", style = MaterialTheme.typography.titleSmall)
                    portrait.corrections.forEachIndexed { index, correction ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(correction, modifier = Modifier.padding(end = 8.dp))
                            IconButton(
                                onClick = { onRemoveCorrection(index) },
                                modifier = Modifier.testTag("portrait-remove-correction-$index"),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "删除这条更正")
                            }
                        }
                    }
                    CorrectionInput(onAddCorrection)
                    if (!busy) {
                        TextButton(
                            onClick = onGenerate,
                            enabled = material.size >= 3 && canGenerate,
                            modifier = Modifier.testTag("portrait-regenerate"),
                        ) { Text("重新理解") }
                        TextButton(
                            onClick = onRevokeConsent,
                            modifier = Modifier.testTag("portrait-revoke"),
                        ) { Text("撤回内容授权") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ open = false }, modifier = Modifier.testTag("portrait-close")) { Text("关闭") }
        },
    )
}

@Composable
private fun PortraitPointCard(portrait: ContentPortrait, point: PortraitPoint, tag: String) {
    var showEvidence by rememberSaveable(tag) { mutableStateOf(false) }
    val evidence = portrait.evidenceOf(point)
    Surface(
        onClick = { showEvidence = !showEvidence },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(point.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(
                    if (showEvidence) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (showEvidence) "收起依据" else "展开依据",
                )
            }
            if (showEvidence) {
                evidence.forEach { entry -> Text(evidenceLine(entry)) }
            }
        }
    }
}

private fun evidenceLine(entry: PortraitEvidence): String = "[${entry.id}] ${entry.text}"

@Composable
private fun MaterialPreview(material: List<PortraitMaterialLine>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) material else material.take(6)
    Text("将会发送的内容", style = MaterialTheme.typography.titleSmall)
    shown.forEach { line -> Text("[${line.id}] ${line.text}", style = MaterialTheme.typography.bodySmall) }
    if (material.size > 6) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("portrait-material-toggle"),
        ) { Text(if (expanded) "收起" else "还有 ${material.size - 6} 条") }
    }
    if (material.size < 3) {
        Text("记录还不够", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CorrectionInput(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("补充一条") },
            modifier = Modifier.weight(1f).testTag("portrait-correction-input"),
        )
        TextButton(
            onClick = {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    onAdd(trimmed)
                    text = ""
                }
            },
            modifier = Modifier.testTag("portrait-add-correction"),
        ) { Text("加上") }
    }
}
