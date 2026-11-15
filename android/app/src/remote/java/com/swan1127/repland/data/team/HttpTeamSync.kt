package com.swan1127.repland.data.team

import com.swan1127.repland.domain.team.ConfirmationApplier
import com.swan1127.repland.domain.team.LedgerEntry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/**
 * T4c 同步通道的联网实现（FEISHU_INTEGRATION_NOTES §2/§5/§6）：
 * - [HttpConfirmationPuller]：确认记录增量拉取（§13 拉取起步）；
 * - [HttpLedgerSink]：台账条目副本投递（§5 只做副本不做同步）；
 * - [HttpMeetingPoster]：会议候选 → 后端写飞书日历（§6）。
 *
 * 边界（§14）：本文件含 HttpURLConnection，只在 debug/internal/designPreview 构建
 * 编译；main 经反射加载，release 无此类、无联网权限。
 */

/** 通用小助手：POST JSON，返回 (status, body)；失败不抛出（fire-and-forget 语义由调用方保证）。 */
internal fun postJson(url: String, token: String, body: String): Pair<Int, String> {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        status to (if (status in 200..299) connection.inputStream.bufferedReader().readText() else "")
    } finally {
        connection.disconnect()
    }
}

/** 通用小助手：PUT JSON，返回 (status, body)。 */
internal fun putJson(url: String, token: String, body: String): Pair<Int, String> {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "PUT"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        status to (if (status in 200..299 || status == 409) connection.inputStream.bufferedReader().readText() else "")
    } finally {
        connection.disconnect()
    }
}

/** 确认记录增量拉取：GET /v1/confirmations?since=;非 200/解析失败 → 空结果、游标不动。 */
class HttpConfirmationPuller : ConfirmationPuller {

    override suspend fun pull(baseUrl: String, accountToken: String, since: Long): ConfirmationPullResult {
        if (baseUrl.isBlank() || accountToken.isBlank()) return ConfirmationPullResult(emptyList(), since)
        return runCatching {
            val connection = URL(baseUrl.trimEnd('/') + "/v1/confirmations?since=$since").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Authorization", "Bearer $accountToken")
                val status = connection.responseCode
                if (status !in 200..299) return ConfirmationPullResult(emptyList(), since)
                val body = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val items = mutableListOf<ConfirmationApplier.Record>()
                val array = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    items += ConfirmationApplier.Record(
                        seq = item.getLong("seq"),
                        kind = item.getString("kind"),
                        refId = item.getString("draftId"),
                        revision = item.optInt("revision", 1),
                        operatorMemberId = item.optString("operatorMemberId"),
                        operatorName = item.optString("operatorName"),
                        decidedAt = item.optString("decidedAt"),
                    )
                }
                ConfirmationPullResult(items, json.optLong("cursor", since))
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(ConfirmationPullResult(emptyList(), since))
    }
}

/** 台账条目副本投递：POST /v1/ledger（§5 fire-and-forget，失败静默）。 */
class HttpLedgerSink(
    private val baseUrl: String,
    private val accountToken: String,
) : LedgerSink {

    override suspend fun push(entry: LedgerEntry, teamId: String, teamName: String, memberName: String) {
        if (baseUrl.isBlank() || accountToken.isBlank()) return
        val body = JSONObject().apply {
            put("teamId", teamId)
            put("teamName", teamName)
            put("entryId", entry.entryId)
            put("kind", entry.kind.name)
            put("evidence", entry.evidence)
            put("memberName", memberName)
            put("occurredAtMs", entry.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            entry.externalUrl?.let { put("externalUrl", it) }
            entry.amountCents?.let { put("amountCents", it) }
            put("isBaseline", entry.isBaseline)
        }.toString()
        runCatching { postJson(baseUrl.trimEnd('/') + "/v1/ledger", accountToken, body) }
    }
}

/** 会议候选写日历：POST /v1/meetings（§6；非队长会被后端拒绝，静默降级）。 */
class HttpMeetingPoster(
    private val baseUrl: String,
    private val accountToken: String,
) : MeetingPoster {

    override suspend fun post(
        teamId: String,
        teamName: String,
        title: String,
        startAtMs: Long,
        endAtMs: Long,
        attendeeMemberIds: List<String>,
        reminderMinutes: Int,
    ) {
        if (baseUrl.isBlank() || accountToken.isBlank()) return
        val body = JSONObject().apply {
            put("teamId", teamId)
            put("teamName", teamName)
            put("title", title)
            put("startAtMs", startAtMs)
            put("endAtMs", endAtMs)
            put("attendeeMemberIds", JSONArray(attendeeMemberIds))
            put("reminderMinutes", reminderMinutes)
        }.toString()
        runCatching { postJson(baseUrl.trimEnd('/') + "/v1/meetings", accountToken, body) }
    }
}

// ---------- §13 表级同步第一块：队伍会话快照推拉 ----------

/** 通用小助手：GET with Bearer，返回 (status, body)。 */
internal fun getJson(url: String, token: String): Pair<Int, String> {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Authorization", "Bearer $token")
        val status = connection.responseCode
        status to (if (status in 200..299) connection.inputStream.bufferedReader().readText() else "")
    } finally {
        connection.disconnect()
    }
}

