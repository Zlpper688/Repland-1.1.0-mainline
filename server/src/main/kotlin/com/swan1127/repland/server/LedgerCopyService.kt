package com.swan1127.repland.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** 副本投递结果 —— 全部非异常路径（§5：副本落后不影响 App 功能）。 */
enum class LedgerCopyResult {
    /** 已入队，异步写多维表格 */
    ACCEPTED,
    /** entryId 幂等键重复，忽略 */
    DUPLICATE,
    /** 多维表格未配置（REPLAND_BITABLE_APP_TOKEN/TABLE_ID 缺失）—— 降级不启用 */
    DISABLED,
    /** 重试队列已满（512），拒绝（副本落后，App 不受影响） */
    QUEUE_FULL,
}

/**
 * 多维表格台账副本服务（T4c，FEISHU_INTEGRATION_NOTES §5 写入策略）：
 * - 台账条目落库成功后异步投递：fire-and-forget + 失败重试队列（3 次退避重试，仍失败记日志丢弃）；
 * - 幂等：entryId 去重（最近 1024 条 LRU），仅在入队路径记账；
 * - 表结构按 §5：时间/成员/类型/事项/证据链接/金额/是否基线 + 队伍列。
 */
class LedgerCopyService(
    private val bitable: BitableWriter?,
    private val retryDelayMs: Long = 500,
) {
    private val queue = Channel<LedgerCopyRequest>(capacity = 512)
    private val recentIds = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 1024
    }
    private val idMutex = Mutex()

    suspend fun offer(request: LedgerCopyRequest): LedgerCopyResult {
        if (bitable == null) return LedgerCopyResult.DISABLED
        val first = idMutex.withLock {
            if (recentIds.containsKey(request.entryId)) false
            else {
                recentIds[request.entryId] = true
                true
            }
        }
        if (!first) return LedgerCopyResult.DUPLICATE
        return if (queue.trySend(request).isSuccess) LedgerCopyResult.ACCEPTED else LedgerCopyResult.QUEUE_FULL
    }

    /** 启动消费协程（§5 异步投递）；仅在 main() 装配时调用。 */
    fun start(scope: CoroutineScope): Job = scope.launch {
        for (request in queue) deliverWithRetry(request)
    }

    /** 3 次退避重试（§2 token 重试纪律同源）；仍失败记日志丢弃——副本允许落后。 */
    internal suspend fun deliverWithRetry(request: LedgerCopyRequest) {
        val writer = bitable ?: return
        repeat(RETRY_ATTEMPTS) { attempt ->
            try {
                if (writer.appendRecords(listOf(fieldsOf(request)))) return
            } catch (failure: Exception) {
                log.warn("台账副本写入异常（第 {} 次）：{}", attempt + 1, failure.message)
            }
            if (attempt < RETRY_ATTEMPTS - 1) delay(retryDelayMs * (attempt + 1))
        }
        log.warn("台账副本写入最终失败，已丢弃：entryId={}（副本落后，不影响 App）", request.entryId)
    }

    /** §5 副本表字段映射；可选列缺省不写（证据链接/金额）。 */
    internal fun fieldsOf(request: LedgerCopyRequest): Map<String, Any?> = buildMap {
        put("时间", request.occurredAtMs)
        put("成员", request.memberName)
        put("类型", request.kind)
        put("事项", request.evidence)
        put("队伍", request.teamName)
        request.externalUrl?.takeIf { it.isNotBlank() }?.let { put("证据链接", it) }
        request.amountCents?.let { put("金额", it) }
        put("是否基线", request.isBaseline)
    }

    private companion object {
        const val RETRY_ATTEMPTS = 3
        val log = LoggerFactory.getLogger(LedgerCopyService::class.java)
    }
}
