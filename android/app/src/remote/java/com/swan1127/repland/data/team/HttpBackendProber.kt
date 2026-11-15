package com.swan1127.repland.data.team

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * /v1/me 连接探测（T4b 配置面，debug/internal 构建专属）：
 * 校验后端可达 + 账号 token 有效，并回显绑定与飞书状态。
 * 边界（§14）：含 HttpURLConnection，只在含 src/remote 的构建编译，main 反射加载。
 */
class HttpBackendProber : BackendProber {

    override suspend fun probe(baseUrl: String, accountToken: String): BackendProbeReport {
        if (baseUrl.isBlank() || accountToken.isBlank()) {
            return BackendProbeReport(false, 0, "请先填写后端地址与账号 token")
        }
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + "/v1/me").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Authorization", "Bearer $accountToken")
                when (val code = connection.responseCode) {
                    200 -> {
                        val json = JSONObject(connection.inputStream.bufferedReader().readText())
                        val role = if (json.optBoolean("isCaptain")) "队长" else "队员"
                        val feishu = if (json.optBoolean("feishuBound")) "飞书已绑定" else "飞书未绑定（仅 App 通知）"
                        BackendProbeReport(
                            ok = true,
                            httpCode = code,
                            message = "已连接：${json.optString("memberName")}（$role）· $feishu",
                            memberName = json.optString("memberName").takeIf { it.isNotEmpty() },
                            teamId = json.optString("teamId").takeIf { it.isNotEmpty() },
                            isCaptain = json.optBoolean("isCaptain"),
                            feishuBound = json.optBoolean("feishuBound"),
                        )
                    }
                    401 -> BackendProbeReport(false, code, "账号 token 无效（后端拒绝）")
                    else -> {
                        val detail = runCatching {
                            JSONObject(connection.errorStream.bufferedReader().readText()).optString("detail")
                        }.getOrNull()
                        BackendProbeReport(false, code, "后端返回 HTTP $code${detail?.let { "：$it" }.orEmpty()}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            BackendProbeReport(false, 0, "连接失败：${failure.message ?: "网络不可达"}")
        }
    }
}
