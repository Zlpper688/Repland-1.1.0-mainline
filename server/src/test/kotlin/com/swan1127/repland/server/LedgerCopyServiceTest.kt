package com.swan1127.repland.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerCopyServiceTest {

    private class FakeBitable : BitableWriter {
        val records = mutableListOf<Map<String, Any?>>()
        var failTimes = 0
        var callCount = 0
        override suspend fun appendRecords(records: List<Map<String, Any?>>): Boolean {
            callCount++
            if (failTimes > 0) { failTimes--; return false }
            this.records += records
            return true
        }
    }

    private fun request(entryId: String = "led-1") = LedgerCopyRequest(
        teamId = "team-1",
        teamName = "逐晓",
        entryId = entryId,
        kind = "FINANCE",
        evidence = "¥25.00：团建聚餐",
        memberName = "Alice",
        occurredAtMs = 1_732_000_000_000,
        externalUrl = null,
        amountCents = 2500,
        isBaseline = true,
    )

    @Test
    fun `字段映射符合第五章副本表结构`() = runTest {
        val bitable = FakeBitable()
        val service = LedgerCopyService(bitable, retryDelayMs = 1)
        val fields = service.fieldsOf(request())
        assertEquals(1_732_000_000_000L, fields["时间"])
        assertEquals("Alice", fields["成员"])
        assertEquals("FINANCE", fields["类型"])
        assertEquals("¥25.00：团建聚餐", fields["事项"])
        assertEquals("逐晓", fields["队伍"])
        assertEquals(2500L, fields["金额"])
        assertEquals(true, fields["是否基线"])
        assertNull(fields["证据链接"]) // 缺省不写该列
    }

    /** 队列消费是常驻协程（永不返回），用真实时间 + 轮询断言，避免虚拟时间调度的版本差异。 */
    private fun runBlockingWith(scope: CoroutineScope, block: suspend () -> Unit) = runBlocking {
        try {
            block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `offer入队后start消费写入`() = runBlockingWith(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        val bitable = FakeBitable()
        val service = LedgerCopyService(bitable, retryDelayMs = 1)
        service.start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(LedgerCopyResult.ACCEPTED, service.offer(request()))
        withTimeout(5_000) { while (bitable.records.isEmpty()) delay(10) }
        assertEquals("¥25.00：团建聚餐", bitable.records[0]["事项"])
        assertEquals(2500L, bitable.records[0]["金额"])
    }

    @Test
    fun `entryId幂等重复忽略`() = runBlockingWith(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        val bitable = FakeBitable()
        val service = LedgerCopyService(bitable, retryDelayMs = 1)
        service.start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(LedgerCopyResult.ACCEPTED, service.offer(request()))
        assertEquals(LedgerCopyResult.DUPLICATE, service.offer(request()))
        withTimeout(5_000) { while (bitable.records.isEmpty()) delay(10) }
        delay(50)
        assertEquals(1, bitable.records.size) // 重复键不入队
    }

    @Test
    fun `未配置bitable全局降级`() = runTest {
        val service = LedgerCopyService(null)
        assertEquals(LedgerCopyResult.DISABLED, service.offer(request()))
    }

    @Test
    fun `写入失败重试三次后丢弃`() = runTest {
        val bitable = FakeBitable().apply { failTimes = 99 }
        val service = LedgerCopyService(bitable, retryDelayMs = 1)
        service.deliverWithRetry(request())
        assertEquals(3, bitable.callCount) // 3 次退避重试，仍失败丢弃
        assertTrue(bitable.records.isEmpty())
    }

    @Test
    fun `临时失败后重试成功`() = runTest {
        val bitable = FakeBitable().apply { failTimes = 2 }
        val service = LedgerCopyService(bitable, retryDelayMs = 1)
        service.deliverWithRetry(request())
        assertEquals(3, bitable.callCount)
        assertEquals(1, bitable.records.size)
    }
}
