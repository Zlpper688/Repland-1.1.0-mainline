package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exchanges an OAuth code on the server. The QR login also requests the authorized profile. */
class WechatHttpClient(
    private val http: HttpClient,
    private val appId: String,
    private val appSecret: String,
    private val apiBase: String = "https://api.weixin.qq.com",
    private val fetchProfile: Boolean = false,
) : WechatCodeExchanger {

    override suspend fun exchange(code: String): WechatIdentity? = try {
        withTimeoutOrNull(10_000) { exchangeIdentity(code) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun exchangeIdentity(code: String): WechatIdentity? {
        val token = http.get("$apiBase/sns/oauth2/access_token") {
            parameter("appid", appId)
            parameter("secret", appSecret)
            parameter("code", code)
            parameter("grant_type", "authorization_code")
        }.bodyAsText().asObject()
        val openId = token.value("openid")
        if (openId.isEmpty()) return null
        val unionId = token.value("unionid")
        if (!fetchProfile) return WechatIdentity(unionId.ifEmpty { openId }, openId)

        val accessToken = token.value("access_token")
        if (accessToken.isEmpty()) return null
        val profile = http.get("$apiBase/sns/userinfo") {
            parameter("access_token", accessToken)
            parameter("openid", openId)
            parameter("lang", "zh_CN")
        }.bodyAsText().asObject()
        if (profile.value("openid") != openId) return null
        val avatar = profile.value("headimgurl").let { url ->
            val uri = runCatching { URI(url) }.getOrNull()
            val host = uri?.host.orEmpty()
            if (uri?.userInfo == null && (host == "qlogo.cn" || host.endsWith(".qlogo.cn"))) {
                when (uri?.scheme) {
                    "https" -> url
                    "http" -> "https" + url.removePrefix("http")
                    else -> ""
                }
            } else ""
        }
        return WechatIdentity(
            unionId.ifEmpty { profile.value("unionid").ifEmpty { openId } },
            openId,
            profile.value("nickname").take(120),
            avatar,
        )
    }

    private fun String.asObject() = Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.value(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
