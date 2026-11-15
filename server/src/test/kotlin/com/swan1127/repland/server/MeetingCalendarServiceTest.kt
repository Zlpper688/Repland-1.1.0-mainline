package com.swan1127.repland.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeetingCalendarServiceTest {

    private val bindings = listOf(
        AccountBinding("tok-captain", "team-1", "m-captain", "队长", "ou_captain", isCaptain = true),
        AccountBinding("tok-alice", "team-1", "m-alice", "Alice", "ou_alice"),
        AccountBinding("tok-bob", "team-1", "m-bob", "Bob", ""), // 未绑定 open_id
    )

    private class FakeCalendar : CalendarWriter {
        val calls = mutableListOf<Call>()
        var result: String? = "evt-1"
        data class Call(
            val calendarId: String,
            val summary: String,
            val startAtMs: Long,
            val endAtMs: Long,
            val attendeeOpenIds: List<String>,
            val reminderMinutes: Int,
        )

        override suspend fun createEvent(
            calendarId: String,
            summary: String,
            startAtMs: Long,
            endAtMs: Long,
            attendeeOpenIds: List<String>,
            reminderMinutes: Int,
        ): String? {
            calls += Call(calendarId, summary, startAtMs, endAtMs, attendeeOpenIds, reminderMinutes)
            return result
        }
    }

    private fun request() = MeetingCreateRequest(
        teamId = "team-1",
        teamName = "逐晓",
        title = "周会",
        startAtMs = 1_732_000_000_000,
        endAtMs = 1_732_000_360_000,
        attendeeMemberIds = listOf("m-captain", "m-alice", "m-bob", "m-ghost"),
    )

    @Test
    fun `队长创建写主日历并附参会人`() = runTest {
        val calendar = FakeCalendar()
        val service = MeetingCalendarService(AccountRegistry(bindings), calendar)
        val (result, eventId) = service.create(request(), bindings[0])
        assertEquals(MeetingCreateResult.CREATED, result)
        assertEquals("evt-1", eventId)
        val call = calendar.calls.single()
        // 主日历：calendar_id 即队长 open_id（§6 队长绑定的日历）
        assertEquals("ou_captain", call.calendarId)
        assertEquals("逐晓·周会", call.summary)
        assertEquals(1_732_000_000_000, call.startAtMs)
        // 参会人 open_id 换算：未绑定的 m-bob 与不存在的 m-ghost 跳过
        assertEquals(listOf("ou_captain", "ou_alice"), call.attendeeOpenIds)
        // 旧客户端不带 reminderMinutes → 按个人端默认提前 10 分钟
        assertEquals(CalendarWriter.DEFAULT_REMINDER_MINUTES, call.reminderMinutes)
    }

    @Test
    fun `提醒分钟数原样传给日历`() = runTest {
        val calendar = FakeCalendar()
        val service = MeetingCalendarService(AccountRegistry(bindings), calendar)
        service.create(request().copy(reminderMinutes = 30), bindings[0])
        assertEquals(30, calendar.calls.single().reminderMinutes)
    }

    @Test
    fun `同队伍同时间窗幂等`() = runTest {
        val calendar = FakeCalendar()
        val service = MeetingCalendarService(AccountRegistry(bindings), calendar)
        assertEquals(MeetingCreateResult.CREATED, service.create(request(), bindings[0]).first)
        assertEquals(MeetingCreateResult.DUPLICATE_WINDOW, service.create(request(), bindings[0]).first)
        assertEquals(1, calendar.calls.size)
    }

    @Test
    fun `非队长拒绝`() = runTest {
        val calendar = FakeCalendar()
        val service = MeetingCalendarService(AccountRegistry(bindings), calendar)
        val (result, eventId) = service.create(request(), bindings[1])
        assertEquals(MeetingCreateResult.FORBIDDEN_NOT_CAPTAIN, result)
        assertEquals("", eventId)
        assertTrue(calendar.calls.isEmpty())
    }

    @Test
    fun `时间窗非法与未绑定日历`() = runTest {
        val calendar = FakeCalendar()
        val service = MeetingCalendarService(AccountRegistry(bindings), calendar)
        val badWindow = request().copy(endAtMs = 0)
        assertEquals(MeetingCreateResult.INVALID_WINDOW, service.create(badWindow, bindings[0]).first)
        val noBinding = bindings[0].copy(openId = "")
        assertEquals(MeetingCreateResult.DROPPED_NO_CALENDAR, service.create(request(), noBinding).first)
        assertTrue(calendar.calls.isEmpty())
    }

    @Test
    fun `未配置writer降级 飞书拒绝返回RETRY_LATER`() = runTest {
        val disabled = MeetingCalendarService(AccountRegistry(bindings), null)
        assertEquals(MeetingCreateResult.DISABLED, disabled.create(request(), bindings[0]).first)

        val rejecting = FakeCalendar().apply { result = null }
        val service = MeetingCalendarService(AccountRegistry(bindings), rejecting)
        assertEquals(MeetingCreateResult.RETRY_LATER, service.create(request(), bindings[0]).first)
        assertEquals(1, rejecting.calls.size)
    }
}
