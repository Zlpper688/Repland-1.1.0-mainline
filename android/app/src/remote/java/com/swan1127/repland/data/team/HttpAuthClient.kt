package com.swan1127.repland.data.team

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

/**
 * 账号登录联网实现（T3 补齐，debug/internal 构建专属）：
 * 手机验证码 + 微信配对双通道（TEAM_COLLAB_DESIGN.md §13）。
 * 边界（§14）：含 HttpURLConnection，只在含 src/remote 的构建编译，main 反射加载。
 */
class HttpAuthClient : SmsCodeRequester, SmsVerifier, WechatQrStarter, WechatQrPoller,
    WechatAvatarDownloader, AccountLogout, AccountMeFetcher {

    override suspend fun logout(baseUrl: String, accountToken: String): Boolean {
        if (baseUrl.isBlank() || accountToken.isBlank()) return false
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + "/v1/auth/logout").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $accountToken")
                connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                // 服务器对 not_found 也回 200：以 body 的 status 为准
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                JSONObject(stream.bufferedReader().readText()).optString("status") == "ok"
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    override suspend fun fetch(baseUrl: String, accountToken: String): AccountMeInfo? {
        if (baseUrl.isBlank() || accountToken.isBlank()) return null
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + "/v1/auth/account-me").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Authorization", "Bearer $accountToken")
                if (connection.responseCode !in 200..299) return null
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val memberships = json.optJSONArray("memberships")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { m ->
                            AccountMembershipInfo(
                                teamId = m.optString("teamId"),
                                memberId = m.optString("memberId"),
                                memberName = m.optString("memberName"),
                                isCaptain = m.optBoolean("isCaptain"),
                            )
                        }
                    }
                }.orEmpty()
                AccountMeInfo(
                    accountId = json.optString("accountId"),
                    phoneMasked = json.optString("phoneMasked"),
                    wechatBound = json.optBoolean("wechatBound"),
                    displayName = json.optString("displayName"),
                    memberships = memberships,
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun request(baseUrl: String, phone: String): SmsCodeResult =
        call(baseUrl, "/v1/auth/sms/request", JSONObject().put("phone", phone)) { json ->
            val status = json.optString("status")
            SmsCodeResult(
                ok = status == "sent",
                message = when (status) {
                    "sent" -> "验证码已发送"
                    "rate_limited" -> "发送过于频繁，${json.optInt("retryAfterSeconds")} 秒后重试"
                    else -> json.optString("detail").ifEmpty { "验证码请求失败" }
                },
                devCode = json.optString("devCode").takeIf { !json.isNull("devCode") && it.matches(Regex("[0-9]{6}")) },
                retryAfterSeconds = json.optInt("retryAfterSeconds"),
            )
        }

    override suspend fun verify(
        baseUrl: String,
        phone: String,
        code: String,
        displayName: String,
        teamId: String,
        memberId: String,
    ): AuthLoginResult = call(
        baseUrl,
        "/v1/auth/sms/verify",
        JSONObject()
            .put("phone", phone)
            .put("code", code)
            .put("displayName", displayName)
            .put("teamId", teamId)
            .put("memberId", memberId),
    ) { json -> loginResult(json) }

    override suspend fun start(baseUrl: String): WechatQrStartResult = call(
        baseUrl,
        "/v1/auth/wechat/qr/start",
        JSONObject(),
    ) { json ->
        WechatQrStartResult(
            ok = json.optString("status") == "started",
            message = json.optString("detail").ifBlank { "微信扫码登录暂不可用" },
            authUrl = json.optString("authUrl"),
            pollToken = json.optString("pollToken"),
            expiresInSeconds = json.optInt("expiresInSeconds"),
        )
    }

    override suspend fun poll(baseUrl: String, pollToken: String): AuthLoginResult =
        call(baseUrl, "/v1/auth/wechat/qr/poll", JSONObject().put("pollToken", pollToken), ::loginResult)

    override suspend fun download(avatarUrl: String): ByteArray? = runCatching {
        val url = URL(avatarUrl)
        if (url.protocol != "https" || url.userInfo != null ||
            (url.host != "qlogo.cn" && !url.host.endsWith(".qlogo.cn"))) return null
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentType?.startsWith("image/") != true) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    // ---------- 内部 ----------

    private fun loginResult(json: JSONObject): AuthLoginResult {
        val status = json.optString("status")
        return AuthLoginResult(
            ok = status == "ok",
            message = json.optString("detail").ifEmpty {
                if (status == "ok") "登录成功" else "暂时无法登录，请重试"
            },
            token = json.optString("token"),
            accountId = json.optString("accountId"),
            displayName = json.optString("displayName"),
            created = json.optBoolean("created"),
            avatarUrl = json.optString("avatarUrl"),
            pending = status == "pending",
        )
    }

    private inline fun <T> call(
        baseUrl: String,
        path: String,
        body: JSONObject,
        parse: (JSONObject) -> T,
    ): T {
        if (baseUrl.isBlank()) {
            return parse(JSONObject().put("status", "no_base_url").put("detail", "请先填写并保存后端地址"))
        }
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val json = stream?.bufferedReader()?.use { reader ->
                    runCatching { JSONObject(reader.readText()) }.getOrNull()
                }
                val detail = if (path.startsWith("/v1/auth/wechat/qr/") && status == 404) {
                    "当前服务器还未开通微信扫码登录"
                } else {
                    "登录服务暂时不可用，请稍后重试"
                }
                parse(json ?: JSONObject().put("status", "server_error").put("detail", detail))
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            parse(
                JSONObject()
                    .put("status", "network_error")
                    .put("detail", when (failure) {
                        is SocketTimeoutException -> "服务器连接超时，请检查网络后重试"
                        is SSLException -> "无法与登录服务器建立安全连接，请稍后重试"
                        else -> "无法连接登录服务器，请检查网络后重试"
                    }),
            )
        }
    }
}
