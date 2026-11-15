package com.swan1127.repland.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenManagerTest {

    private class FakeFetcher : TokenFetcher {
        var calls = 0
        var failTimes = 0
        var throwNet = 0
        override suspend fun fetch(): Pair<String, Long> {
            calls++
            if (throwNet > 0) { throwNet--; throw java.io.IOException("网络抖动") }
            if (failTimes > 0) { failTimes--; error("code=99991663 bad secret") }
            return "tok-${calls}" to 7200L
        }
    }

    @Test
    fun `首次拉取成功并缓存`() = runTest {
        val fetcher = FakeFetcher()
        val manager = TokenManager(fetcher, clock = { 0L })
        assertEquals("tok-1", manager.get())
        assertEquals("tok-1", manager.get())
        assertEquals(1, fetcher.calls, "第二次 get 应命中缓存")
    }

    @Test
    fun `临期自动刷新`() = runTest {
        val fetcher = FakeFetcher()
        var now = 0L
        val manager = TokenManager(fetcher, clock = { now })
        assertEquals("tok-1", manager.get())
        now = 2 * 3600 * 1000 - 4 * 60 * 1000 // 距过期仅 4 分钟（< 5 分钟提前量）
        assertEquals("tok-2", manager.get(), "临期应重新拉取")
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `业务码失败重试一次后仍失败则抛错`() = runTest {
        val fetcher = FakeFetcher().apply { failTimes = 99 }
        val manager = TokenManager(fetcher, clock = { 0L })
        val ex = try { manager.get(); null } catch (e: IllegalStateException) { e }
        assertTrue(ex != null && ex.message!!.contains("已重试一次"))
        assertEquals(2, fetcher.calls, "应恰好重试一次")
    }

    @Test
    fun `第一次网络异常第二次成功`() = runTest {
        val fetcher = FakeFetcher().apply { throwNet = 1 }
        val manager = TokenManager(fetcher, clock = { 0L })
        assertEquals("tok-2", manager.get())
        assertEquals(2, fetcher.calls)
    }
}
