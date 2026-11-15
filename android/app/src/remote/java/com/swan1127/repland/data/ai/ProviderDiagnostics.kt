package com.swan1127.repland.data.ai

import android.util.Log
import java.net.HttpURLConnection
import org.json.JSONObject

/**
 * 上游 4xx 的响应体是区分「密钥无效」「额度用尽」「模型不存在」的唯一凭据，原先整块被丢掉，
 * UI 只剩一句笼统的鉴权失败。这里只取 error.message，限长后写 logcat：请求头不参与，
 * 响应体不进 Room、不进提示词、不进任何持久化状态。
 */
internal fun HttpURLConnection.readProviderRejection(): String {
    val raw = runCatching {
        errorStream?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() < MAX_PROVIDER_ERROR_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, minOf(count, MAX_PROVIDER_ERROR_BYTES - output.size()))
            }
            output.toString("UTF-8")
        }
    }.getOrNull().orEmpty()
    val message = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
    return message.ifBlank { raw }.replace('\n', ' ').trim().take(MAX_PROVIDER_ERROR_CHARS).ifBlank { "网关未给出原因" }
}

internal fun logProviderRejection(endpoint: String, code: Int, detail: String, keyLength: Int) {
    Log.w(PROVIDER_LOG_TAG, "$endpoint 返回 $code：$detail（本次发出密钥 $keyLength 位）")
}

private const val PROVIDER_LOG_TAG = "ReplandProvider"
private const val MAX_PROVIDER_ERROR_BYTES = 4096
private const val MAX_PROVIDER_ERROR_CHARS = 240
