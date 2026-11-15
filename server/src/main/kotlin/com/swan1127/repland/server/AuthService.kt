package com.swan1127.repland.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 登录服务（T3 补齐，§13）：手机号验证码 + 微信配对双通道。
 * - 短信验证码：6 位数字，5 分钟有效，60 秒重发限流，一次性消费；
 * - 微信：服务端签发 6 位配对码（10 分钟），用户在微信内打开授权 H5（authUrl?state=配对码），
 *   H5 授权成功后回提交 complete，App 轮询 poll 换取 token（免微信 SDK / 免公网回调域名）；
 * - 账号合并：手机号与微信 unionId 可聚到同一账号（先到先标识，后绑补齐）；
 * - 登录签发的 token 经 [resolveBinding] 融入既有 AccountBinding 世界观，团队端点零改动。
 */
/** 微信 OAuth code → 身份（自托管授权页回调换取；可注入 stub 便于测试）。 */
fun interface WechatCodeExchanger {
    suspend fun exchange(code: String): WechatIdentity?
}

class AuthService(
    private val store: AuthStore,
    private val smsSender: SmsSender? = null,
    /** dev 模式：请求验证码响应回显 devCode（未配置短信网关时自动为 true） */
    private val smsDevMode: Boolean = true,
    /** 内测名单：非空时只有名单内手机号能拿到验证码（公网无短信网关时必须配，见 DEPLOY.md） */
    private val allowedPhones: Set<String> = emptySet(),
    /** 外部微信授权 H5 地址（覆盖自托管页）；null 时若微信凭证 + 公网地址齐备则用自托管页 */
    private val wechatAuthUrl: String? = null,
    /** 微信网页授权凭证（公众号 appid/secret）；与 publicBaseUrl 三者齐备 → 启用自托管授权页 */
    private val wechatAppId: String? = null,
    private val wechatAppSecret: String? = null,
    private val wechatApiBase: String = "https://api.weixin.qq.com",
    /** 服务端公网地址（自托管授权页 /v1/auth/wechat/page 的对外入口） */
    private val publicBaseUrl: String? = null,
    /** OAuth code 换身份的客户端；null = 自托管回调不工作（仅外部 authUrl 模式） */
    private val wechatCodeExchanger: WechatCodeExchanger? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tokenGen: () -> String = { newHex(32) },
) {

    private data class SmsCode(val code: String, val sentAtMs: Long)
    private data class Pairing(
        val displayName: String,
        val teamId: String,
        val memberId: String,
        val createdAtMs: Long,
        @Volatile var result: LoginResponse? = null,
    )

    private val smsCodes = ConcurrentHashMap<String, SmsCode>()
    private val pairings = ConcurrentHashMap<String, Pairing>()
    private val random = SecureRandom()

    // ── 手机号验证码通道 ─────────────────────────────────────────────────────

    suspend fun requestSmsCode(phoneRaw: String): SmsRequestResponse {
        val phone = normalizePhone(phoneRaw)
            ?: return SmsRequestResponse("invalid_phone", "手机号格式不正确（大陆 11 位）")
        if (allowedPhones.isNotEmpty() && phone !in allowedPhones) {
            return SmsRequestResponse("not_allowed", "这个手机号还没进内测名单，请联系队长加进来")
        }
        val now = clock()
        val previous = smsCodes[phone]
        if (previous != null && now - previous.sentAtMs < RESEND_LIMIT_MS) {
            val retryAfter = ((RESEND_LIMIT_MS - (now - previous.sentAtMs) + 999) / 1000).toInt()
            return SmsRequestResponse("rate_limited", "验证码发送过于频繁，请稍后再试", retryAfterSeconds = retryAfter)
        }
        val code = "%06d".format(random.nextInt(1_000_000))
        val sender = smsSender ?: LoggingSmsSender()
        val accepted = try {
            withTimeoutOrNull(8_000) { sender.send(phone, code) } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!accepted) return SmsRequestResponse("gateway_error", "短信网关受理失败，请稍后重试")
        smsCodes[phone] = SmsCode(code, now)
        return SmsRequestResponse(
            "sent",
            devCode = if (smsDevMode) code else null,
        )
    }

    fun verifySmsCode(
        phoneRaw: String,
        code: String,
        displayName: String,
        teamId: String,
        memberId: String,
    ): LoginResponse {
        val phone = normalizePhone(phoneRaw)
            ?: return LoginResponse("invalid_phone", "手机号格式不正确（大陆 11 位）")
        val now = clock()
        val record = smsCodes[phone]
            ?: return LoginResponse("no_code", "请先获取验证码")
        if (code.trim() != record.code) return LoginResponse("bad_code", "验证码不正确")
        if (now - record.sentAtMs > CODE_TTL_MS) {
            smsCodes.remove(phone)
            return LoginResponse("expired", "验证码已过期，请重新获取")
        }
        smsCodes.remove(phone) // 一次性消费
        return loginOrRegister(phone = phone, wechat = null, displayName = displayName, teamId = teamId, memberId = memberId)
    }

    // ── 微信配对通道 ─────────────────────────────────────────────────────────

    fun startWechatPairing(displayName: String, teamId: String, memberId: String): WechatStartResponse {
        val base = wechatAuthUrl
            ?: if (!wechatAppId.isNullOrBlank() && !wechatAppSecret.isNullOrBlank() && !publicBaseUrl.isNullOrBlank()) {
                "${publicBaseUrl.trimEnd('/')}/v1/auth/wechat/page"
            } else {
                null
            }
            ?: return WechatStartResponse("disabled", "微信登录未启用（服务端未配置授权页）")
        val code = "%06d".format(random.nextInt(1_000_000))
        pairings[code] = Pairing(displayName.trim(), teamId.trim(), memberId.trim(), clock())
        return WechatStartResponse(
            "started",
            pairingCode = code,
            authUrl = "$base?state=$code",
            expiresInSeconds = (PAIRING_TTL_MS / 1000).toInt(),
        )
    }

    /**
     * 自托管授权页跳转微信 OAuth 的地址（snsapi_base 静默授权）。
     * state = 配对码；redirect_uri 回到本服务 /v1/auth/wechat/callback。配置不齐或 state 空返回 null。
     */
    fun wechatAuthorizeUrl(pairingCode: String): String? {
        val state = pairingCode.trim()
        if (state.isEmpty() || wechatAppId.isNullOrBlank() || publicBaseUrl.isNullOrBlank()) return null
        val redirectUri = java.net.URLEncoder.encode(
            "${publicBaseUrl.trimEnd('/')}/v1/auth/wechat/callback",
            Charsets.UTF_8.name(),
        )
        return "https://open.weixin.qq.com/connect/oauth2/authorize" +
            "?appid=${wechatAppId}" +
            "&redirect_uri=$redirectUri" +
            "&response_type=code&scope=snsapi_base&state=$state#wechat_redirect"
    }

    /** OAuth code → 微信身份（自托管回调用）；未配置 exchanger 或换失败返回 null。 */
    suspend fun exchangeWechatCode(code: String): WechatIdentity? {
        val exchanger = wechatCodeExchanger ?: return null
        if (code.isBlank()) return null
        return runCatching { exchanger.exchange(code.trim()) }.getOrNull()
    }

    /**
     * 微信授权 H5 授权成功后回提交；admin token 校验在路由层。
     * 自托管回调（/v1/auth/wechat/callback）只传前三参，displayName/teamId/memberId 由 start 时的配对携带。
     */
    fun completeWechatPairing(
        pairingCode: String,
        unionId: String,
        openId: String,
        displayName: String = "",
        teamId: String = "",
        memberId: String = "",
    ): LoginResponse {
        val pairing = pairing(pairingCode) ?: return LoginResponse("not_found", "配对码不存在或已过期")
        val union = unionId.trim()
        if (union.isEmpty()) return LoginResponse("invalid", "unionId 不能为空")
        if (pairing.result != null) return pairing.result!! // 幂等
        val response = loginOrRegister(
            phone = "",
            wechat = WechatIdentity(union, openId.trim()),
            displayName = displayName.ifBlank { pairing.displayName },
            teamId = teamId.ifBlank { pairing.teamId },
            memberId = memberId.ifBlank { pairing.memberId },
        )
        pairing.result = response
        return response
    }

    fun pollWechatPairing(pairingCode: String): LoginResponse {
        val pairing = pairing(pairingCode) ?: return LoginResponse("not_found", "配对码不存在或已过期")
        return pairing.result ?: LoginResponse("pending", "等待微信侧完成授权")
    }

    fun loginWithWechatProfile(identity: WechatIdentity): LoginResponse = loginOrRegister(
        phone = "",
        wechat = identity,
        displayName = identity.nickname,
        teamId = "",
        memberId = "",
    )

    // ── token → 既有团队端点鉴权视图 ─────────────────────────────────────────

    fun authenticate(token: String): AuthAccount? = store.accountByToken(token)

    /**
     * 账号 token → team-scoped AccountBinding 视图（与 accounts.json 预置绑定同构）。
     * teamId 为 null 时取该账号首个成员位（/v1/me）；找不到成员位（未入队）返回 null。
     */
    fun resolveBinding(token: String, teamId: String?): AccountBinding? {
        val account = authenticate(token) ?: return null
        val memberships = store.membershipsOf(account.accountId)
        val membership = teamId?.let { want -> memberships.firstOrNull { it.teamId == want } }
            ?: memberships.firstOrNull()
            ?: return null
        return AccountBinding(
            accountToken = token,
            teamId = membership.teamId,
            memberId = membership.memberId,
            memberName = membership.memberName,
            openId = membership.feishuOpenId,
            isCaptain = membership.isCaptain,
        )
    }

    fun accountMe(account: AuthAccount): AccountMeResponse = AccountMeResponse(
        accountId = account.accountId,
        phoneMasked = account.phone.takeIf { it.isNotEmpty() }
            ?.let { it.take(3) + "****" + it.takeLast(4) }
            .orEmpty(),
        wechatBound = account.wechatUnionId.isNotEmpty(),
        displayName = account.displayName,
        memberships = store.membershipsOf(account.accountId),
        avatarUrl = account.avatarUrl,
    )

    /** 登出：吊销调用方自己的 token（多设备并存，互不影响）。 */
    fun logout(token: String): Boolean = store.revokeToken(token)

    /** 管理端账号清单（X-Auth-Admin-Token 保护；手机号脱敏后输出）。 */
    fun accountsAdmin(): List<AdminAccountView> {
        val (accounts, memberships) = store.accountsSnapshot()
        return accounts.map { account ->
            AdminAccountView(
                accountId = account.accountId,
                phoneMasked = account.phone.takeIf { it.isNotEmpty() }
                    ?.let { it.take(3) + "****" + it.takeLast(4) }
                    .orEmpty(),
                wechatBound = account.wechatUnionId.isNotEmpty(),
                displayName = account.displayName,
                createdAtMs = account.createdAtMs,
                memberships = memberships.filter { it.accountId == account.accountId },
            )
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    @Synchronized
    private fun loginOrRegister(
        phone: String,
        wechat: WechatIdentity?,
        displayName: String,
        teamId: String,
        memberId: String,
    ): LoginResponse {
        var created = false
        var account = when {
            phone.isNotEmpty() -> store.accountByPhone(phone)
            wechat != null -> store.accountByWechat(wechat.unionId)
            else -> null
        }
        if (account == null) {
            account = store.insertAccount(
                AuthAccount(
                    accountId = "acc-" + newHex(4),
                    phone = phone,
                    wechatUnionId = wechat?.unionId.orEmpty(),
                    wechatOpenId = wechat?.openId.orEmpty(),
                    displayName = displayName.ifBlank { DEFAULT_DISPLAY_NAME },
                    createdAtMs = clock(),
                    avatarUrl = wechat?.avatarUrl.orEmpty(),
                ),
            )
            created = true
        }
        val current = requireNotNull(account)
        if (!created) {
            // 后绑补齐：手机号登录的账号补微信标识（反之亦然），displayName 覆盖以最新输入
            val patched = current.copy(
                phone = current.phone.ifEmpty { phone },
                wechatUnionId = current.wechatUnionId.ifEmpty { wechat?.unionId.orEmpty() },
                wechatOpenId = current.wechatOpenId.ifEmpty { wechat?.openId.orEmpty() },
                displayName = displayName.ifBlank { current.displayName },
                avatarUrl = wechat?.avatarUrl.orEmpty().ifBlank { current.avatarUrl },
            )
            if (patched != current) {
                store.updateAccount(patched)
                return loginOrRegisterResume(patched, created, displayName, teamId, memberId)
            }
        }
        return loginOrRegisterResume(current, created, displayName, teamId, memberId)
    }

    private fun loginOrRegisterResume(
        account: AuthAccount,
        created: Boolean,
        displayName: String,
        teamId: String,
        memberId: String,
    ): LoginResponse {
        var memberships = store.membershipsOf(account.accountId)
        if (teamId.isNotEmpty() && memberId.isNotEmpty() && memberships.none { it.teamId == teamId }) {
            val claimed = store.claimMembership(
                AuthMembership(
                    accountId = account.accountId,
                    teamId = teamId,
                    memberId = memberId,
                    memberName = displayName.ifBlank { account.displayName },
                ),
            ) ?: return LoginResponse("slot_taken", "该成员位已被其他账号认领")
            memberships += claimed
        }

        val token = tokenGen()
        store.issueToken(AuthTokenEntry(token = token, accountId = account.accountId, createdAtMs = clock()))
        return LoginResponse(
            "ok",
            token = token,
            accountId = account.accountId,
            displayName = account.displayName,
            created = created,
            memberships = memberships,
            avatarUrl = account.avatarUrl,
        )
    }

    private fun pairing(code: String): Pairing? {
        val pairing = pairings[code.trim()] ?: return null
        if (clock() - pairing.createdAtMs > PAIRING_TTL_MS) {
            pairings.remove(code.trim())
            return null
        }
        return pairing
    }

    companion object {
        /** 短信验证码有效期：5 分钟（T3 补齐常量，部署可后置为配置） */
        const val CODE_TTL_MS: Long = 5 * 60 * 1000
        /** 重发限流：60 秒 */
        const val RESEND_LIMIT_MS: Long = 60 * 1000
        /** 配对码有效期：10 分钟 */
        const val PAIRING_TTL_MS: Long = 10 * 60 * 1000

        private const val DEFAULT_DISPLAY_NAME = "新队员"

        fun normalizePhone(raw: String): String? {
            val cleaned = raw.trim().replace(" ", "").replace("-", "")
            return cleaned.takeIf { Regex("^1\\d{10}$").matches(it) }
        }

        fun newHex(bytes: Int): String = buildString {
            val random = SecureRandom()
            repeat(bytes) { append("%02x".format(random.nextInt(256))) }
        }
    }
}
