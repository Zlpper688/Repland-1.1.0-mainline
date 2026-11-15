package com.swan1127.repland.data.team

/** 探测报告（T4b 配置面）：ok=true 时携带 /v1/me 解析出的绑定信息。 */
data class BackendProbeReport(
    val ok: Boolean,
    /** HTTP 状态码；0 = 网络层失败/无联网能力 */
    val httpCode: Int,
    /** 用户可读的结果说明 */
    val message: String,
    val memberName: String? = null,
    val teamId: String? = null,
    val isCaptain: Boolean = false,
    /** false = 后端尚未绑定该队员的飞书 open_id（通知仅走 App） */
    val feishuBound: Boolean = false,
)

/**
 * 后端连接探测抽象（T4b）：GET /v1/me 校验地址可达 + token 有效 + 绑定状态。
 * 接口留 main（离线边界内），联网实现在 `src/remote`（§14 边界），反射加载；
 * release 反射失败 → 「此构建不含联网能力」提示。
 */
fun interface BackendProber {
    suspend fun probe(baseUrl: String, accountToken: String): BackendProbeReport
}