/** 快照上行：PUT /v1/teams/{teamId}/state；409 = 冲突，回带服务端当前版。 */
class HttpTeamStatePusher : TeamStatePusher {

    override suspend fun push(
        baseUrl: String,
        accountToken: String,
        teamId: String,
        baseRevision: Long,
        payload: String,
        updatedByMember: String,
    ): TeamStatePushResult {
        if (baseUrl.isBlank() || accountToken.isBlank() || payload.isBlank()) {
            return TeamStatePushResult(false, "后端未配置")
        }
        return runCatching {
            val body = JSONObject().apply {
                put("baseRevision", baseRevision)
                put("payload", payload)
                put("updatedByMember", updatedByMember)
            }.toString()
            val (status, text) = putJson(baseUrl.trimEnd('/') + "/v1/teams/$teamId/state", accountToken, body)
            val json = JSONObject(text)
            when {
                status in 200..299 -> TeamStatePushResult(
                    ok = true,
                    message = "已同步到服务器",
                    revision = json.optLong("revision", 0L),
                )
                status == 409 -> TeamStatePushResult(
                    ok = false,
                    message = json.optString("detail", "服务器已有更新的版本"),
                    conflict = true,
                    serverRevision = json.optLong("revision", 0L),
                    serverPayload = json.optString("payload"),
                )
                else -> TeamStatePushResult(false, json.optString("detail", "同步失败（$status）"))
            }
        }.getOrElse { TeamStatePushResult(false, "网络异常：${it.message ?: "unknown"}") }
    }
}

/** 快照下行：GET /v1/teams/{teamId}/state?since=；只取比 since 新的版本。 */
class HttpTeamStatePuller : TeamStatePuller {

    override suspend fun pull(baseUrl: String, accountToken: String, teamId: String, since: Long): TeamStatePullResult {
        if (baseUrl.isBlank() || accountToken.isBlank()) return TeamStatePullResult(false, "后端未配置")
        return runCatching {
            val (status, text) = getJson(baseUrl.trimEnd('/') + "/v1/teams/$teamId/state?since=$since", accountToken)
            val json = JSONObject(text)
            when (json.optString("status")) {
                "ok" -> TeamStatePullResult(
                    ok = true,
                    message = "已拉取服务器版本",
                    hasNewer = true,
                    revision = json.optLong("revision", 0L),
                    updatedByMember = json.optString("updatedByMember"),
                    payload = json.optString("payload"),
                )
                "current" -> TeamStatePullResult(ok = true, message = "已是最新", revision = json.optLong("revision", since))
                "not_found" -> TeamStatePullResult(ok = true, message = "云端还没有快照")
                else -> TeamStatePullResult(false, json.optString("detail", "拉取失败（$status）"))
            }
        }.getOrElse { TeamStatePullResult(false, "网络异常：${it.message ?: "unknown"}") }
    }
}
