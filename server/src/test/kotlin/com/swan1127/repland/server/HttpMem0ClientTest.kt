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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * HttpMem0Client 单元测试（MockEngine 拦截，不出网）。
 * 线上契约以 docs.mem0.ai/api-reference 为真值（2026-10 核实）：
 * Token 鉴权 + Accept 必填、add/search 走 v3 端点、search 的 entity ID 在 filters 内（AND 包装）、
 * 非 2xx/损坏 JSON 静默降级（false/空列表）并记录日志，路由层聚合为 accepted/skipped。
 */
class HttpMem0ClientTest {

    private data class Wire(val url: String, val auth: String?, val accept: String?, val body: String)

    private fun client(status: HttpStatusCode, body: String, wire: MutableList<Wire>) = HttpClient(
        MockEngine { request ->
            wire += Wire(
                request.url.toString(),
                request.headers[HttpHeaders.Authorization],
                request.headers[HttpHeaders.Accept],
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
    fun `add 走 v3 端点携带 Token 头与 user_id kind 元数据`() = runTest {
        val wire = mutableListOf<Wire>()
        val gateway = HttpMem0Client(client(HttpStatusCode.OK, """{"usage":{}}""", wire), "k-123")
        assertTrue(gateway.add("acc-1", "擅长 Kotlin 协程调度", "profile"))
        assertEquals(1, wire.size)
        assertTrue(wire[0].url.endsWith("/v3/memories/add/"), "add 应走 v3 端点：${wire[0].url}")
        assertEquals("Token k-123", wire[0].auth, "mem0 鉴权头为 Token 前缀")
        assertEquals("application/json", wire[0].accept, "Accept 头必填")
        assertTrue(wire[0].body.contains("\"user_id\":\"acc-1\""), "请求体应携带 user_id=accountId：${wire[0].body}")
        assertTrue(wire[0].body.contains("擅长 Kotlin 协程调度"), "请求体应携带记忆文本")
        assertTrue(wire[0].body.contains("\"kind\":\"profile\""), "请求体应携带 kind 元数据")
        assertTrue(wire[0].body.contains("\"source\":\"repland\""), "请求体应携带来源标记")
    }

    @Test
    fun `add 非 2xx 或网络异常返回 false 不抛异常`() = runTest {
        val gateway = HttpMem0Client(client(HttpStatusCode.InternalServerError, "boom", mutableListOf()), "k-123")
        assertFalse(gateway.add("acc-1", "x", "profile"))
        val brokenEngine = HttpClient(MockEngine { error("连接被拒") })
        assertFalse(HttpMem0Client(brokenEngine, "k-123").add("acc-1", "x", "profile"))
    }

    @Test
    fun `search 走 v3 端点 entity 过滤在 filters 内`() = runTest {
        val wire = mutableListOf<Wire>()
        val body = """{"results":[{"id":"m1","memory":"擅长 Kotlin 协程","score":0.9},{"id":"m2","memory":"偏好 Anki","score":0.8}]}"""
        val gateway = HttpMem0Client(client(HttpStatusCode.OK, body, wire), "k-123")
        assertEquals(listOf("擅长 Kotlin 协程", "偏好 Anki"), gateway.search("acc-9", "擅长"))
        assertTrue(wire[0].url.endsWith("/v3/memories/search/"), "search 应走 v3 端点：${wire[0].url}")
        assertEquals("Token k-123", wire[0].auth)
        assertTrue(wire[0].body.contains("\"query\":\"擅长\""), "请求体应携带查询词")
        assertTrue(
            wire[0].body.contains("\"user_id\":\"acc-9\""),
            "entity 过滤必须在 filters 内（AND 包装）：${wire[0].body}",
        )
    }

    @Test
    fun `search 兼容数组格式 损坏响应与 memory 空串过滤`() = runTest {
        val v1 = HttpMem0Client(
            client(HttpStatusCode.OK, """[{"memory":"a"},{"memory":" "},{"other":1},{"memory":"b"}]""", mutableListOf()),
            "k-123",
        )
        assertEquals(listOf("a", "b"), v1.search("u", "q"), "应解析数组格式并过滤空白 memory")
        val broken = HttpMem0Client(client(HttpStatusCode.OK, "not-json", mutableListOf()), "k-123")
        assertTrue(broken.search("u", "q").isEmpty(), "损坏 JSON 应静默返回空列表")
        val rejected = HttpMem0Client(client(HttpStatusCode.Unauthorized, """{"detail":"无效 key"}""", mutableListOf()), "bad-key")
        assertTrue(rejected.search("u", "q").isEmpty(), "401 应静默返回空列表（并记录日志）")
    }
}
