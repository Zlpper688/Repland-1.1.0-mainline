package com.swan1127.repland.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {

    private val bindings = listOf(
        AccountBinding("tok-captain", "team-1", "m-captain", "队长", "ou_captain", isCaptain = true),
        AccountBinding("tok-alice", "team-1", "m-alice", "Alice", "ou_alice"),
    )

    private fun ApplicationTestBuilder.moduleUnderTest(notify: NotifyService?, confirmations: ConfirmationStore? = null) {
        // ContentNegotiation 由 replandModule 内部安装，这里不再重复
        application {
            replandModule(AccountRegistry(bindings), notify, feishuConfigured = notify != null, confirmations = confirmations)
        }
    }

    private fun notifyService() = NotifyService(
        registry = AccountRegistry(bindings),
        feishu = RecordingSender(),
    )

    private class RecordingSender : FeishuSender {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendText(openId: String, message: String): Boolean {
            sent += openId to message
            return true
        }
    }

    private fun body(channel: String, teamId: String = "team-1") =
        """{"teamId":"$teamId","teamName":"逐晓","audienceId":"m-alice","audienceName":"Alice","channel":"$channel","message":"m","notificationId":"ntf-x"}"""

    @Test
    fun `health 报告飞书配置与账号数`() = testApplication {
        moduleUnderTest(notify = null)
        val response = client.get("/health").bodyAsText()
        assertTrue(response.contains("\"feishuConfigured\":false"))
        assertTrue(response.contains("\"boundAccounts\":2"))
    }

    @Test
    fun `无token或坏token返回401`() = testApplication {
        moduleUnderTest(notify = null)
        val response = client.post("/v1/notify") {
            contentType(ContentType.Application.Json)
            setBody(body("PUSH_AND_FEISHU"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val bad = client.post("/v1/notify") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody(body("PUSH_AND_FEISHU"))
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }

    @Test
    fun `跨队伍请求返回403`() = testApplication {
        moduleUnderTest(notify = null)
        val response = client.post("/v1/notify") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(body("APP_ONLY", teamId = "team-9"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `APP_ONLY返回SKIPPED_CHANNEL`() = testApplication {
        moduleUnderTest(notify = notifyService())
        val response = client.post("/v1/notify") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(body("APP_ONLY"))
        }
        assertEquals(200, response.status.value)
        assertTrue(response.bodyAsText().contains("SKIPPED_CHANNEL"))
    }

    @Test
    fun `PUSH_AND_FEISHU同步投递返回DELIVERED`() = testApplication {
        moduleUnderTest(notify = notifyService())
        val response = client.post("/v1/notify") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(body("PUSH_AND_FEISHU"))
        }
        assertEquals(200, response.status.value)
        assertTrue(response.bodyAsText().contains("DELIVERED"))
    }

    // ---------- T4b：/v1/me 与 /v1/confirmations ----------

    @Test
    fun `me 回显绑定与队长状态`() = testApplication {
        moduleUnderTest(notify = null)
        val response = client.get("/v1/me") {
            header(HttpHeaders.Authorization, "Bearer tok-captain")
        }
        assertEquals(200, response.status.value)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"memberId\":\"m-captain\""))
        assertTrue(text.contains("\"isCaptain\":true"))
        assertTrue(text.contains("\"feishuBound\":true"))
    }

    @Test
    fun `me 无token返回401`() = testApplication {
        moduleUnderTest(notify = null)
        val response = client.get("/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `confirmations 增量拉取与游标`() = testApplication {
        val store = ConfirmationStore()
        store.record("team-1", "confirm_draft", "draft-1", 3, "m-captain", "队长", "ou_captain", "2026-10-29T00:00:00Z")
        store.record("team-1", "abandon_draft", "draft-2", 1, "m-captain", "队长", "ou_captain", "2026-10-29T00:01:00Z")
        store.record("team-9", "keep_transfer", "draft-3", 1, "m-other", "别人", "ou_other", "2026-10-29T00:02:00Z")
        moduleUnderTest(notify = null, confirmations = store)

        val first = client.get("/v1/confirmations") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
        }
        assertEquals(200, first.status.value)
        val firstText = first.bodyAsText()
        assertTrue(firstText.contains("\"draftId\":\"draft-1\""))
        assertTrue(firstText.contains("\"draftId\":\"draft-2\""))
        assertTrue(!firstText.contains("draft-3")) // 只回本队伍

        val second = client.get("/v1/confirmations?since=1") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
        }
        val secondText = second.bodyAsText()
        assertTrue(!secondText.contains("\"draftId\":\"draft-1\""))
        assertTrue(secondText.contains("\"draftId\":\"draft-2\""))
        assertTrue(secondText.contains("\"cursor\":2"))
    }

    @Test
    fun `confirmations 无token返回401 台账未启用返回503`() = testApplication {
        moduleUnderTest(notify = null)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/confirmations").status,
        )
        val noStore = client.get("/v1/confirmations") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, noStore.status)
    }

    // ---------- T4c：/v1/ledger 与 /v1/meetings ----------

    private fun ApplicationTestBuilder.moduleUnderTestFull(
        notify: NotifyService? = null,
        ledgerCopy: LedgerCopyService? = null,
        meetings: MeetingCalendarService? = null,
    ) {
        application {
            replandModule(
                AccountRegistry(bindings), notify, feishuConfigured = notify != null,
                confirmations = null, ledgerCopy = ledgerCopy, meetings = meetings,
            )
        }
    }

    private fun ledgerBody(entryId: String, teamId: String = "team-1") =
        """{"teamId":"$teamId","teamName":"逐晓","entryId":"$entryId","kind":"FINANCE","evidence":"¥25.00：聚餐","memberName":"Alice","occurredAtMs":1732000000000,"amountCents":2500,"isBaseline":true}"""

    private fun meetingBody(teamId: String = "team-1") =
        """{"teamId":"$teamId","teamName":"逐晓","title":"周会","startAtMs":1732000000000,"endAtMs":1732000360000,"attendeeMemberIds":["m-captain","m-alice"]}"""

    @Test
    fun `ledger 入队accepted 幂等duplicate`() = testApplication {
        moduleUnderTestFull(ledgerCopy = LedgerCopyService(FakeBitableForRoutes()))
        val first = client.post("/v1/ledger") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(ledgerBody("led-1"))
        }
        assertEquals(200, first.status.value)
        assertTrue(first.bodyAsText().contains("ACCEPTED"))
        val second = client.post("/v1/ledger") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(ledgerBody("led-1"))
        }
        assertTrue(second.bodyAsText().contains("DUPLICATE"))
    }

    @Test
    fun `ledger 未启用503 无token401 跨队403`() = testApplication {
        moduleUnderTestFull()
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.post("/v1/ledger") {
                header(HttpHeaders.Authorization, "Bearer tok-alice")
                contentType(ContentType.Application.Json)
                setBody(ledgerBody("led-1"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/ledger") { contentType(ContentType.Application.Json); setBody(ledgerBody("led-1")) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/ledger") {
                header(HttpHeaders.Authorization, "Bearer tok-alice")
                contentType(ContentType.Application.Json)
                setBody(ledgerBody("led-1", teamId = "team-9"))
            }.status,
        )
    }

    @Test
    fun `meetings 队长创建 非队长拒绝`() = testApplication {
        val fake = FakeCalendarForRoutes()
        moduleUnderTestFull(meetings = MeetingCalendarService(AccountRegistry(bindings), fake))
        val created = client.post("/v1/meetings") {
            header(HttpHeaders.Authorization, "Bearer tok-captain")
            contentType(ContentType.Application.Json)
            setBody(meetingBody())
        }
        assertEquals(200, created.status.value)
        val text = created.bodyAsText()
        assertTrue(text.contains("CREATED"))
        assertTrue(text.contains("\"eventId\":\"evt-1\""))
        assertEquals("ou_captain", fake.calls.single().first)
        // 非队长
        val forbidden = client.post("/v1/meetings") {
            header(HttpHeaders.Authorization, "Bearer tok-alice")
            contentType(ContentType.Application.Json)
            setBody(meetingBody())
        }
        assertTrue(forbidden.bodyAsText().contains("FORBIDDEN_NOT_CAPTAIN"))
    }

    @Test
    fun `meetings 未启用503`() = testApplication {
        moduleUnderTestFull()
        val response = client.post("/v1/meetings") {
            header(HttpHeaders.Authorization, "Bearer tok-captain")
            contentType(ContentType.Application.Json)
            setBody(meetingBody())
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("disabled"))
    }

    private class FakeBitableForRoutes : BitableWriter {
        override suspend fun appendRecords(records: List<Map<String, Any?>>): Boolean = true
    }

    private class FakeCalendarForRoutes : CalendarWriter {
        val calls = mutableListOf<Pair<String, String>>() // calendarId to summary
        override suspend fun createEvent(
            calendarId: String,
            summary: String,
            startAtMs: Long,
            endAtMs: Long,
            attendeeOpenIds: List<String>,
            reminderMinutes: Int,
        ): String? {
            calls += calendarId to summary
            return "evt-1"
        }
    }
}
