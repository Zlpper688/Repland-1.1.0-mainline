package com.swan1127.repland.data.team

import com.swan1127.repland.domain.team.TeamNotification

/**
 * 队伍通知出口抽象（T4a 引入，T4b 按设计 §14 拆分边界）：
 * 接口留在 main（离线边界内），联网实现在 `src/remote`（debug/internal 构建专属），
 * main 侧经 [BackendConfigStore.buildNotifier] 反射加载；release 反射失败 → [NoopNotifier]。
 */
interface TeamNotifier {
    suspend fun dispatch(notification: TeamNotification, teamId: String, teamName: String, audienceMemberId: String?)
}

/** 未配置后端 / release 构建时的空实现：纯开销为零（§3 降级语义）。 */
object NoopNotifier : TeamNotifier {
    override suspend fun dispatch(
        notification: TeamNotification,
        teamId: String,
        teamName: String,
        audienceMemberId: String?,
    ) = Unit
}
