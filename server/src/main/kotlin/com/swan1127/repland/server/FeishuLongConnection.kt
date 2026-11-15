package com.swan1127.repland.server

import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse
import com.lark.oapi.event.cardcallback.model.CallBackToast
import com.lark.oapi.ws.Client

/**
 * 飞书长连接（T4b，FEISHU_INTEGRATION_NOTES §4）：
 * 官方 SDK WS 全双工通道，免公网回调、免验签；断线自动重连由 SDK 承担。
 * 卡片按钮回调（card.action.trigger）→ [CardActionService] 校验 + 幂等记账 → toast 反馈。
 *
 * 本类只是 SDK 胶水：无业务逻辑（全部在 CardActionService，可单测）。
 * 平台侧注意（§4）：开放平台「事件与回调」订阅方式须选「使用长连接接收」，保存时本程序必须在线。
 */
class FeishuLongConnection(
    private val appId: String,
    private val appSecret: String,
    private val cardService: CardActionService,
) {

    private var client: Client? = null

    /** 启动 WS 客户端（非阻塞）；重复调用抛错。连接失败由 SDK 内部重试，不阻断 HTTP 服务。 */
    fun start() {
        check(client == null) { "飞书长连接已启动" }
        val dispatcher = EventDispatcher.newBuilder("", "")
            .onP2CardActionTrigger(object : P2CardActionTriggerHandler() {
                override fun handle(event: P2CardActionTrigger): P2CardActionTriggerResponse {
                    val data = event.event
                    val (result, _) = cardService.dispatch(
                        operatorOpenId = data.operator?.openId.orEmpty(),
                        value = data.action?.value,
                    )
                    return P2CardActionTriggerResponse().apply {
                        toast = CallBackToast().apply {
                            type = if (result == CardActionService.Result.RECORDED) "success" else "fail"
                            content = when (result) {
                                CardActionService.Result.RECORDED -> "已记录确认"
                                CardActionService.Result.DUPLICATE -> "该确认已记录（幂等去重）"
                                CardActionService.Result.FORBIDDEN_NOT_CAPTAIN -> "仅队长可执行该操作"
                                CardActionService.Result.UNKNOWN_OPERATOR -> "未找到账号绑定"
                                CardActionService.Result.INVALID_PAYLOAD -> "卡片动作格式无效"
                            }
                        }
                    }
                }
            })
            .build()
        val started = Client.Builder(appId, appSecret)
            .eventHandler(dispatcher)
            .autoReconnect(true)
            .build()
        started.start()
        client = started
    }

    fun close() {
        client?.close()
        client = null
    }
}
