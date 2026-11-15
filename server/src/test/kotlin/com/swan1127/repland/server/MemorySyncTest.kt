package com.swan1127.repland.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §12 mem0 云记忆同步（T5 收尾）端到端测试：
 * 账号 token 鉴权 → sync（画像/档案按 kind 分流，accepted/skipped 聚合）→ search 语义检索；
 * 未配置 MEM0_API_KEY → disabled 降级；网关失败计入 skipped（本地主副本不受影响，离线优先）。
 */
class MemorySyncTest {

    /** 录制型 stub：记录 add 调用，search 按 query 包含过滤。 */
    private class RecordingMem0 : Mem0Gateway {
        data class Add(val userId: String, val text: String, val kind: String)
        val added = mutableListOf<Add>()
        val memories = listOf("擅长 Kotlin 协程调度", "偏好轻量 Anki 卡片", "校赛一等奖·逐晓队")
        var failNext = false

        override suspend fun add(userId: String, text: String, kind: String): Boolean {
            if (failNext) return false
            added += Add(userId, text, kind)
            return true
        }

        override suspend fun search(userId: String, query: String): List<String> =
            memories.filter { it.contains(query) }
    }

    private var nowMs = 1_735_000_000_000L

    private fun authService() = AuthService(
        store = AuthStore(),
        smsSender = LoggingSmsSender(),
        smsDevMode = true,
        clock = { nowMs },
    )

    private fun ApplicationTestBuilder.module(auth: AuthService, gateway: Mem0Gateway?) {
        application {
            replandModule(
                AccountRegistry(listOf(AccountBinding("tok-legacy", "team-1", "m-captain", "队长", "ou_1", isCaptain = true))),
                notifyService = null,
                feishuConfigured = false,
                authService = auth,
                mem0Gateway = gateway,
            )
        }
    }

    private suspend fun io.ktor.client.HttpClient.login(): Pair<String, String> {
        val code = post("/v1/auth/sms/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800007777"}""")
        }.bodyAsText().let { Json { ignoreUnknownKeys = true }.decodeFromString(SmsRequestResponse.serializer(), it) }
        val login = post("/v1/auth/sms/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800007777","code":"${code.devCode}","displayName":"同步客","teamId":"team-1","memberId":"m-sync"}""")
        }.bodyAsText().let { Json { ignoreUnknownKeys = true }.decodeFromString(LoginResponse.serializer(), it) }
        assertEquals("ok", login.status)
        return login.token to login.accountId
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `同步个人记忆到云端并语义检索`() = testApplication {
        val auth = authService()
        val gateway = RecordingMem0()
        module(auth, gateway)
        val (token, accountId) = client.login()
        // sync：2 条画像 + 1 份档案 → 全部受理；user_id = accountId，kind 分流
        val syncRaw = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"facts":["擅长 Kotlin 协程调度","偏好轻量 Anki 卡片"],
                   "archives":["参赛档案 · 逐晓队\n成绩：校赛一等奖"]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, syncRaw.status)
        val sync = json.decodeFromString(MemorySyncResponse.serializer(), syncRaw.bodyAsText())
        assertEquals("ok", sync.status)
        assertEquals(3, sync.accepted)
        assertEquals(0, sync.skipped)
        assertEquals(3, gateway.added.size)
        assertTrue(gateway.added.all { it.userId == accountId })
        assertEquals(listOf("profile", "profile", "archive"), gateway.added.map { it.kind })
        // search：语义检索回传匹配记忆
        val searchRaw = client.post("/v1/memory/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"Kotlin"}""")
        }
        assertEquals(HttpStatusCode.OK, searchRaw.status)
        val search = json.decodeFromString(MemorySearchResponse.serializer(), searchRaw.bodyAsText())
        assertEquals("ok", search.status)
        assertEquals(listOf("擅长 Kotlin 协程调度"), search.memories)
        // 空 query → invalid
        val blank = client.post("/v1/memory/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"   "}""")
        }
        assertEquals("invalid", json.decodeFromString(MemorySearchResponse.serializer(), blank.bodyAsText()).status)
    }

    @Test
    fun `未配置降级 鉴权失败与网关失败计入 skipped`() = testApplication {
        val auth = authService()
        val gateway = RecordingMem0()
        module(auth, gateway)
        // 未带 token → 401
        val noAuth = client.post("/v1/memory/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"facts":["x"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        val (token, _) = client.login()
        // 空内容 → invalid
        val empty = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":["  "],"archives":[]}""")
        }
        assertEquals("invalid", json.decodeFromString(MemorySyncResponse.serializer(), empty.bodyAsText()).status)
        // 网关失败 → skipped 聚合（不阻断，本地主副本不受影响）
        gateway.failNext = true
        val partial = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":["擅长 Kotlin 协程调度"]}""")
        }
        val partialSync = json.decodeFromString(MemorySyncResponse.serializer(), partial.bodyAsText())
        assertEquals("ok", partialSync.status)
        assertEquals(0, partialSync.accepted)
        assertEquals(1, partialSync.skipped)
    }

    @Test
    fun `未配置 MEM0_API_KEY 时记忆端点降级 disabled`() = testApplication {
        val auth = authService()
        module(auth, null)
        val (token, _) = client.login()
        val sync = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":["x"]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, sync.status)
        assertTrue(sync.bodyAsText().contains("\"status\":\"disabled\""))
        val search = client.post("/v1/memory/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"Kotlin"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, search.status)
        assertTrue(search.bodyAsText().contains("MEM0_API_KEY"))
    }

    @Test
    fun `sync 与 search 账号级限流与单次条数上限`() = testApplication {
        val auth = authService()
        val gateway = RecordingMem0()
        module(auth, gateway)
        val (token, accountId) = client.login()
        val body = """{"facts":["擅长 Kotlin 协程调度"]}"""
        // sync：窗口内第二次 → 429 rate_limited
        val first = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals("ok", json.decodeFromString(MemorySyncResponse.serializer(), first.bodyAsText()).status)
        val second = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertEquals("rate_limited", json.decodeFromString(MemorySyncResponse.serializer(), second.bodyAsText()).status)
        // search：窗口内第二次 → 429
        val searchFirst = client.post("/v1/memory/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"Kotlin"}""")
        }
        assertEquals("ok", json.decodeFromString(MemorySearchResponse.serializer(), searchFirst.bodyAsText()).status)
        val searchSecond = client.post("/v1/memory/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"query":"Anki"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, searchSecond.status)
        // 单次条数上限：>100 条 → invalid（不计限流）
        val oversized = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"facts":[${(1..101).joinToString(",") { "\"t$it\"" }}]}""")
        }
        val over = json.decodeFromString(MemorySyncResponse.serializer(), oversized.bodyAsText())
        assertEquals("invalid", over.status)
        assertTrue(over.detail.contains("100"))
        // 无关账号不受影响：第二个账号登录后 sync 正常（限流按账号隔离）
        val code = client.post("/v1/auth/sms/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800008888"}""")
        }.bodyAsText().let { json.decodeFromString(SmsRequestResponse.serializer(), it) }
        val login2 = client.post("/v1/auth/sms/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800008888","code":"${code.devCode}","displayName":"二号"}""")
        }.bodyAsText().let { json.decodeFromString(LoginResponse.serializer(), it) }
        val other = client.post("/v1/memory/sync") {
            header(HttpHeaders.Authorization, "Bearer ${login2.token}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals("ok", json.decodeFromString(MemorySyncResponse.serializer(), other.bodyAsText()).status)
        assertEquals(1, gateway.added.count { it.userId == accountId })
    }
}
