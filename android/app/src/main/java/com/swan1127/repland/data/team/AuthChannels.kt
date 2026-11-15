package com.swan1127.repland.data.team

/**
 * 账号登录通道（T3 补齐，TEAM_COLLAB_DESIGN.md §13 双通道）：
 * main 只定义结果结构与 fun interface，联网实现在 src/remote 的 HttpAuthClient——
 * §14 分级守卫：release 构建无 src/remote → 反射失败 → 降级为明确提示，登录入口不可用但 App 全功能离线可用。
 * 登录成功后 token 写入 BackendConfigStore.accountToken，此后推送/确认拉取/台账/日历四通道自动携带。
 */

/** 请求验证码结果；devCode 仅服务端开发模式回显（未配短信网关时日志出码）。 */
data class SmsCodeResult(
    val ok: Boolean,
    val message: String,
    val devCode: String? = null,
    val retryAfterSeconds: Int = 0,
)

/** 微信签发二维码页面，轮询凭证只留在发起登录的 App。 */
data class WechatQrStartResult(
    val ok: Boolean,
    val message: String,
    val authUrl: String = "",
    val pollToken: String = "",
    val expiresInSeconds: Int = 0,
)

/** 统一登录结果（验证码校验 / 微信轮询共用）。 */
data class AuthLoginResult(
    val ok: Boolean,
    val message: String,
    val token: String = "",
    val accountId: String = "",
    val displayName: String = "",
    val created: Boolean = false,
    val avatarUrl: String = "",
    val pending: Boolean = false,
)

/** 手机号验证码通道：请求出码。 */
fun interface SmsCodeRequester {
    suspend fun request(baseUrl: String, phone: String): SmsCodeResult
}

/** 手机号验证码通道：校验换 token；teamId/memberId 用于认领预置成员位（选填）。 */
fun interface SmsVerifier {
    suspend fun verify(
        baseUrl: String,
        phone: String,
        code: String,
        displayName: String,
        teamId: String,
        memberId: String,
    ): AuthLoginResult
}

fun interface WechatQrStarter {
    suspend fun start(baseUrl: String): WechatQrStartResult
}

fun interface WechatQrPoller {
    suspend fun poll(baseUrl: String, pollToken: String): AuthLoginResult
}

fun interface WechatAvatarDownloader {
    suspend fun download(avatarUrl: String): ByteArray?
}

/** 账号管理：登出（吊销调用方自己的 token；多设备并存互不影响）。 */
fun interface AccountLogout {
    suspend fun logout(baseUrl: String, accountToken: String): Boolean
}

/** 账号信息（/v1/auth/account-me；成员位为该账号在各队伍里的席位）。 */
data class AccountMembershipInfo(
    val teamId: String,
    val memberId: String,
    val memberName: String,
    val isCaptain: Boolean,
)

data class AccountMeInfo(
    val accountId: String,
    val phoneMasked: String,
    val wechatBound: Boolean,
    val displayName: String,
    val memberships: List<AccountMembershipInfo>,
)

/** 账号信息拉取（登录后展示与账号管理用）。 */
fun interface AccountMeFetcher {
    suspend fun fetch(baseUrl: String, accountToken: String): AccountMeInfo?
}
