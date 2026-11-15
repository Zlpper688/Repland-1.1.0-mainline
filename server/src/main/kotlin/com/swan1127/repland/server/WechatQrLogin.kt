package com.swan1127.repland.server

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

class WechatQrLogin(
    private val auth: AuthService,
    private val appId: String,
    callbackUrl: String,
    private val exchanger: WechatCodeExchanger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val redirect = URLEncoder.encode(callbackUrl, Charsets.UTF_8)

    init {
        val uri = URI(callbackUrl)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.query == null && uri.fragment == null &&
            uri.path.endsWith("/v1/auth/wechat/qr/callback")) { "Invalid WeChat QR callback URL" }
    }

    private data class Session(val expiresAt: Long, var exchanging: Boolean = false, var result: LoginResponse? = null)

    private val sessions = mutableMapOf<String, Session>()
    private val random = SecureRandom()

    @Synchronized
    fun start(): WechatQrStartResponse {
        val now = clock()
        sessions.values.removeIf { it.expiresAt <= now }
        if (sessions.size >= 500) return WechatQrStartResponse("busy", "扫码登录繁忙，请稍后重试")

        // Only the hash travels through the browser. The app keeps a separate polling credential.
        val pollToken = ByteArray(32).also(random::nextBytes).toHex()
        val state = stateFor(pollToken)
        sessions[state] = Session(now + VALIDITY_SECONDS * 1000L)
        val encodedAppId = URLEncoder.encode(appId, Charsets.UTF_8)
        val url = "https://open.weixin.qq.com/connect/qrconnect" +
            "?appid=$encodedAppId&redirect_uri=$redirect" +
            "&response_type=code&scope=snsapi_login&state=$state#wechat_redirect"
        return WechatQrStartResponse("started", authUrl = url, pollToken = pollToken, expiresInSeconds = VALIDITY_SECONDS)
    }

    @Synchronized
    fun poll(pollToken: String): LoginResponse {
        if (!pollToken.matches(Regex("[0-9a-f]{64}"))) return expired()
        val session = sessions[stateFor(pollToken)]?.takeIf { it.expiresAt > clock() } ?: return expired()
        return session.result ?: LoginResponse("pending")
    }

    suspend fun complete(state: String, code: String): Boolean {
        val session = synchronized(this) {
            val pending = sessions[state]?.takeIf { it.expiresAt > clock() } ?: return false
            if (pending.result != null) return pending.result?.status == "ok"
            if (pending.exchanging) return false
            if (code.isBlank()) {
                pending.result = LoginResponse("denied", "微信授权已取消，请重新扫码")
                return false
            }
            pending.exchanging = true
            pending
        }
        val identity = try {
            exchanger.exchange(code)
        } catch (cancelled: CancellationException) {
            synchronized(this) { session.exchanging = false }
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return synchronized(this) {
            if (session.expiresAt <= clock()) return false
            val result = identity?.let(auth::loginWithWechatProfile)
                ?: LoginResponse("oauth_error", "微信授权未完成，请重新扫码")
            session.result = result
            result.status == "ok"
        }
    }

    private fun expired() = LoginResponse("expired", "二维码已过期，请刷新")

    private fun stateFor(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val VALIDITY_SECONDS = 300
    }
}
