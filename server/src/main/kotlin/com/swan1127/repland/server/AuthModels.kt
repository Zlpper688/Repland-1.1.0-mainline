package com.swan1127.repland.server

import kotlinx.serialization.Serializable

/**
 * 账号体系（T3 补齐，TEAM_COLLAB_DESIGN.md §13）：
 * - 双通道登录：手机号验证码 + 微信（配对码流程）；
 * - 凭证哲学：短信网关 / 微信授权页全部服务端持有，App 只配「后端地址 + 账号 token」；
 * - 账号 ↔ 队伍成员位（membership）分离：一个账号可入多队（§16 一账号多队）；
 *   登录时可携带 teamId+memberId 认领预置成员位（手机号/微信标识匹配或显式认领）。
 */

/** 账号：以手机号或微信 unionId 为唯一标识的自然人。 */
@Serializable
data class AuthAccount(
    val accountId: String,
    /** 11 位大陆手机号（去符号）；空 = 未绑手机号 */
    val phone: String = "",
    val wechatUnionId: String = "",
    val wechatOpenId: String = "",
    val displayName: String,
    val createdAtMs: Long,
    val avatarUrl: String = "",
)

/** 登录令牌：多设备并存（登录不吊销旧 token；吊销能力后置）。 */
@Serializable
data class AuthTokenEntry(
    val token: String,
    val accountId: String,
    val createdAtMs: Long,
)

/** 账号在某支队伍里的成员位（与既有 AccountBinding 契约字段对齐）。 */
@Serializable
data class AuthMembership(
    val accountId: String,
    val teamId: String,
    val memberId: String,
    val memberName: String,
    val isCaptain: Boolean = false,
    /** 空 = 未绑飞书 open_id（通知仅走 App，语义与 AccountBinding.openId 一致） */
    val feishuOpenId: String = "",
)

// ── 手机号验证码通道 ─────────────────────────────────────────────────────────

@Serializable
data class SmsRequestRequest(val phone: String)

@Serializable
data class SmsRequestResponse(
    val status: String,
    val detail: String = "",
    /** 仅开发模式（REPLAND_SMS_DEV_MODE=1 或未配短信网关）回显，生产为 null */
    val devCode: String? = null,
    val retryAfterSeconds: Int = 0,
)

@Serializable
data class SmsVerifyRequest(
    val phone: String,
    val code: String,
    val displayName: String = "",
    /** 认领预置成员位（可选）：两者同时提供才尝试绑定 */
    val teamId: String = "",
    val memberId: String = "",
)

// ── 微信通道（配对码） ───────────────────────────────────────────────────────

@Serializable
data class WechatStartRequest(val displayName: String = "", val teamId: String = "", val memberId: String = "")

@Serializable
data class WechatStartResponse(
    val status: String,
    val detail: String = "",
    val pairingCode: String = "",
    /** 微信内打开的授权页（服务端配置的 H5，授权完成后回调 /v1/auth/wechat/complete） */
    val authUrl: String = "",
    val expiresInSeconds: Int = 0,
)

/** 微信授权 H5 页授权成功后提交（建议部署侧以同源/反代鉴权保护；配置 admin token 后需带请求头）。 */
@Serializable
data class WechatCompleteRequest(
    val pairingCode: String,
    val unionId: String,
    val openId: String = "",
    val displayName: String = "",
    val teamId: String = "",
    val memberId: String = "",
)

@Serializable
data class WechatPollRequest(val pairingCode: String)

@Serializable
data class WechatQrStartResponse(
    val status: String,
    val detail: String = "",
    val authUrl: String = "",
    val pollToken: String = "",
    val expiresInSeconds: Int = 0,
)

@Serializable
data class WechatQrPollRequest(val pollToken: String)

/** 微信身份（OAuth code 换取）；unionId 缺失时以 openId 兜底（仍全局唯一）。 */
data class WechatIdentity(
    val unionId: String,
    val openId: String,
    val nickname: String = "",
    val avatarUrl: String = "",
)

// ── 统一登录结果 ─────────────────────────────────────────────────────────────

@Serializable
data class LoginResponse(
    val status: String,
    val detail: String = "",
    /** status = ok 时下发 */
    val token: String = "",
    val accountId: String = "",
    val displayName: String = "",
    val created: Boolean = false,
    val memberships: List<AuthMembership> = emptyList(),
    val avatarUrl: String = "",
)

/** 账号级信息（区别于 team-scoped 的 GET /v1/me）。 */
@Serializable
data class AccountMeResponse(
    val accountId: String,
    /** 脱敏手机号：前 3 后 4 */
    val phoneMasked: String,
    val wechatBound: Boolean,
    val displayName: String,
    val memberships: List<AuthMembership>,
    val avatarUrl: String = "",
)

// ── 账号管理（登出 / 管理端账号清单） ────────────────────────────────────────

@Serializable
data class LogoutRequest(val token: String = "")

@Serializable
data class LogoutResponse(val status: String, val detail: String = "")

@Serializable
data class AdminAccountsResponse(val status: String, val detail: String = "", val accounts: List<AdminAccountView> = emptyList())

@Serializable
data class AdminAccountView(
    val accountId: String,
    val phoneMasked: String,
    val wechatBound: Boolean,
    val displayName: String,
    val createdAtMs: Long,
    val memberships: List<AuthMembership> = emptyList(),
)

// ── 团队状态双向同步（§13 表级同步的第一块：队伍会话快照） ────────────────────
// 本地 Room 仍是主副本；服务端按 teamId 存整份快照 + 单调 revision。
// 写入带 baseRevision 守卫：不匹配返回 conflict 并附服务端当前版本（客户端拉取后重放）。

@Serializable
data class TeamStatePutRequest(
    /** 0 = 无条件写入（队伍首个上行者）；否则必须等于服务端当前 revision */
    val baseRevision: Long = 0,
    /** TeamStateCodec schema 1 整份快照（与 Room team_sessions 的 payload 同构） */
    val payload: String,
    val updatedByMember: String = "",
)

@Serializable
data class TeamStateResponse(
    val status: String,
    val detail: String = "",
    val revision: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val updatedByMember: String = "",
    /** status = ok / conflict 时携带快照原文 */
    val payload: String = "",
)
