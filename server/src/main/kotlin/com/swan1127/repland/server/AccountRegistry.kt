package com.swan1127.repland.server

/**
 * 队员账号注册表：token → 绑定关系。
 *
 * T4a 用 JSON 文件起步（config/accounts.json）；Postgres 持久化按 §17 T3 原计划后置。
 * 文件缺失或为空时注册表为空 —— 全部请求 401，服务本身不崩溃。
 */
class AccountRegistry(bindings: List<AccountBinding>) {

    private val byToken: Map<String, AccountBinding> =
        bindings.associateBy { it.accountToken }

    val size: Int get() = byToken.size

    /** 鉴权：token 不存在返回 null（→ 401） */
    fun authenticate(token: String): AccountBinding? = byToken[token]

    /** 在同一队伍里按 memberId 找投递目标（→ null 时降级为仅 App 通知） */
    fun findRecipient(teamId: String, memberId: String): AccountBinding? =
        byToken.values.firstOrNull { it.teamId == teamId && it.memberId == memberId }

    /** 全量绑定视图（卡片回调按 open_id 反查操作人用） */
    fun all(): List<AccountBinding> = byToken.values.toList()

    companion object {
        fun load(path: java.nio.file.Path): AccountRegistry {
            val file = path.toFile()
            if (!file.exists()) return AccountRegistry(emptyList())
            val raw = file.readText()
            if (raw.isBlank()) return AccountRegistry(emptyList())
            val list = kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AccountBinding.serializer()),
                raw,
            )
            return AccountRegistry(list)
        }
    }
}
