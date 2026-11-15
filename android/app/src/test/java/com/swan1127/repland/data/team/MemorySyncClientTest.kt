package com.swan1127.repland.data.team

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpMemoryClient 单元测试（本地 JDK HttpServer 回环，不出外网）：
 * 空地址/空 token 前置拦截、Bearer 头与请求体线上格式、响应解析（accepted/skipped、memories）、
 * 服务端 disabled 降级提示。
 * §14 边界：实现类在 src/remote（仅 debug 构建编译）→ 反射获取；release 变体测试静默跳过。
 */
class MemorySyncClientTest {

    /** 反射实例化 src/remote 实现；release 构建无该类 → null（调用方跳过）。 */
    private fun client(): Any? = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpMemoryClient").getDeclaredConstructor().newInstance()
    }.getOrNull()

    /** 起一个本地 HttpServer，按 path 前缀路由回 JSON；记录收到的鉴权头与请求体。 */
    private class LocalServer {
        var auth: String? = null
        var body = ""
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        fun route(path: String, code: Int, json: String) {
            server.createContext(path) { exchange ->
                auth = exchange.requestHeaders.getFirst("Authorization")
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = json.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        fun start(): String {
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun stop() = server.stop(0)
    }

    @Test
    fun `空后端地址与空 token 前置拦截并给明确提示`() = runBlocking {
        val impl = client() ?: return@runBlocking
        val pusher = impl as MemoryPusher
        val noUrl = pusher.push("  ", "tok", listOf("a"), emptyList())
        assertFalse(noUrl.ok)
        assertTrue(noUrl.message.contains("后端地址"))
        val noToken = pusher.push("http://127.0.0.1:9", " ", listOf("a"), emptyList())
        assertFalse(noToken.ok)
        assertTrue(noToken.message.contains("登录"))
        val searchNoToken = (impl as MemorySearcher).search("http://127.0.0.1:9", "", "擅长")
        assertFalse(searchNoToken.ok)
        assertTrue(searchNoToken.message.contains("登录"))
    }

    @Test
    fun `推送携带 Bearer 头与请求体并解析 accepted skipped`() = runBlocking {
        val impl = client() ?: return@runBlocking
        val server = LocalServer()
        server.route("/v1/memory/sync", 200, """{"status":"ok","accepted":2,"skipped":1}""")
        val base = server.start()
        try {
            val result = (impl as MemoryPusher).push(
                base, "tok-abc",
                facts = listOf("擅长 Kotlin 协程调度", "偏好轻量 Anki"),
                archives = listOf("参赛档案 · 逐晓队"),
            )
            assertTrue(result.ok)
            assertEquals(2, result.accepted)
            assertEquals(1, result.skipped)
            assertEquals("Bearer tok-abc", server.auth)
            assertTrue("请求体应含画像文本：${server.body}", server.body.contains("擅长 Kotlin 协程调度"))
            assertTrue("请求体应含档案数组", server.body.contains("\"archives\":"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `检索解析 memories 列表并携带查询词`() = runBlocking {
        val impl = client() ?: return@runBlocking
        val server = LocalServer()
        server.route("/v1/memory/search", 200, """{"status":"ok","memories":["擅长 Kotlin","偏好 Anki"]}""")
        val base = server.start()
        try {
            val result = (impl as MemorySearcher).search(base, "tok-abc", "擅长")
            assertTrue(result.ok)
            assertEquals(listOf("擅长 Kotlin", "偏好 Anki"), result.memories)
            assertEquals("Bearer tok-abc", server.auth)
            assertTrue("请求体应含查询词：${server.body}", server.body.contains("擅长"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `服务端未启用时降级为明确提示`() = runBlocking {
        val impl = client() ?: return@runBlocking
        val server = LocalServer()
        server.route("/v1/memory/sync", 503, """{"status":"disabled","detail":"记忆云同步未启用（服务端未配置 MEM0_API_KEY）"}""")
        val base = server.start()
        try {
            val result = (impl as MemoryPusher).push(base, "tok-abc", listOf("a"), emptyList())
            assertFalse(result.ok)
            assertTrue("503 disabled 应透出降级提示：${result.message}", result.message.contains("未启用"))
        } finally {
            server.stop()
        }
    }
}
