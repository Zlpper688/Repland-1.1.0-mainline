package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §13 表级同步第一块（队伍会话快照）+ 账号管理端点：
 * SMS 登录 → PUT 状态（首写 / revision 守卫 / 冲突回带服务端版 / 非本队禁写）
 * → 第二账号 GET 拉取 → since 短路 → 登出吊销 token → 管理端账号清单（脱敏 + admin token 门）
 * → 快照库磁盘往返。
 */
class TeamStateSyncTest {

    private fun service() = AuthService(store = AuthStore(), smsSender = LoggingSmsSender(), smsDevMode = true)

    private fun ApplicationTestBuilder.module(
        authService: AuthService? = null,
        adminToken: String? = null,
        teamStates: TeamStateStore? = TeamStateStore(),
    ) {
        application {
            replandModule(
                AccountRegistry(emptyList()),
                notifyService = null,
                feishuConfigured = false,
                authService = authService,
                authAdminToken = adminToken,
                teamStates = teamStates,
            )
        }
    }

    private suspend fun HttpClient.requestCode(phone: String): SmsRequestResponse {
        val raw = post("/v1/auth/sms/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SmsRequestResponse.serializer(), raw.bodyAsText())
    }

    private suspend fun HttpClient.verifyCode(
        phone: String,
        code: String,
        teamId: String = "",
        memberId: String = "",
        displayName: String = "",
    ): LoginResponse {
        val raw = post("/v1/auth/sms/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"$code","displayName":"$displayName","teamId":"$teamId","memberId":"$memberId"}""")
        }
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(LoginResponse.serializer(), raw.bodyAsText())
    }

    private suspend fun HttpClient.putState(
        token: String,
        teamId: String,
        baseRevision: Long,
        payload: String,
        member: String,
    ): Pair<HttpStatusCode, TeamStateResponse> {
        // payload 是"字符串字段"不是嵌套对象——用 JsonPrimitive 正确编码，避免插值破坏 JSON
        val body = kotlinx.serialization.json.buildJsonObject {
            put("baseRevision", kotlinx.serialization.json.JsonPrimitive(baseRevision))
            put("payload", kotlinx.serialization.json.JsonPrimitive(payload))
            put("updatedByMember", kotlinx.serialization.json.JsonPrimitive(member))
        }.toString()
        val raw: HttpResponse = put("/v1/teams/$teamId/state") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = raw.bodyAsText()
        println("PUT /v1/teams/$teamId/state -> ${raw.status} body=[$text]")
        return raw.status to decode(text)
    }

    private suspend fun HttpClient.getState(token: String, teamId: String, since: Long): Pair<HttpStatusCode, TeamStateResponse> {
        val raw = get("/v1/teams/$teamId/state?since=$since") { bearerAuth(token) }
        return raw.status to decode(raw.bodyAsText())
    }

    private fun decode(text: String): TeamStateResponse =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(TeamStateResponse.serializer(), text)

    @Test
    fun `队伍快照 首写 revision 守卫与冲突回带服务端版`() = testApplication {
        module(service())
        val captainCode = client.requestCode("13800001111").devCode!!
        val captain = client.verifyCode("13800001111", captainCode, teamId = "team-1", memberId = "m-captain", displayName = "队长")
        assertEquals("ok", captain.status)

        // 首写（baseRevision=0 无条件）
        val (s1, r1) = client.putState(captain.token, "team-1", 0, """{"v":1}""", "队长")
        assertEquals(HttpStatusCode.OK, s1)
        assertEquals("ok", r1.status)
        assertEquals(1L, r1.revision)
        assertEquals("队长", r1.updatedByMember)

        // 旧 base → 冲突，响应带服务端当前版（客户端拉取后重放）
        val (sc, rc) = client.putState(captain.token, "team-1", 0, """{"v":2}""", "队长")
        assertEquals(HttpStatusCode.Conflict, sc)
        assertEquals("conflict", rc.status)
        assertEquals(1L, rc.revision)
        assertTrue(rc.payload.contains("\"v\":1"))

        // 正确 base → rev2
        val (s2, r2) = client.putState(captain.token, "team-1", 1, """{"v":2}""", "队长")
        assertEquals(HttpStatusCode.OK, s2)
        assertEquals(2L, r2.revision)

        // 非本队成员禁写
        val outsiderCode = client.requestCode("13800002222").devCode!!
        val outsider = client.verifyCode("13800002222", outsiderCode, teamId = "team-9", memberId = "m-x", displayName = "外人")
        val (sf, _) = client.putState(outsider.token, "team-1", 2, """{"v":3}""", "外人")
        assertEquals(HttpStatusCode.Forbidden, sf)
    }

