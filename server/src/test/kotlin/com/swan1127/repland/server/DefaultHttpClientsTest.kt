package com.swan1127.repland.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * 生产 HTTP 客户端回归测试（MockEngine 拦截，不出网）：
 * 共享 HttpClient 未装 ContentNegotiation，请求体必须为显式 JSON 字符串——
 * 此前 setBody(mapOf) 在运行期序列化失败（静默吞成 false/异常），本组测试锁定线上格式防回归。
 */
class DefaultHttpClientsTest {

    private data class Wire(val body: String)

    private fun client(status: HttpStatusCode, body: String, wire: MutableList<Wire>) = HttpClient(
        MockEngine { request ->
            wire += Wire(
                when (val content = request.body) {
                    is OutgoingContent.ByteArrayContent -> content.bytes().toString(Charsets.UTF_8)
                    else -> content.toString()
                },
            )
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun `飞书 defaultFetcher 发送显式 JSON 并解析 token 与有效期`() = runTest {
        val wire = mutableListOf<Wire>()
        val fetcher = TokenManager.defaultFetcher(
            client(HttpStatusCode.OK, """{"code":0,"tenant_access_token":"t-1","expire":7200}""", wire),
            "cli-1", "sec-1", "https://open.feishu.cn",
        )
        assertEquals("t-1" to 7200L, fetcher.fetch())
        assertEquals(1, wire.size)
        assertTrue(wire[0].body.contains("\"app_id\":\"cli-1\""), "请求体应含 app_id：${wire[0].body}")
        assertTrue(wire[0].body.contains("\"app_secret\":\"sec-1\""), "请求体应含 app_secret：${wire[0].body}")
    }

    @Test
    fun `defaultFetcher 业务码非 0 抛出带响应体的错误`() = runTest {
        val fetcher = TokenManager.defaultFetcher(
            client(HttpStatusCode.OK, """{"code":99991663,"msg":"app_id invalid"}""", mutableListOf()),
            "cli-1", "sec-1", "https://open.feishu.cn",
        )
        try {
            fetcher.fetch()
            fail("业务码非 0 应抛错")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("99991663"))
        }
    }

    @Test
    fun `短信网关 sender 发送显式 JSON 并按 2xx 判定受理`() = runTest {
        val wire = mutableListOf<Wire>()
        val ok = WebhookSmsSender(client(HttpStatusCode.OK, "{}", wire), "https://sms.example.com/send")
        assertTrue(ok.send("13800001234", "123456"))
        assertEquals(1, wire.size)
        assertTrue(wire[0].body.contains("\"phone\":\"13800001234\""), "请求体应含 phone：${wire[0].body}")
        assertTrue(wire[0].body.contains("\"code\":\"123456\""), "请求体应含 code：${wire[0].body}")
        val rejected = WebhookSmsSender(client(HttpStatusCode.ServiceUnavailable, "down", mutableListOf()), "https://sms.example.com/send")
        assertEquals(false, rejected.send("13800001234", "123456"), "非 2xx 应判未受理")
    }
}
