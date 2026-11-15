package com.swan1127.repland.server

/**
 * 卡片按钮回调 → 确认动作映射（T4b，FEISHU_INTEGRATION_NOTES §4）。
 *
 * 卡片 value 契约（发卡方构造按钮 value JSON 对象；卡片消息在 T4c 随 im/v1 interactive 落地）：
 * ```
 * {"kind":"confirm_draft|abandon_draft|keep_transfer|reclaim_transfer",
 *  "teamId":"t-x","draftId":"d-1","revision":3}
 * ```
 * 纪律（§7/§14）：回调只等价于一次显式确认操作，不直接写业务状态；后端只做
 * 操作人校验 + 幂等记账，落库由 App 增量拉取后并入本地主副本。
 *
 * 权限（§10 队长默认全权限）：四类动作均为裁决类，操作人必须绑定本队伍且 isCaptain；
 * 权限控制台数据同步到后端后按成员权限细化。
 */
class CardActionService(
    private val registry: AccountRegistry,
    private val store: ConfirmationStore,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {

    enum class Result { RECORDED, DUPLICATE, UNKNOWN_OPERATOR, FORBIDDEN_NOT_CAPTAIN, INVALID_PAYLOAD }

    /** kind → 人类可读动作名（卡片 toast 用） */
    private val kindLabels = mapOf(
        "confirm_draft" to "确认草案生效",
        "abandon_draft" to "放弃草案",
        "keep_transfer" to "维持转派",
        "reclaim_transfer" to "收回转派",
    )

    /**
     * 处理一次卡片回调。value 为飞书回调里的按钮 value Map（SDK 已反序列化）。
     * 返回（结果, 记录）；除 RECORDED 外记录均为 null。
     */
    fun dispatch(operatorOpenId: String, value: Map<*, *>?): Pair<Result, ConfirmationRecord?> {
        val kind = value?.get("kind")?.toString()
            ?.takeIf { it in kindLabels } ?: return Result.INVALID_PAYLOAD to null
        val teamId = value["teamId"]?.toString()
            ?.takeIf { it.isNotBlank() } ?: return Result.INVALID_PAYLOAD to null
        val draftId = value["draftId"]?.toString()
            ?.takeIf { it.isNotBlank() } ?: return Result.INVALID_PAYLOAD to null
        val revision = (value["revision"] as? Number)?.toInt()
            ?: return Result.INVALID_PAYLOAD to null

        val binding = registry.all().firstOrNull { it.openId == operatorOpenId && it.teamId == teamId }
            ?: return Result.UNKNOWN_OPERATOR to null
        if (!binding.isCaptain) return Result.FORBIDDEN_NOT_CAPTAIN to null

        val recorded = store.record(
            teamId = teamId,
            kind = kind,
            draftId = draftId,
            revision = revision,
            operatorMemberId = binding.memberId,
            operatorName = binding.memberName,
            operatorOpenId = operatorOpenId,
            decidedAt = now(),
        ) ?: return Result.DUPLICATE to null
        return Result.RECORDED to recorded
    }

    fun labelOf(kind: String): String? = kindLabels[kind]
}
