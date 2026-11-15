package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** 日历写入抽象（T4c）：生产 = 真实 REST，测试 = 内存 fake。 */
interface CalendarWriter {
    /** @return event_id；null = 飞书侧拒绝（调用方进重试/降级） */
    suspend fun createEvent(
        calendarId: String,
        summary: String,
        startAtMs: Long,
        endAtMs: Long,
        attendeeOpenIds: List<String>,
        reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
    ): String?

    companion object {
        /** 与个人端逐事件提醒的默认提前量对齐（EventLeadMinutes 默认 10）。 */
        const val DEFAULT_REMINDER_MINUTES = 10
    }
}

/**
 * 飞书日历 v4 客户端（FEISHU_INTEGRATION_NOTES §6，权限 calendar:calendar）。
 * 创建：POST /open-apis/calendar/v4/calendars/:calendar_id/events
 * 参会人：POST .../events/:event_id/attendees?user_id_type=open_id（best-effort，失败不回滚日程）
 */
class FeishuCalendarClient(
    private val http: HttpClient,
    private val tokenManager: TokenManager,
    private val apiBase: String,
) : CalendarWriter {

    override suspend fun createEvent(
        calendarId: String,
        summary: String,
        startAtMs: Long,
        endAtMs: Long,
        attendeeOpenIds: List<String>,
        reminderMinutes: Int,
    ): String? {
        val token = tokenManager.get()
        val body = buildJsonObject {
            put("summary", summary)
            putJsonObject("start_time") { put("timestamp", startAtMs.toString()) }
            putJsonObject("end_time") { put("timestamp", endAtMs.toString()) }
            put("time_zone", "Asia/Shanghai")
            // 飞书 v4 的事件级提醒：开赛前 N 分钟给参会人推日历提醒。
            put("reminders", buildJsonArray {
                add(buildJsonObject { put("minutes_before_event", reminderMinutes) })
            })
        }
        val response = http.post("$apiBase/open-apis/calendar/v4/calendars/$calendarId/events") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val code = json["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) return null
        val eventId = json["data"]?.jsonObject?.get("events")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("event_id")?.jsonPrimitive?.content
            ?: return null
        if (attendeeOpenIds.isNotEmpty()) attachAttendees(calendarId, eventId, attendeeOpenIds)
        return eventId
    }

    /** 参会人追加（§6 附参会人 open_id 列表）：best-effort，失败仅记日志。 */
    private suspend fun attachAttendees(calendarId: String, eventId: String, openIds: List<String>) {
        try {
            val token = tokenManager.get()
            val body = buildJsonObject {
                put(
                    "attendees",
                    buildJsonArray {
                        openIds.forEach { openId ->
                            add(
                                buildJsonObject {
                                    put("type", "user")
                                    put("is_optional", false)
                                    put("user_id", openId)
                                },
                            )
                        }
                    },
                )
            }
            http.post("$apiBase/open-apis/calendar/v4/calendars/$calendarId/events/$eventId/attendees?user_id_type=open_id") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (failure: Exception) {
            log.warn("日历参会人追加失败（日程已创建，不回滚）：{}", failure.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FeishuCalendarClient::class.java)
    }
}

/** 会议建日程结果 —— 全部非异常路径（§6 幂等键 teamId+时间窗）。 */
enum class MeetingCreateResult {
    /** 已在队长主日历创建 */
    CREATED,
    /** 同一会议幂等键重复（teamId + 时间窗），忽略 */
    DUPLICATE_WINDOW,
    /** 仅队长可确认建日程（§6 队长确认后写入） */
    FORBIDDEN_NOT_CAPTAIN,
    /** 时间窗非法 */
    INVALID_WINDOW,
    /** 操作人未绑定飞书（无主日历可写）——降级 */
    DROPPED_NO_CALENDAR,
    /** 日历写入未启用 */
    DISABLED,
    /** 飞书侧拒绝/异常 —— 重试后置 */
    RETRY_LATER,
}

/**
 * 会议写飞书日历服务（T4c，§6）：
 * - 日历 = 队长主日历（飞书约定主日历 calendar_id 即 open_id）；
 * - 标题 = 队伍名 + 事项；参会人按 memberId 在后端换算 open_id（未绑定的跳过）；
 * - 幂等键 teamId + 时间窗，仅在确认创建成功后记账（失败不占键，可重试）。
 */
class MeetingCalendarService(
    private val registry: AccountRegistry,
    private val writer: CalendarWriter?,
) {
    private val recentKeys = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 256
    }
    private val keyMutex = Mutex()

    /** @return (结果, eventId)；除 CREATED 外 eventId 均为空串 */
    suspend fun create(request: MeetingCreateRequest, requester: AccountBinding): Pair<MeetingCreateResult, String> {
        val writer = writer ?: return MeetingCreateResult.DISABLED to ""
        if (!requester.isCaptain) return MeetingCreateResult.FORBIDDEN_NOT_CAPTAIN to ""
        if (request.startAtMs <= 0 || request.endAtMs <= request.startAtMs) {
            return MeetingCreateResult.INVALID_WINDOW to ""
        }
        if (requester.openId.isBlank()) return MeetingCreateResult.DROPPED_NO_CALENDAR to ""
        val key = "${request.teamId}|${request.startAtMs}|${request.endAtMs}"
        if (keyMutex.withLock { recentKeys.containsKey(key) }) return MeetingCreateResult.DUPLICATE_WINDOW to ""
        val attendeeOpenIds = request.attendeeMemberIds
            .mapNotNull { registry.findRecipient(request.teamId, it) }
            .map { it.openId }
            .filter { it.isNotBlank() }
        return try {
            val eventId = writer.createEvent(
                calendarId = requester.openId,
                summary = "${request.teamName}·${request.title.ifBlank { "会议" }}",
                startAtMs = request.startAtMs,
                endAtMs = request.endAtMs,
                attendeeOpenIds = attendeeOpenIds,
                reminderMinutes = request.reminderMinutes ?: CalendarWriter.DEFAULT_REMINDER_MINUTES,
            ) ?: return MeetingCreateResult.RETRY_LATER to ""
            keyMutex.withLock { recentKeys[key] = true }
            MeetingCreateResult.CREATED to eventId
        } catch (_: Exception) {
            MeetingCreateResult.RETRY_LATER to ""
        }
    }
}
