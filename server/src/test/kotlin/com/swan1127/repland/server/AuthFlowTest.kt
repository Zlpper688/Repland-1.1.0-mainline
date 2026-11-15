package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T3 补齐（§13 双通道登录）端到端测试：
 * 手机号验证码（dev 出码/限流/错误/过期/成员位认领/重复登录）+ 微信配对（start/poll/complete/admin token）
 * + 登录 token 融入既有团队端点鉴权 + 账号库持久化往返。
 */
class AuthFlowTest {

    private var nowMs = 1_735_000_000_000L

    private fun service(
        store: AuthStore = AuthStore(),
        smsDevMode: Boolean = true,
        allowedPhones: Set<String> = emptySet(),
        wechatAuthUrl: String? = null,
        wechatAppId: String? = null,
        wechatAppSecret: String? = null,
        publicBaseUrl: String? = null,
        wechatCodeExchanger: WechatCodeExchanger? = null,
    ) = AuthService(
        store = store,
        smsSender = LoggingSmsSender(),
        smsDevMode = smsDevMode,
        allowedPhones = allowedPhones,
        wechatAuthUrl = wechatAuthUrl,
        wechatAppId = wechatAppId,
        wechatAppSecret = wechatAppSecret,
        publicBaseUrl = publicBaseUrl,
        wechatCodeExchanger = wechatCodeExchanger,
        clock = { nowMs },
    )

    private fun ApplicationTestBuilder.module(
        authService: AuthService?,
        adminToken: String? = null,
        wechatQrLogin: WechatQrLogin? = null,
    ) {
        application {
            replandModule(
                AccountRegistry(listOf(AccountBinding("tok-legacy", "team-1", "m-captain", "队长", "ou_1", isCaptain = true))),
                notifyService = null,
                feishuConfigured = false,
                authService = authService,
                authAdminToken = adminToken,
                wechatQrLogin = wechatQrLogin,
            )
        }
    }

