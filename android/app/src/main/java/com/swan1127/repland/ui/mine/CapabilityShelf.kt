package com.swan1127.repland.ui.mine

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.ui.notifications.NotificationAccess

/** 连接器三档状态语言：已连接 / 未连接 / 需要处理；做不到的事直说「暂不支持」，不画假勾。 */
enum class CapabilityState { CONNECTED, NOT_CONNECTED, NEEDS_ATTENTION, UNAVAILABLE }

/** 云端 AI 的连通判定与群聊 cloudEnabled 同口径：开关、同意、密钥、构建能力缺一不可。 */
fun cloudCapabilityState(
    aiEnabled: Boolean,
    consented: Boolean,
    hasApiKey: Boolean,
    remoteSupported: Boolean,
): CapabilityState = when {
    !remoteSupported -> CapabilityState.UNAVAILABLE
    !aiEnabled || !consented || !hasApiKey -> CapabilityState.NOT_CONNECTED
    else -> CapabilityState.CONNECTED
}

private data class TrustCopy(val benefit: String, val control: String, val attention: String)

private data class ShelfRow(
    val key: String,
    val title: String,
    val benefit: String,
    val state: CapabilityState,
    val statusText: String,
    val action: (() -> Unit)?,
    val trust: TrustCopy?,
)

/**
 * 能力货架：助手真正用到的本机与云端能力，一行一个状态。
 * 收在设置页的折叠区里，不占常驻版面；授权前先说清三段信任框架（借鉴 Today 连接器授权弹层）。
 */
@Composable
fun CapabilityShelf(
    notificationAccess: NotificationAccess,
    exactAlarmsGranted: Boolean,
    batteryExempted: Boolean,
    micGranted: Boolean,
    voiceServiceAvailable: Boolean,
    aiEnabled: Boolean,
    aiConsented: Boolean,
    hasApiKey: Boolean,
    remoteAiSupported: Boolean,
    teamSyncConnected: Boolean,
    onOpenNotifications: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenMic: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenTeamSync: () -> Unit,
) {
    var trustFor by rememberSaveable { mutableStateOf<String?>(null) }
    val exactAlarmNative = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val cloudState = cloudCapabilityState(aiEnabled, aiConsented, hasApiKey, remoteAiSupported)
    val rows = listOf(
        ShelfRow(
            key = "notifications", title = "到点提醒", benefit = "日程到点响铃，重要的事不靠记性",
            state = when (notificationAccess) {
                NotificationAccess.Allowed -> CapabilityState.CONNECTED
                NotificationAccess.NeedsPermission -> CapabilityState.NOT_CONNECTED
                NotificationAccess.BlockedInSettings -> CapabilityState.NEEDS_ATTENTION
            },
            statusText = when (notificationAccess) {
                NotificationAccess.Allowed -> "已连接"
                NotificationAccess.NeedsPermission -> "去开启"
                NotificationAccess.BlockedInSettings -> "去设置"
            },
            action = onOpenNotifications,
            trust = TrustCopy(
                benefit = "按提前量响铃",
                control = "可随时收回权限；已排日程不删",
                attention = "省电或勿扰可能延迟",
            ),
        ),
        ShelfRow(
            key = "exact-alarm", title = "准时提醒", benefit = "闹钟级精确到点，不被系统攒着延迟",
            state = if (exactAlarmsGranted) CapabilityState.CONNECTED else CapabilityState.NOT_CONNECTED,
            statusText = if (exactAlarmsGranted) (if (exactAlarmNative) "系统自带" else "已连接") else "去开启",
            action = if (exactAlarmsGranted) null else onOpenExactAlarm,
            trust = TrustCopy(
                benefit = "按分钟准点响",
                control = "可随时关闭；关闭后可能延迟",
                attention = "厂商省电策略不同",
            ),
        ),
        ShelfRow(
            key = "battery", title = "后台保活", benefit = "锁屏后提醒仍有机会按时送达",
            state = if (batteryExempted) CapabilityState.CONNECTED else CapabilityState.NOT_CONNECTED,
            statusText = if (batteryExempted) "已豁免" else "去设置",
            action = if (batteryExempted) null else onOpenBattery,
            trust = TrustCopy(
                benefit = "减少后台掐断",
                control = "可随时改回；已排日程不变",
                attention = "仍以实际响铃为准",
            ),
        ),
        ShelfRow(
            key = "voice", title = "语音输入", benefit = "说一句话就能建日程、问安排",
            state = when {
                !voiceServiceAvailable -> CapabilityState.UNAVAILABLE
                micGranted -> CapabilityState.CONNECTED
                else -> CapabilityState.NOT_CONNECTED
            },
            statusText = when {
                !voiceServiceAvailable -> "本机无语音服务"
                micGranted -> "已连接"
                else -> "去开启"
            },
            action = if (!voiceServiceAvailable) null else if (micGranted) null else onOpenMic,
            trust = TrustCopy(
                benefit = "语音直接说安排",
                control = "系统识别，不保存音频",
                attention = "发送前检查文字",
            ),
        ),
        ShelfRow(
            key = "cloud-ai", title = "云端 AI", benefit = "更深的语义理解与建议（可选）",
            state = cloudState,
            statusText = when {
                cloudState == CapabilityState.CONNECTED -> "已连接"
                cloudState == CapabilityState.UNAVAILABLE -> "此构建仅本地"
                !aiEnabled -> "开关未开"
                !aiConsented -> "未同意授权"
                else -> "未填密钥"
            },
            action = if (cloudState == CapabilityState.UNAVAILABLE) null else onOpenAiSettings,
            trust = null,
        ),
        ShelfRow(
            key = "team-sync", title = "队伍同步", benefit = "队伍任务与排期同步到服务器",
            state = if (teamSyncConnected) CapabilityState.CONNECTED else CapabilityState.NOT_CONNECTED,
            statusText = if (teamSyncConnected) "已连接" else "去队伍页",
            action = onOpenTeamSync,
            trust = null,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("capability-shelf")) {
        rows.forEach { row ->
            Surface(
                onClick = {
                    if (row.trust != null && row.action != null) trustFor = row.key else row.action?.invoke()
                },
                enabled = row.action != null,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().testTag("capability-${row.key}"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusGlyph(row.state)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        row.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (row.state) {
                            CapabilityState.CONNECTED -> MaterialTheme.colorScheme.primary
                            CapabilityState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
    val trustRow = rows.firstOrNull { it.key == trustFor }
    if (trustRow?.trust != null && trustRow.action != null) {
        AlertDialog(
            onDismissRequest = { trustFor = null },
            modifier = Modifier.testTag("capability-trust-dialog"),
            title = { Text(trustRow.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrustSection("让助手更好地帮你", trustRow.trust.benefit)
                    TrustSection("数据由你掌控", trustRow.trust.control)
                    TrustSection("需要保持关注", trustRow.trust.attention)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { val action = trustRow.action; trustFor = null; action() },
                    modifier = Modifier.testTag("capability-trust-confirm"),
                ) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { trustFor = null }, modifier = Modifier.testTag("capability-trust-dismiss")) {
                    Text("先不了")
                }
            },
        )
    }
}

@Composable
private fun TrustSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusGlyph(state: CapabilityState) {
    val (glyph, tint, background) = when (state) {
        CapabilityState.CONNECTED -> Triple("✓", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        CapabilityState.NOT_CONNECTED -> Triple("+", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
        CapabilityState.NEEDS_ATTENTION -> Triple("!", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
        CapabilityState.UNAVAILABLE -> Triple("–", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Surface(color = background, shape = CircleShape, modifier = Modifier.size(26.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}