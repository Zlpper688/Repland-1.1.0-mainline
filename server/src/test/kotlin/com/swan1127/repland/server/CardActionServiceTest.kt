package com.swan1127.repland.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardActionServiceTest {

    private val bindings = listOf(
        AccountBinding("tok-captain", "team-1", "m-captain", "队长", "ou_captain", isCaptain = true),
        AccountBinding("tok-alice", "team-1", "m-alice", "Alice", "ou_alice", isCaptain = false),
        AccountBinding("tok-outside", "team-2", "m-bob", "Bob", "ou_bob", isCaptain = true),
    )

    private fun service(store: ConfirmationStore = ConfirmationStore()) =
        CardActionService(AccountRegistry(bindings), store, now = { "2026-10-29T00:00:00Z" })

    private val validValue = mapOf(
        "kind" to "confirm_draft",
        "teamId" to "team-1",
        "draftId" to "draft-1",
        "revision" to 3,
    )

    @Test
    fun `队长回调正常记账`() {
        val (result, record) = service().dispatch("ou_captain", validValue)
        assertEquals(CardActionService.Result.RECORDED, result)
        assertNotNull(record)
        assertEquals("team-1", record.teamId)
        assertEquals("confirm_draft", record.kind)
        assertEquals("m-captain", record.operatorMemberId)
        assertEquals(3, record.revision)
        assertEquals(1L, record.seq)
    }

    @Test
    fun `同键重复回调幂等去重`() {
        val store = ConfirmationStore()
        val svc = service(store)
        svc.dispatch("ou_captain", validValue)
        val (second, record) = svc.dispatch("ou_captain", validValue)
        assertEquals(CardActionService.Result.DUPLICATE, second)
        assertNull(record)
        assertEquals(1, store.size())
    }

    @Test
    fun `非队长回调被拒`() {
        val (result, record) = service().dispatch("ou_alice", validValue)
        assertEquals(CardActionService.Result.FORBIDDEN_NOT_CAPTAIN, result)
        assertNull(record)
    }

    @Test
    fun `未绑定或跨队伍操作人被拒`() {
        val (unknown, _) = service().dispatch("ou_stranger", validValue)
        assertEquals(CardActionService.Result.UNKNOWN_OPERATOR, unknown)
        // Bob 是队长但绑定在 team-2，跨队伍裁决无效
        val (outside, _) = service().dispatch("ou_bob", validValue)
        assertEquals(CardActionService.Result.UNKNOWN_OPERATOR, outside)
    }

    @Test
    fun `非法payload被拒`() {
        val svc = service()
        assertEquals(
            CardActionService.Result.INVALID_PAYLOAD,
            svc.dispatch("ou_captain", mapOf("kind" to "unknown_kind", "teamId" to "team-1", "draftId" to "d", "revision" to 1)).first,
        )
        assertEquals(CardActionService.Result.INVALID_PAYLOAD, svc.dispatch("ou_captain", null).first)
        assertEquals(
            CardActionService.Result.INVALID_PAYLOAD,
            svc.dispatch("ou_captain", mapOf("kind" to "confirm_draft", "teamId" to "team-1", "draftId" to "d")).first,
        )
    }

    @Test
    fun `台账支持按队伍增量拉取`() {
        val store = ConfirmationStore()
        val svc = service(store)
        svc.dispatch("ou_captain", validValue)
        svc.dispatch("ou_bob", mapOf("kind" to "keep_transfer", "teamId" to "team-2", "draftId" to "draft-2", "revision" to 1))
        val team1 = store.since("team-1", 0L)
        assertEquals(1, team1.size)
        assertEquals("confirm_draft", team1.first().kind)
        assertTrue(store.since("team-1", team1.first().seq).isEmpty())
        assertEquals(2, store.since("team-2", 0L).size + team1.size)
    }
}
