package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 多维表格追加抽象（T4c，FEISHU_INTEGRATION_NOTES §5）：生产 = 真实 REST，测试 = 内存 fake。
 */
interface BitableWriter {
    /** @return true = 全部写入成功（code==0）；false = 飞书侧拒绝（调用方进重试/降级） */
    suspend fun appendRecords(records: List<Map<String, Any?>>): Boolean
}

/**
 * 多维表格 v1 客户端（§5：只做副本不做同步）。
 * POST /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records/batch_create（权限 bitable:app）
 * 表结构按 §5：时间/成员/类型/事项/证据链接/金额/是否基线，另加「队伍」列（自托管单表承载多队伍）。
 */
class FeishuBitableClient(
    private val http: HttpClient,
    private val tokenManager: TokenManager,
    private val apiBase: String,
    private val appToken: String,
    private val tableId: String,
) : BitableWriter {

    override suspend fun appendRecords(records: List<Map<String, Any?>>): Boolean {
        val token = tokenManager.get()
        val body = buildJsonObject {
            put(
                "records",
                buildJsonArray {
                    records.forEach { fields ->
                        add(
                            buildJsonObject {
                                put(
                                    "fields",
                                    buildJsonObject {
                                        fields.forEach { (key, value) -> if (value != null) put(key, fieldJson(value)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        val response = http.post("$apiBase/open-apis/bitable/v1/apps/$appToken/tables/$tableId/records/batch_create") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        val json = Json.parseToJsonElement(text).jsonObject
        val code = json["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        return code == 0
    }

    /** bitable 字段类型：数值/布尔按原类型写，其余按文本。 */
    private fun fieldJson(value: Any): JsonPrimitive = when (value) {
        is Long -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
