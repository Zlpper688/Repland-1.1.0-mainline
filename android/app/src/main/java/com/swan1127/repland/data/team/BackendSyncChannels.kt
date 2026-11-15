package com.swan1127.repland.data.team

import com.swan1127.repland.domain.team.ConfirmationApplier
import com.swan1127.repland.domain.team.LedgerEntry

/**
 * T4c 后端同步通道契约：联网实现位于 src/remote，经反射加载（§14 离线边界）。
 * 全部为 fire-and-forget / 可降级语义：副本与日程投递失败不影响 App 功能（§5/§6）。
 */

/** 确认记录拉取（§13 拉取起步）：GET /v1/confirmations?since= 游标增量。 */
fun interface ConfirmationPuller {
    suspend fun pull(baseUrl: String, accountToken: String, since: Long): ConfirmationPullResult
}

/** 拉取结果：cursor 单调递增，App 端持久化避免重复消费。 */
data class ConfirmationPullResult(
    val items: List<ConfirmationApplier.Record>,
    val cursor: Long,
)

/** 台账条目副本投递（§5 只做副本不做同步）：POST /v1/ledger，响应结果可忽略。 */
fun interface LedgerSink {
    suspend fun push(entry: LedgerEntry, teamId: String, teamName: String, memberName: String)
}

/** 会议候选 → 后端写飞书日历（§6 队长确认后创建）：POST /v1/meetings，响应结果可忽略。 */
fun interface MeetingPoster {
    suspend fun post(
        teamId: String,
        teamName: String,
        title: String,
        startAtMs: Long,
        endAtMs: Long,
        attendeeMemberIds: List<String>,
        reminderMinutes: Int,
    )
}

// ---------- §13 表级同步第一块：队伍会话快照双向同步 ----------

/** 快照上行结果：conflict=true 时 serverPayload/serverRevision 为服务端当前版（客户端拉取后重放）。 */
data class TeamStatePushResult(
    val ok: Boolean,
    val message: String,
    val revision: Long = 0,
    val conflict: Boolean = false,
    val serverRevision: Long = 0,
    val serverPayload: String = "",
)

/** 快照下行结果：hasNewer=false 表示服务端没有比 since 新的版本。 */
data class TeamStatePullResult(
    val ok: Boolean,
    val message: String,
    val hasNewer: Boolean = false,
    val revision: Long = 0,
    val updatedByMember: String = "",
    val payload: String = "",
)

/** 快照上行：PUT /v1/teams/{teamId}/state，baseRevision 守卫（0 = 首写）。 */
fun interface TeamStatePusher {
    suspend fun push(
        baseUrl: String,
        accountToken: String,
        teamId: String,
        baseRevision: Long,
        payload: String,
        updatedByMember: String,
    ): TeamStatePushResult
}

/** 快照下行：GET /v1/teams/{teamId}/state?since=，只取比 since 新的版本。 */
fun interface TeamStatePuller {
    suspend fun pull(baseUrl: String, accountToken: String, teamId: String, since: Long): TeamStatePullResult
}
