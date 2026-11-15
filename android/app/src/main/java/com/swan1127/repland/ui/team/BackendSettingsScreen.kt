package com.swan1127.repland.ui.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swan1127.repland.data.team.AccountMeInfo
import com.swan1127.repland.data.team.BackendProbeReport
import com.swan1127.repland.ui.components.DesignOutlinedButton
import kotlinx.coroutines.launch

/**
 * 账号与服务器（原「后端与飞书连接」瘦身版）：
 * 登录/登出入口已上移到登录界面（SessionGateScreen）；这里只管登录后的账号信息
 * 与官方服务器的连通性。服务器出厂预置（BackendConfigStore.DEFAULT_BASE_URL），
 * 不再有队长自建、手发 token 的路径。
 */
@Composable
fun BackendSettingsScreen(vm: TeamViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var probing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<BackendProbeReport?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← 返回") }
                Text(
                    "账号与服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // ── 当前账号（§13 登录后展示与登出；登出后回到登录界面） ─────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var me by remember { mutableStateOf<AccountMeInfo?>(null) }
                    var loadingMe by remember { mutableStateOf(true) }
                    var logoutHint by remember { mutableStateOf<String?>(null) }
                    var logoutOk by remember { mutableStateOf(false) }
                    val meScope = rememberCoroutineScope()
                    LaunchedEffect(Unit) {
                        me = vm.fetchAccountMe()
                        loadingMe = false
                    }
                    when {
                        loadingMe -> Text(
                            "读取中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        me != null -> {
                            Text(
                                "${me!!.displayName}（${me!!.phoneMasked.ifEmpty { "未绑手机号" }}）",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (me!!.memberships.isNotEmpty()) {
                                Text(
                                    "成员位：" + me!!.memberships.joinToString("；") { slot ->
                                        "${slot.memberName} · ${slot.teamId}" + if (slot.isCaptain) "（队长）" else ""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DesignOutlinedButton(
                                onClick = {
                                    meScope.launch {
                                        val (ok, msg) = vm.logoutAccount()
                                        logoutHint = msg
                                        logoutOk = ok
                                        if (ok) me = null
                                    }
                                },
                                enabled = !loadingMe,
                                modifier = Modifier.testTag("account-logout"),
                            ) { Text("退出登录") }
                        }
                        else -> {
                            Text(
                                "未登录 · 团队与云端记忆不可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DesignOutlinedButton(
                                onClick = { vm.openLoginGate() },
                                modifier = Modifier.testTag("account-go-login"),
                            ) { Text("去登录") }
                        }
                    }
                    logoutHint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (logoutOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("account-logout-hint"),
                        )
                    }
                }
            }

            // ── 官方服务器（预置地址 + 连通性自检） ─────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("官方服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        vm.backendBaseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("backend-base-url"),
                    )
                    Text(
                        if (vm.backendConfigured) "已登录：推送、同步、台账与云端记忆通道已启用。"
                        else "未登录：全部通知仅存 App 本地（离线模式）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DesignOutlinedButton(
                        {
                            probing = true
                            report = null
                            scope.launch {
                                report = vm.probeBackend(vm.backendBaseUrl, vm.backendAccountToken)
                                probing = false
                            }
                        },
                        modifier = Modifier.testTag("backend-probe"),
                        enabled = !probing,
                    ) { Text(if (probing) "测试中…" else "测试连接") }
                    report?.let { r ->
                        Text(
                            r.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("backend-probe-result"),
                        )
                    }
                }
            }
        }
    }
}
