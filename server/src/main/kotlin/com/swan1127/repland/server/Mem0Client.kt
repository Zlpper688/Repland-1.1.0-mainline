package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * mem0 远端记忆同步层（§12 后置项，T5 收尾）：
 * - 服务端持有 MEM0_API_KEY（BYOK），客户端只见「后端地址 + 账号 token」（§13）；
 * - 可选增强：本地 JSON 仍是主副本（离线优先不变），云端只做沉淀与语义检索；
 * - 账号域隔离：user_id = accountId，个人画像与参赛档案按 kind 元数据区分。
 *
 * 线上契约（docs.mem0.ai/api-reference，2026-10 核实）：
 * - 鉴权头 `Authorization: Token <key>` + `Accept: application/json`（必填）；
 * - add = POST /v3/memories/add/（additive 管线，异步抽取，entity ID 顶层可放）；
 * - search = POST /v3/memories/search/（hybrid 检索；entity ID 必须在 filters 内，顶层 400）。
 */

@Serializable
data class MemorySyncRequest(
    val facts: List<String> = emptyList(),
    val archives: List<String> = emptyList(),
)

@Serializable
data class MemorySyncResponse(
    val status: String,
    val detail: String = "",
    val accepted: Int = 0,
    val skipped: Int = 0,
)

@Serializable
data class MemorySearchRequest(val query: String)

@Serializable
data class MemorySearchResponse(
    val status: String,
    val detail: String = "",
    val memories: List<String> = emptyList(),
)

/** mem0 网关（可注入 stub 便于测试）；失败返回空/false 并记录日志，由调用方聚合为 accepted/skipped。 */
interface Mem0Gateway {
    suspend fun add(userId: String, text: String, kind: String): Boolean
    suspend fun search(userId: String, query: String): List<String>
}

/** mem0 平台 REST 默认实现（v3 端点；Token 鉴权）。 */
class HttpMem0Client(
    private val http: HttpClient,
    private val apiKey: String,
    private val apiBase: String = "https://api.mem0.ai",
) : Mem0Gateway {

    private val log = LoggerFactory.getLogger(HttpMem0Client::class.java)

    override suspend fun add(userId: String, text: String, kind: String): Boolean {
        val outcome = runCatching {
            val response = http.post("$apiBase/v3/memories/add/") {
                headers {
                    append(HttpHeaders.Authorization, "Token $apiKey")
                    append(HttpHeaders.Accept, "application/json")
                }
                contentType(ContentType.Application.Json)
                // 共享 HttpClient 未装 ContentNegotiation：显式 JSON 字符串体（Map 会在运行期序列化失败）
                setBody(
                    buildJsonObject {
                        put("messages", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("role", "user")
                                put("content", text)
                            })
                        })
                        put("user_id", userId)
                        put("metadata", buildJsonObject {
                            put("kind", kind)
                            put("source", "repland")
                        })
                    }.toString(),
                )
            }
            Triple(response.status.value, response.bodyAsText(), userId)
        }
        val (status, body, _) = outcome.getOrElse { failure ->
            log.warn("mem0 add 异常：{}", failure.message)
            return false
        }
        if (status !in 200..299) {
            log.warn("mem0 add 失败：status={} body={}", status, body.take(300))
            return false
        }
        return true
    }

    override suspend fun search(userId: String, query: String): List<String> {
        val outcome = runCatching {
            val response = http.post("$apiBase/v3/memories/search/") {
                headers {
                    append(HttpHeaders.Authorization, "Token $apiKey")
                    append(HttpHeaders.Accept, "application/json")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("query", query)
                        // v3：entity ID 必须在 filters 内（顶层被 400 拒绝）；AND 包装为官方示例格式
                        put("filters", buildJsonObject {
                            put("AND", kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.buildJsonObject { put("user_id", userId) })
                            })
                        })
                    }.toString(),
                )
            }
            Triple(response.status.value, response.bodyAsText(), userId)
        }
        val (status, body, _) = outcome.getOrElse { failure ->
            log.warn("mem0 search 异常：{}", failure.message)
            return emptyList()
        }
        if (status !in 200..299) {
            log.warn("mem0 search 失败：status={} body={}", status, body.take(300))
            return emptyList()
        }
        return runCatching { parseMemories(body) }
            .onFailure { log.warn("mem0 search 响应解析失败：{}", it.message) }
            .getOrDefault(emptyList())
    }

    /** 解析 search 响应：v3/v2 返回 {"results":[{memory,...}]}，兼容 v1 直接返回数组。 */
    private fun parseMemories(body: String): List<String> {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
        val items = when (root) {
            is kotlinx.serialization.json.JsonObject -> root["results"]?.jsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList())
            is kotlinx.serialization.json.JsonArray -> root
            else -> kotlinx.serialization.json.JsonArray(emptyList())
        }
        return items.mapNotNull { item ->
            item.jsonObject["memory"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    }
}