    @Test
    fun `第二账号拉取 since 短路与空队 not_found`() = testApplication {
        module(service())
        val captainCode = client.requestCode("13800001111").devCode!!
        val captain = client.verifyCode("13800001111", captainCode, teamId = "team-1", memberId = "m-captain", displayName = "队长")

        // 没有云端快照 → not_found
        val (s0, r0) = client.getState(captain.token, "team-1", 0)
        assertEquals(HttpStatusCode.OK, s0)
        assertEquals("not_found", r0.status)

        client.putState(captain.token, "team-1", 0, """{"v":1}""", "队长")

        // 队员登录拉取
        val memberCode = client.requestCode("13900002222").devCode!!
        val member = client.verifyCode("13900002222", memberCode, teamId = "team-1", memberId = "m-member", displayName = "队员")
        val (s, r) = client.getState(member.token, "team-1", 0)
        assertEquals(HttpStatusCode.OK, s)
        assertEquals("ok", r.status)
        assertEquals(1L, r.revision)
        assertEquals("队长", r.updatedByMember)
        assertTrue(r.payload.contains("\"v\":1"))

        // since=revision → current 短路（不重复传快照）
        val (_, r2) = client.getState(member.token, "team-1", 1)
        assertEquals("current", r2.status)
    }

    @Test
    fun `登出吊销 token 与管理端账号清单`() = testApplication {
        module(service(), adminToken = "admin-secret")
        val code = client.requestCode("13800001111").devCode!!
        val login = client.verifyCode("13800001111", code, displayName = "小爱")

        // 登出 → token 失效
        val logoutRaw = client.post("/v1/auth/logout") { bearerAuth(login.token) }
        assertTrue(logoutRaw.bodyAsText().contains("\"status\":\"ok\""))
        val me = client.get("/v1/auth/account-me") { bearerAuth(login.token) }
        assertEquals(HttpStatusCode.Unauthorized, me.status)
        // 重复登出 → not_found
        val again = client.post("/v1/auth/logout") { bearerAuth(login.token) }
        assertTrue(again.bodyAsText().contains("\"status\":\"not_found\""))

        // 管理端：无 admin token 403；带 token → 清单（手机号脱敏）
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/auth/accounts").status)
        val admin = client.get("/v1/auth/accounts") { header("X-Auth-Admin-Token", "admin-secret") }
        assertEquals(HttpStatusCode.OK, admin.status)
        assertTrue(admin.bodyAsText().contains("138****1111"))
    }

    @Test
    fun `快照库磁盘往返`() {
        val dir = Files.createTempDirectory("repland-teams")
        val store = TeamStateStore(dir)
        val first = store.put("team-1", 0, """{"v":1}""", "队长")
        assertTrue(first is TeamStateStore.PutResult.Accepted)
        // 同目录重开：快照仍在，revision 延续
        val reopened = TeamStateStore(dir)
        assertEquals(1L, reopened.current("team-1")?.revision)
        val second = reopened.put("team-1", 1, """{"v":2}""", "队员")
        assertTrue(second is TeamStateStore.PutResult.Accepted)
        assertEquals(2L, (second as TeamStateStore.PutResult.Accepted).entry.revision)
    }
}
