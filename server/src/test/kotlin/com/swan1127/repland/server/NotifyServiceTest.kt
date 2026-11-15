package com.swan1127.repland.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotifyServiceTest {

    private val bindings = listOf(
        AccountBinding("tok-captain", "team-1", "m-captain", "队长", "ou_captain"),
        AccountBinding("tok-alice", "team-1", "m-alice", "Alice", "ou_alice"),
        AccountBinding("tok-bob", "team-1", "m-bob", "Bob", ""),          // 未绑定 open_id
        AccountBinding("tok-other", "team-2", "m-carol", "Carol", "ou_carol"),
    )

    private class FakeSender : FeishuSender {
        val sent = mutableListOf<Pair<String, String>>()
        val cards = mutableListOf<Pair<String, String>>()
        var failTimes = 0
        override suspend fun sendText(openId: String, message: String): Boolean {
            if (failTimes > 0) { failTimes--; return false }
            sent += openId to message
            return true
        }

        override suspend fun sendInteractive(openId: String, cardJson: String): Boolean {
            if (failTimes > 0) { failTimes--; return false }
            cards += openId to cardJson
            return true
        }
    }

    private fun request(
        channel: String = "PUSH_AND_FEISHU",
        audienceId: String = "m-alice",
        notificationId: String = "ntf-1",
        teamId: String = "team-1",
        card: NotifyCard? = null,
    ) = NotifyRequest(
        teamId = teamId, teamName = "逐晓", audienceId = audienceId,
        audienceName = "Alice", channel = channel, message = "测试消息", notificationId = notificationId,
        card = card,
    )

    @Test
    fun `非飞书通道直接跳过`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        assertEquals(DispatchResult.SKIPPED_CHANNEL, service.dispatch(request(channel = "APP_ONLY"), bindings[0]))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `正常投递同步送达`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        assertEquals(DispatchResult.DELIVERED, service.dispatch(request(), bindings[0]))
        assertEquals(listOf("ou_alice" to "【逐晓】Alice：测试消息"), sender.sent)
    }

    @Test
    fun `幂等键重复忽略且不二次投递`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        assertEquals(DispatchResult.DELIVERED, service.dispatch(request(), bindings[0]))
        assertEquals(DispatchResult.SKIPPED_DUPLICATE, service.dispatch(request(), bindings[0]))
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `无绑定降级且不占用幂等键`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        assertEquals(DispatchResult.DROPPED_NO_BINDING, service.dispatch(request(audienceId = "m-ghost"), bindings[0]))
        // 未绑定 open_id
        assertEquals(DispatchResult.DROPPED_NO_BINDING, service.dispatch(request(audienceId = "m-bob"), bindings[0]))
        assertTrue(sender.sent.isEmpty())
        // 同一幂等键随后可正常投递（降级路径不记账）
        assertEquals(DispatchResult.DELIVERED, service.dispatch(request(), bindings[0]))
    }

    @Test
    fun `飞书侧拒绝返回RETRY_LATER`() = runTest {
        val sender = FakeSender().apply { failTimes = 1 }
        val service = NotifyService(AccountRegistry(bindings), sender)
        assertEquals(DispatchResult.RETRY_LATER, service.dispatch(request(), bindings[0]))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `飞书凭证未配置则全局降级`() = runTest {
        val service = NotifyService(AccountRegistry(bindings), null)
        assertEquals(DispatchResult.DROPPED_FEISHU_DISABLED, service.dispatch(request(), bindings[0]))
    }

    // ---------- T4c：interactive 卡片投递 ----------

    @Test
    fun `带卡片的请求走interactive且value符合契约`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        val result = service.dispatch(
            request(notificationId = "ntf-card", card = NotifyCard("draft_confirm", "d-1", 3)),
            bindings[0],
        )
        assertEquals(DispatchResult.DELIVERED, result)
        assertTrue(sender.sent.isEmpty()) // 卡片请求不再发文本
        val (openId, cardJson) = sender.cards.single()
        assertEquals("ou_alice", openId)
        // 卡片按钮 value 契约：kind/teamId/draftId/revision（CardActionService 侧解析）
        assertTrue(cardJson.contains("\"kind\":\"confirm_draft\""))
        assertTrue(cardJson.contains("\"kind\":\"abandon_draft\""))
        assertTrue(cardJson.contains("\"teamId\":\"team-1\""))
        assertTrue(cardJson.contains("\"draftId\":\"d-1\""))
        assertTrue(cardJson.contains("\"revision\":3"))
        assertTrue(cardJson.contains("待确认草案"))
    }

    @Test
    fun `裁决卡片携带维持与收回按钮`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        val result = service.dispatch(
            request(notificationId = "ntf-card-2", card = NotifyCard("transfer_adjudication", "obj-1", 1)),
            bindings[0],
        )
        assertEquals(DispatchResult.DELIVERED, result)
        val cardJson = sender.cards.single().second
        assertTrue(cardJson.contains("\"kind\":\"keep_transfer\""))
        assertTrue(cardJson.contains("\"kind\":\"reclaim_transfer\""))
        assertTrue(cardJson.contains("\"draftId\":\"obj-1\""))
    }

    @Test
    fun `未知卡片类型按投递失败处理`() = runTest {
        val sender = FakeSender()
        val service = NotifyService(AccountRegistry(bindings), sender)
        val result = service.dispatch(
            request(notificationId = "ntf-card-3", card = NotifyCard("unknown_kind", "d-9", 1)),
            bindings[0],
        )
        assertEquals(DispatchResult.RETRY_LATER, result)
        assertTrue(sender.cards.isEmpty())
    }
}
