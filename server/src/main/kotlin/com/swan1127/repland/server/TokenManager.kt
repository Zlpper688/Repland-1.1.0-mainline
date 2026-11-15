package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** token 拉取抽象：生产 = 真实 REST，测试 = 内存 fake（避免 HTTP mock 的时序干扰）。 */
fun interface TokenFetcher {
    /** @return (token, 有效期秒) */
    suspend fun fetch(): Pair<String, Long>
}

/**
 * 飞书 tenant_access_token 单飞缓存（FEISHU_INTEGRATION_NOTES §2）：
 * - 有效期约 2 小时，缓存并提前 ≥5 分钟刷新；
 * - 并发请求经 Mutex 合并为一次取 token（single-flight）；
 * - 拉取失败重试一次，再失败抛出（由调用方降级）。
 */
class TokenManager(
    private val fetcher: TokenFetcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedExpiresAt: Long = 0

    suspend fun get(): String = mutex.withLock {
        val now = clock()
        val cached = cachedToken
        if (cached != null && cachedExpiresAt - now > REFRESH_AHEAD_MS) return cached
        fetchWithRetry(now)
    }

    private suspend fun fetchWithRetry(now: Long): String {
        try {
            return fetch(now)
        } catch (first: Exception) {
            try {
                return fetch(now)
            } catch (second: Exception) {
                throw IllegalStateException("获取 tenant_access_token 失败（已重试一次）", second)
            }
        }
    }

    private suspend fun fetch(now: Long): String {
        val (token, expireSeconds) = fetcher.fetch()
        cachedToken = token
        cachedExpiresAt = now + expireSeconds * 1000
        return token
    }

    companion object {
        /** 提前 5 分钟刷新（§2.2） */
        const val REFRESH_AHEAD_MS: Long = 5 * 60 * 1000

        /** 生产 fetcher：调飞书 tenant_access_token/internal */
        fun defaultFetcher(
            http: HttpClient,
            appId: String,
            appSecret: String,
            apiBase: String,
        ): TokenFetcher = TokenFetcher {
            val response = http.post("$apiBase/open-apis/auth/v3/tenant_access_token/internal") {
                contentType(ContentType.Application.Json)
                // 共享 HttpClient 未装 ContentNegotiation：显式 JSON 字符串体（Map 会在运行期序列化失败）
                setBody(
                    buildJsonObject {
                        put("app_id", appId)
                        put("app_secret", appSecret)
                    }.toString(),
                )
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val code = json["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (code != 0) error("tenant_access_token 接口返回 code=$code body=$body")
            val token = json["tenant_access_token"]?.jsonPrimitive?.content
                ?: error("tenant_access_token 响应缺少 token 字段")
            val expireSeconds = json["expire"]?.jsonPrimitive?.content?.toLongOrNull() ?: 7200L
            token to expireSeconds
        }
    }
}
