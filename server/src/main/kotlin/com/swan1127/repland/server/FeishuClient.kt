package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 投递抽象：生产 = FeishuClient（真实 REST），测试 = 内存 fake。 */
interface FeishuSender {
    /** @return true = 投递成功（code==0）；false = 飞书侧拒绝（调用方进重试/降级） */
    suspend fun sendText(openId: String, message: String): Boolean

    /**
     * T4c：发送 interactive 卡片（§3/§4 卡片带按钮回调）。
     * 默认 false：文本-only 的 fake 无需改动即自动降级为 RETRY_LATER。
     */
    suspend fun sendInteractive(openId: String, cardJson: String): Boolean = false
}

/**
 * 飞书 im/v1 客户端（T4a：text 起步，卡片随后 —— §3）。
 * POST /open-apis/im/v1/messages?receive_id_type=open_id
 */
class FeishuClient(
    private val http: HttpClient,
    private val tokenManager: TokenManager,
    private val apiBase: String = "https://open.feishu.cn",
) : FeishuSender {
    /** @return true = 投递成功（code==0）；false = 飞书侧拒绝（调用方进重试/降级） */
    override suspend fun sendText(openId: String, message: String): Boolean {
        val token = tokenManager.get()
        val content = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(message))
            },
        )
        val response = http.post("$apiBase/open-apis/im/v1/messages?receive_id_type=open_id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "receive_id" to openId,
                    "msg_type" to "text",
                    "content" to content,
                )
            )
        }
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        // 飞书业务码在 data 之外顶层 code 字段
        val code = json["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        return code == 0
    }

    /** T4c：interactive 卡片投递，契约同 sendText（content 为整张卡片 JSON 字符串）。 */
    override suspend fun sendInteractive(openId: String, cardJson: String): Boolean {
        val token = tokenManager.get()
        val response = http.post("$apiBase/open-apis/im/v1/messages?receive_id_type=open_id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "receive_id" to openId,
                    "msg_type" to "interactive",
                    "content" to cardJson,
                )
            )
        }
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val code = json["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        return code == 0
    }
}
