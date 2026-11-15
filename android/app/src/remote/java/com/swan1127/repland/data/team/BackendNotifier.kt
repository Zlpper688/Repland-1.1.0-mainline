package com.swan1127.repland.data.team

import com.swan1127.repland.domain.team.NotificationChannel
import com.swan1127.repland.domain.team.TeamNotification
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 队伍通知的飞书推送出口（T4a，FEISHU_INTEGRATION_NOTES §2 链路的 App 侧末端）：
 * - 只投递 PUSH_AND_FEISHU（APP_ONLY 在 [dispatch] 前由调用方过滤亦可，接口内再兜底）；
 * - open_id 绑定关系完全在后端，App 只上报队伍/受众标识；
 * - 未配置后端时由 store 用 [NoopNotifier]，完全零开销（§3 降级语义）。
 *
 * 边界（§14 照搬 src/remote 模式）：本实现含 HttpURLConnection，只在 debug/internal/
 * designPreview 构建编译；main 通过反射加载，release 无此类、无 INTERNET 权限。
 */
class BackendNotifier(
    private val baseUrl: String,
    private val accountToken: String,
) : TeamNotifier {

    override suspend fun dispatch(
        notification: TeamNotification,
        teamId: String,
        teamName: String,
        audienceMemberId: String?,
    ) {
        if (notification.channel != NotificationChannel.PUSH_AND_FEISHU) return
        val audienceId = audienceMemberId ?: return
        val body = JSONObject().apply {
            put("teamId", teamId)
            put("teamName", teamName)
            put("audienceId", audienceId)
            put("audienceName", notification.audience)
            put("channel", notification.channel.name)
            put("message", notification.message)
            put("notificationId", notification.notificationId)
            // T4c：卡片元数据 → 后端构造 im/v1 interactive 卡片（按钮回调走 CardActionService）
            notification.card?.let { c ->
                put(
                    "card",
                    JSONObject().put("cardKind", c.cardKind).put("refId", c.refId).put("revision", c.revision),
                )
            }
        }.toString()
        runCatching {
            val connection = URL(baseUrl.trimEnd('/') + "/v1/notify").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $accountToken")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                // 响应状态不阻断主流程；401/403/5xx 一律视为后端降级
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }
}
