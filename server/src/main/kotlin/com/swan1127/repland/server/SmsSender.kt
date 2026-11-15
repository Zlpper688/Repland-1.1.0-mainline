package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * 短信发送抽象（T3 补齐，§13 凭证哲学：短信网关凭证全部服务端持有）。
 * - 未配置网关或 REPLAND_SMS_DEV_MODE=1 → [LoggingSmsSender]（日志出码 + 响应回显 devCode，开发/自托管起步用）；
 * - 生产接入：[WebhookSmsSender] POST {"phone","code"} 到自配网关（服务商侧模板与签名由网关承担）。
 */
fun interface SmsSender {
    /** @return 是否受理成功（网关失败不阻断流程，调用方提示重试） */
    suspend fun send(phone: String, code: String): Boolean
}

class LoggingSmsSender : SmsSender {
    override suspend fun send(phone: String, code: String): Boolean {
        LoggerFactory.getLogger("repland-sms")
            .info("开发模式短信：phone=******{} code={}（未配置短信网关，仅日志出码）", phone.takeLast(4), code)
        return true
    }
}

class WebhookSmsSender(private val http: HttpClient, private val url: String) : SmsSender {
    override suspend fun send(phone: String, code: String): Boolean = runCatching {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            // 共享 HttpClient 未装 ContentNegotiation：显式 JSON 字符串体（Map 会在运行期序列化失败）
            setBody(buildJsonObject {
                put("phone", phone)
                put("code", code)
            }.toString())
        }
        response.status.value in 200..299
    }.getOrDefault(false)
}