    @Test
    fun `微信扫码回调授权导入资料且只给发起请求的客户端签发登录凭证`() = testApplication {
        val auth = service()
        val provider = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sns/oauth2/access_token" -> {
                    assertEquals("wechat-code", request.url.parameters["code"])
                    respond("""{"openid":"wx-open","unionid":"wx-union","access_token":"wx-session"}""")
                }
                "/sns/userinfo" -> {
                    assertEquals("wx-open", request.url.parameters["openid"])
                    assertEquals("wx-session", request.url.parameters["access_token"])
                    respond("""{"openid":"wx-open","nickname":"微信测试","headimgurl":"http://thirdwx.qlogo.cn/avatar"}""")
                }
                else -> error("Unexpected WeChat route")
            }
        })
        try {
            val callback = "https://repland.example/repland/v1/auth/wechat/qr/callback"
            val qr = WechatQrLogin(auth, "test-app", callback, WechatHttpClient(provider, "test-app", "test-secret", fetchProfile = true)) { nowMs }
            module(auth, wechatQrLogin = qr)
            val json = kotlinx.serialization.json.Json
            val start = json.decodeFromString(
                WechatQrStartResponse.serializer(), client.post("/v1/auth/wechat/qr/start").bodyAsText(),
            )
            val qrUrl = Url(start.authUrl)
            assertEquals("snsapi_login", qrUrl.parameters["scope"])
            assertEquals(callback, qrUrl.parameters["redirect_uri"])
            val state = requireNotNull(qrUrl.parameters["state"])
            assertTrue(start.pollToken != state)
            suspend fun poll(token: String): LoginResponse = json.decodeFromString(
                LoginResponse.serializer(), client.post("/v1/auth/wechat/qr/poll") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(WechatQrPollRequest.serializer(), WechatQrPollRequest(token)))
                }.bodyAsText(),
            )
            assertEquals("pending", poll(start.pollToken).status)
            val completion = client.get("/v1/auth/wechat/qr/callback?state=$state&code=wechat-code").bodyAsText()
            val login = poll(start.pollToken)
            assertEquals("ok", login.status)
            assertEquals("微信测试", login.displayName)
            assertEquals("https://thirdwx.qlogo.cn/avatar", login.avatarUrl)
            assertEquals(login.avatarUrl, auth.authenticate(login.token)?.avatarUrl)
            assertTrue(login.token !in completion)
            assertEquals("expired", poll(state).status)
            nowMs += start.expiresInSeconds * 1_000L
            assertEquals("expired", poll(start.pollToken).status)
        } finally {
            provider.close()
        }
    }

    private suspend fun io.ktor.client.HttpClient.requestCode(phone: String): SmsRequestResponse {
        val raw = post("/v1/auth/sms/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val text = raw.bodyAsText()
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SmsRequestResponse.serializer(), text)
    }

    private suspend fun io.ktor.client.HttpClient.verifyCode(
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

    // ── 手机号验证码通道 ─────────────────────────────────────────────────────

    @Test
    fun `验证码请求 dev 模式出码且 60 秒内限流`() = testApplication {
        module(service())
        val first = client.requestCode("13800001111")
        assertEquals("sent", first.status)
        assertEquals(6, first.devCode?.length)
        // 同号（含空格/横线的脏输入会被规范化）60 秒内重发 → 限流
        val second = client.requestCode("138 0000-1111")
        assertEquals("rate_limited", second.status)
        assertTrue(second.retryAfterSeconds in 1..60)
        // 非法手机号
        assertEquals("invalid_phone", client.requestCode("12345").status)
    }

    @Test
    fun `内测名单外手机号拿不到验证码`() = testApplication {
        module(service(allowedPhones = setOf("13800001111")))
        val inside = client.requestCode("13800001111")
        assertEquals("sent", inside.status)
        assertEquals(6, inside.devCode?.length)
        // 名单外既不回显码，也不生成可校验的码
        val outside = client.requestCode("13900002222")
        assertEquals("not_allowed", outside.status)
        assertNull(outside.devCode)
        assertEquals("no_code", client.verifyCode("13900002222", "123456").status)
    }

    @Test
    fun `验证码校验 成功签发 token 并认领成员位`() = testApplication {
        val auth = service()
        module(auth)
        val code = client.requestCode("13800001111").devCode!!
        val login = client.verifyCode("13800001111", code, teamId = "team-1", memberId = "m-alice", displayName = "小爱")
        assertEquals("ok", login.status)
        assertTrue(login.token.length >= 32)
        assertTrue(login.created)
        assertEquals(1, login.memberships.size)
        assertEquals("team-1", login.memberships.first().teamId)
        assertEquals("m-alice", login.memberships.first().memberId)
        // 登录 token 融入既有端点：/v1/me（teamId query）与 /v1/notify（body teamId）
        val me = client.get("/v1/me?teamId=team-1") { header(HttpHeaders.Authorization, "Bearer ${login.token}") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"memberName\":\"小爱\""))
        val notify = client.post("/v1/notify") {
            header(HttpHeaders.Authorization, "Bearer ${login.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"teamId":"team-1","teamName":"逐晓","audienceId":"m-alice","audienceName":"小爱","channel":"APP_ONLY","message":"hi","notificationId":"n1"}""")
        }
        assertEquals(HttpStatusCode.OK, notify.status)
        // account-me 回环
        val accountMe = client.get("/v1/auth/account-me") { header(HttpHeaders.Authorization, "Bearer ${login.token}") }
        assertEquals(HttpStatusCode.OK, accountMe.status)
        assertTrue(accountMe.bodyAsText().contains("138****1111"))
    }

    @Test
    fun `验证码错误 过期 重复登录与成员位抢占`() = testApplication {
        val auth = service()
        module(auth)
        // 错误码
        client.requestCode("13800002222")
        assertEquals("bad_code", client.verifyCode("13800002222", "000000").status)
        // 过期：推进时钟 6 分钟
        nowMs += 6 * 60 * 1000
        val fresh = client.requestCode("13800002222").devCode!!
        nowMs += 6 * 60 * 1000
        assertEquals("expired", client.verifyCode("13800002222", fresh).status)
        // 首次登录（无队伍 → 游离账号）
        val code = client.requestCode("13800003333").devCode!!
        val first = client.verifyCode("13800003333", code, displayName = "阿岚")
        assertEquals("ok", first.status)
        assertTrue(first.memberships.isEmpty())
        // 重复登录：同账号新 token（多设备），created=false
        val code2 = client.requestCode("13800003333").devCode!!
        nowMs += 2 * 60 * 1000
        val second = client.verifyCode("13800003333", code2)
        assertEquals("ok", second.status)
        assertEquals(first.accountId, second.accountId)
        assertTrue(!second.created)
        assertTrue(second.token != first.token)
        // 未入队账号访问团队端点 → 401（无成员位上下文；须在认领成员位之前验证）
        val lone = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${first.token}") }
        assertEquals(HttpStatusCode.Unauthorized, lone.status)
        // 成员位抢占：m-alice 已被 team-1 内他人占 → slot_taken
        val code3 = client.requestCode("13800003333").devCode!!
        nowMs += 2 * 60 * 1000
        val claimed = client.verifyCode("13800003333", code3, teamId = "team-1", memberId = "m-alice")
        assertEquals("ok", claimed.status)
        val code4 = client.requestCode("13800005555").devCode!!
        nowMs += 2 * 60 * 1000
        val rival = client.verifyCode("13800005555", code4, teamId = "team-1", memberId = "m-alice")
        assertEquals("slot_taken", rival.status)
        // 认领成功后再访问 /v1/me → 200（成员位上下文生效）
        val after = client.get("/v1/me?teamId=team-1") { header(HttpHeaders.Authorization, "Bearer ${first.token}") }
        assertEquals(HttpStatusCode.OK, after.status)
    }

    // ── 微信配对通道 ─────────────────────────────────────────────────────────

    @Test
    fun `微信配对 start 到 poll 换 token 且幂等`() = testApplication {
        val auth = service(wechatAuthUrl = "https://auth.example.com/wx")
        module(auth, adminToken = "admin-secret")
        val startRaw = client.post("/v1/auth/wechat/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"微信客","teamId":"team-1","memberId":"m-wx"}""")
        }
        val start = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(WechatStartResponse.serializer(), startRaw.bodyAsText())
        assertEquals("started", start.status)
        assertTrue(start.authUrl.contains("state=${start.pairingCode}"))
        // 未完成授权 → pending
        val pollPendingRaw = client.post("/v1/auth/wechat/poll") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}"}""")
        }
        assertTrue(pollPendingRaw.bodyAsText().contains("\"status\":\"pending\""))
        // H5 回提交：无 admin token → 401
        val noToken = client.post("/v1/auth/wechat/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}","unionId":"u-100"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, noToken.status)
        // 带 admin token 完成 → poll 拿到 token（幂等重复返回）
        val complete = client.post("/v1/auth/wechat/complete") {
            headers { append("X-Auth-Admin-Token", "admin-secret") }
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}","unionId":"u-100","openId":"o-100"}""")
        }
        val login = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(LoginResponse.serializer(), complete.bodyAsText())
        assertEquals("ok", login.status)
        assertEquals("team-1", login.memberships.firstOrNull()?.teamId)
        assertEquals("m-wx", login.memberships.firstOrNull()?.memberId)
        val pollOk = client.post("/v1/auth/wechat/poll") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}"}""")
        }
        assertTrue(pollOk.bodyAsText().contains("\"token\":\"${login.token}\""))
        // 微信未配置 → disabled
        val plain = service()
        assertEquals("disabled", plain.startWechatPairing("", "", "").status)
        // 不存在的配对码
        assertEquals("not_found", auth.pollWechatPairing("999999").status)
    }

    @Test
    fun `配对码过期后不可用`() {
        val auth = service(wechatAuthUrl = "https://auth.example.com/wx")
        val start = auth.startWechatPairing("小白", "", "")
        nowMs += 11 * 60 * 1000
        assertEquals("not_found", auth.pollWechatPairing(start.pairingCode).status)
    }

    // ── 自托管微信授权页（server 资产：page 跳微信 OAuth → callback 换身份 → 自动完成配对） ──

    @Test
    fun `自托管授权页全链路 page 跳转 callback 换身份并自动完成配对`() = testApplication {
        val auth = service(
            wechatAppId = "wx-app",
            wechatAppSecret = "wx-secret",
            publicBaseUrl = "https://repland.example.com",
            wechatCodeExchanger = WechatCodeExchanger { code ->
                if (code == "wx-good") WechatIdentity("u-200", "o-200") else null
            },
        )
        module(auth)
        // 禁跟随重定向：page 的 302 指向微信外网，testApplication 环境不可达，只断言 Location
        val client = createClient { followRedirects = false }
        // start：未配外部 authUrl，自托管条件齐备 → authUrl 指向本服务授权页
        val startRaw = client.post("/v1/auth/wechat/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"自托管客","teamId":"team-1","memberId":"m-self"}""")
        }
        val start = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(WechatStartResponse.serializer(), startRaw.bodyAsText())
        assertEquals("started", start.status)
        assertTrue(start.authUrl.startsWith("https://repland.example.com/v1/auth/wechat/page?state="))
        // page：302 跳微信 OAuth，state（=配对码）与回跳地址原样携带
        val page = client.get("/v1/auth/wechat/page?state=${start.pairingCode}")
        assertEquals(HttpStatusCode.Found, page.status)
        val location = page.headers[HttpHeaders.Location].orEmpty()
        assertTrue(location.startsWith("https://open.weixin.qq.com/connect/oauth2/authorize"))
        assertTrue(location.contains("appid=wx-app"))
        assertTrue(location.contains("scope=snsapi_base"))
        assertTrue(location.contains("state=${start.pairingCode}"))
        assertTrue(location.contains("redirect_uri=https%3A%2F%2Frepland.example.com%2Fv1%2Fauth%2Fwechat%2Fcallback"))
        // callback：code 换身份 → 自动 complete → HTML 成功页
        val callback = client.get("/v1/auth/wechat/callback?code=wx-good&state=${start.pairingCode}")
        assertEquals(HttpStatusCode.OK, callback.status)
        assertTrue(callback.bodyAsText().contains("授权成功"))
        // poll 换 token；成员位按 start 时携带认领
        val poll = client.post("/v1/auth/wechat/poll") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}"}""")
        }
        val login = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(LoginResponse.serializer(), poll.bodyAsText())
        assertEquals("ok", login.status)
        assertEquals("team-1", login.memberships.firstOrNull()?.teamId)
        assertEquals("m-self", login.memberships.firstOrNull()?.memberId)
        // 登录 token 融入既有端点
        val me = client.get("/v1/me?teamId=team-1") { header(HttpHeaders.Authorization, "Bearer ${login.token}") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"memberName\":\"自托管客\""))
    }

    @Test
    fun `自托管回调换身份失败保持 pending 且缺 state 返回错误页`() = testApplication {
        val auth = service(
            wechatAppId = "wx-app",
            wechatAppSecret = "wx-secret",
            publicBaseUrl = "https://repland.example.com",
            wechatCodeExchanger = WechatCodeExchanger { null },
        )
        module(auth)
        val startRaw = client.post("/v1/auth/wechat/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"等待客","teamId":"team-1","memberId":"m-wait"}""")
        }
        val start = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(WechatStartResponse.serializer(), startRaw.bodyAsText())
        // 微信侧换身份失败（无效 code）→ HTML 失败页
        val callback = client.get("/v1/auth/wechat/callback?code=wx-bad&state=${start.pairingCode}")
        assertEquals(HttpStatusCode.OK, callback.status)
        assertTrue(callback.bodyAsText().contains("授权失败"))
        // 配对未被污染：仍 pending（App 重试轮询安全）
        val poll = client.post("/v1/auth/wechat/poll") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"${start.pairingCode}"}""")
        }
        assertTrue(poll.bodyAsText().contains("\"status\":\"pending\""))
        // 缺 state 的授权页 → HTML 错误页（不跳转）
        val page = client.get("/v1/auth/wechat/page")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("无法授权"))
    }

    // ── 账号库持久化 ─────────────────────────────────────────────────────────

    @Test
    fun `账号库文件持久化往返`() {
        val file = Files.createTempFile("repland-auth", ".json")
        val store = AuthStore(file)
        val account = store.insertAccount(AuthAccount("acc-1", phone = "13800006666", displayName = "老白", createdAtMs = 1L))
        store.issueToken(AuthTokenEntry("tok-abc", "acc-1", 2L))
        store.claimMembership(AuthMembership("acc-1", "team-2", "m-bai", "老白", isCaptain = true))

        val reloaded = AuthStore(file)
        assertEquals(account, reloaded.accountById("acc-1"))
        assertEquals("acc-1", reloaded.accountByToken("tok-abc")?.accountId)
        assertNull(reloaded.accountByToken("tok-missing"))
        assertEquals(1, reloaded.membershipsOf("acc-1").size)
        assertEquals("m-bai", reloaded.membershipBySlot("team-2", "m-bai")?.memberId)
        // 槽位被占
        assertNull(reloaded.claimMembership(AuthMembership("acc-9", "team-2", "m-bai", "别人")))
        Files.deleteIfExists(file)
    }
}
