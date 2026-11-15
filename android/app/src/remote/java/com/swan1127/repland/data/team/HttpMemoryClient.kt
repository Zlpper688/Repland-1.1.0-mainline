package com.swan1127.repland.data.team

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * mem0 云记忆同步联网实现（T5 收尾，debug/internal 构建专属）：
 * POST /v1/memory/sync（画像 + 档案批量推送）与 POST /v1/memory/search（语义检索）。
 * 边界（§14）：含 HttpURLConnection，只在含 src/remote 的构建编译，main 反射加载。
 */
class HttpMemoryClient : MemoryPusher, MemorySearcher {

    override suspend fun push(
        baseUrl: String,
        token: String,
        facts: List<String>,
        archives: List<String>,
    ): MemorySyncResult = call(baseUrl, token, "/v1/memory/sync", JSONObject().apply {
        put("facts", JSONArray().apply { facts.filter { it.isNotBlank() }.forEach { put(it) } })
        put("archives", JSONArray().apply { archives.filter { it.isNotBlank() }.forEach { put(it) } })
    }) { json ->
        val status = json.optString("status")
        val accepted = json.optInt("accepted")
        val skipped = json.optInt("skipped")
        MemorySyncResult(
            ok = status == "ok",
            message = when (status) {
                "ok" -> "已同步 $accepted 条，云端解析中" + (if (skipped > 0) "（$skipped 条未受理，可重试）" else "")
                "invalid" -> json.optString("detail").ifEmpty { "没有可同步的记忆" }
                "disabled" -> json.optString("detail").ifEmpty { "服务端未启用记忆云同步" }
                else -> json.optString("detail").ifEmpty { "同步失败" }
            },
            accepted = accepted,
            skipped = skipped,
        )
    }

    override suspend fun search(baseUrl: String, token: String, query: String): MemorySearchResult =
        call(baseUrl, token, "/v1/memory/search", JSONObject().put("query", query)) { json ->
            val status = json.optString("status")
            val memories = json.optJSONArray("memories")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            MemorySearchResult(
                ok = status == "ok",
                message = when (status) {
                    "ok" -> if (memories.isEmpty()) "云端没有相关记忆" else "找到 ${memories.size} 条"
                    "disabled" -> json.optString("detail").ifEmpty { "服务端未启用记忆云同步" }
                    else -> json.optString("detail").ifEmpty { "搜索失败" }
                },
                memories = memories,
            )
        }

    // ---------- 内部 ----------

    private fun <T> call(
        baseUrl: String,
        token: String,
        path: String,
        body: JSONObject,
        parse: (JSONObject) -> T,
    ): T {
        if (baseUrl.isBlank()) {
            return parse(JSONObject().put("status", "no_base_url").put("detail", "请先填写并保存后端地址"))
        }
        if (token.isBlank()) {
            return parse(JSONObject().put("status", "no_token").put("detail", "请先登录获取账号 token"))
        }
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                parse(JSONObject(stream.bufferedReader().readText()))
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            parse(
                JSONObject()
                    .put("status", "network_error")
                    .put("detail", "连接失败：${failure.message ?: "网络不可达"}"),
            )
        }
    }
}
