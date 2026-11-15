package com.swan1127.repland.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 投递结果 —— 全部非异常路径（§3：未绑定/未配置自动降级，不报错不打断）。
 */
enum class DispatchResult {
    /** 非飞书通道，App 侧本就不该发，后端直接忽略 */
    SKIPPED_CHANNEL,
    /** 幂等键重复，忽略 */
    SKIPPED_DUPLICATE,
    /** 受众无绑定或未绑 open_id —— 降级为仅 App 通知 */
    DROPPED_NO_BINDING,
    /** 已送达飞书 */
    DELIVERED,
    /** 飞书侧拒绝/网络异常 —— T4a 仅记录结果，重试队列后置（T4b） */
    RETRY_LATER,
    /** 飞书凭证未配置 —— 全局降级 */
    DROPPED_FEISHU_DISABLED,
}

/**
 * 推送管道（T4a）：TeamNotification(PUSH_AND_FEISHU) → 飞书 im/v1 text。
 * - 幂等：notificationId 去重（最近 1024 条 LRU），仅在确认投递路径记账；
 * - 同步投递：HTTP handler 协程内完成，结果直接反映在响应中；
 * - 副本落后不影响 App 功能（§5 写入策略同源）。
 */
class NotifyService(
    private val registry: AccountRegistry,
    private val feishu: FeishuSender?,
) {
    private val recentIds = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 1024
    }
    private val idMutex = Mutex()

    suspend fun dispatch(request: NotifyRequest, requester: AccountBinding): DispatchResult {
        if (request.channel != "PUSH_AND_FEISHU") return DispatchResult.SKIPPED_CHANNEL
        val client = feishu ?: return DispatchResult.DROPPED_FEISHU_DISABLED
        val target = registry.findRecipient(request.teamId, request.audienceId)
            ?: return DispatchResult.DROPPED_NO_BINDING
        if (target.openId.isBlank()) return DispatchResult.DROPPED_NO_BINDING
        // 幂等记账放在确认投递路径：无绑定/降级不占用键
        val first = idMutex.withLock {
            if (recentIds.containsKey(request.notificationId)) false
            else {
                recentIds[request.notificationId] = true
                true
            }
        }
        if (!first) return DispatchResult.SKIPPED_DUPLICATE
        val text = "【${request.teamName}】${request.audienceName}：${request.message}"
        return try {
            // T4c：card != null → interactive 卡片（按钮 value 走 CardActionService 契约）；否则纯文本
            val delivered = when (val card = request.card) {
                null -> client.sendText(target.openId, text)
                else -> {
                    val cardJson = CardBuilder.build(card.cardKind, request.teamId, card.refId, card.revision, text)
                    cardJson != null && client.sendInteractive(target.openId, cardJson)
                }
            }
            if (delivered) DispatchResult.DELIVERED else DispatchResult.RETRY_LATER
        } catch (_: Exception) {
            DispatchResult.RETRY_LATER
        }
    }
}
