package com.swan1127.repland.data.team

/**
 * mem0 云记忆同步通道（§12 可选远端同步层，T5 收尾）：
 * main 只定义结果结构与 fun interface，联网实现在 src/remote 的 HttpMemoryClient——
 * §14 分级守卫：release 构建无 src/remote → 反射失败 → 降级为明确提示。
 * 本地 JSON 永远是主副本（离线优先不变）：同步失败只影响云端沉淀，不改变本地记忆。
 */

/** 云端同步结果：accepted/skipped 为服务端聚合计数（mem0 网关逐条受理）。 */
data class MemorySyncResult(
    val ok: Boolean,
    val message: String,
    val accepted: Int = 0,
    val skipped: Int = 0,
)

/** 云端语义检索结果：mem0 返回的记忆文本列表。 */
data class MemorySearchResult(
    val ok: Boolean,
    val message: String,
    val memories: List<String> = emptyList(),
)

/** 个人记忆域推送：画像要点 + 参赛档案导出文本（服务端按 kind 分流写 mem0）。 */
fun interface MemoryPusher {
    suspend fun push(baseUrl: String, token: String, facts: List<String>, archives: List<String>): MemorySyncResult
}

/** 云端记忆语义检索（账号域隔离：服务端以 accountId 为 mem0 user_id）。 */
fun interface MemorySearcher {
    suspend fun search(baseUrl: String, token: String, query: String): MemorySearchResult
}
