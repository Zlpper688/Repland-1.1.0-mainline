package com.swan1127.repland.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

// ── 配置（全部环境变量，BYOK：凭证永不进仓库） ──────────────────────────────

data class ServerConfig(
    val port: Int,
    /** 监听网卡：公网部署应设 127.0.0.1，由 nginx 反代出 443（REPLAND_SERVER_HOST） */
    val host: String,
    val feishuAppId: String?,
    val feishuAppSecret: String?,
    val feishuApiBase: String,
    val accountsFile: String,
    val confirmationsFile: String,
    /** T4c：多维表格副本（§5）。缺一即降级不启用（BYOK：凭证永不进仓库） */
    val bitableAppToken: String?,
    val bitableTableId: String?,
    /** T3 补齐：账号库（登录身份，区别于 accounts.json 的投递绑定） */
    val authDbFile: String,
    /** 短信网关 webhook（POST {"phone","code"}）；未配置 → 日志出码开发模式 */
    val smsWebhookUrl: String?,
    /** 验证码回显：显式 1/0 控制，默认 = 未配置网关（开发模式必须出码） */
    val smsDevMode: Boolean,
    /** 内测手机号名单（REPLAND_SMS_ALLOWED_PHONES，逗号分隔）；空 = 不限制 */
    val smsAllowedPhones: Set<String>,
    /** 微信授权 H5 地址；未配置 → 微信通道禁用（或凭自托管条件启用，见下） */
    val wechatAuthUrl: String?,
    /** T3 补齐：自托管微信授权页（公众号网页授权 snsapi_base）；三者齐备 → /v1/auth/wechat/page 可用 */
    val wechatAppId: String?,
    val wechatAppSecret: String?,
    val wechatApiBase: String,
    /** 服务端公网地址（自托管授权页入口；含协议，如 https://repland.example.com） */
    val publicBaseUrl: String?,
    /** mem0 云记忆同步（§12 可选远端层）；未配置 → 记忆端点降级 disabled */
    val mem0ApiKey: String?,
    val mem0ApiBase: String,
    /** 旧版微信 complete 提交口令（H5 → 后端）；未配置时拒绝。 */
    val authAdminToken: String?,
    val wechatQrAppId: String? = null,
    val wechatQrAppSecret: String? = null,
    val wechatQrCallbackUrl: String? = null,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig {
            val smsWebhookUrl = env("REPLAND_SMS_WEBHOOK_URL")?.takeIf { it.isNotBlank() }
            val smsDevMode = when (env("REPLAND_SMS_DEV_MODE")) {
                "1" -> true
                "0" -> false
                else -> smsWebhookUrl == null
            }
            // 名单外的手机号连码都拿不到：公网无短信网关时，dev 出码只对内测号码开放
            val smsAllowedPhones = env("REPLAND_SMS_ALLOWED_PHONES")
                ?.split(',', ';', ' ', '\n')
                ?.mapNotNull { AuthService.normalizePhone(it) }
                ?.toSet()
                ?: emptySet()
            return ServerConfig(
                port = env("REPLAND_SERVER_PORT")?.toIntOrNull() ?: 8080,
                host = env("REPLAND_SERVER_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
                feishuAppId = env("FEISHU_APP_ID")?.takeIf { it.isNotBlank() },
                feishuAppSecret = env("FEISHU_APP_SECRET")?.takeIf { it.isNotBlank() },
                feishuApiBase = env("FEISHU_API_BASE")?.takeIf { it.isNotBlank() } ?: "https://open.feishu.cn",
                accountsFile = env("REPLAND_ACCOUNTS_FILE")?.takeIf { it.isNotBlank() } ?: "config/accounts.json",
                confirmationsFile = env("REPLAND_CONFIRMATIONS_FILE")?.takeIf { it.isNotBlank() }
                    ?: "config/confirmations.json",
                bitableAppToken = env("REPLAND_BITABLE_APP_TOKEN")?.takeIf { it.isNotBlank() },
                bitableTableId = env("REPLAND_BITABLE_TABLE_ID")?.takeIf { it.isNotBlank() },
                authDbFile = env("REPLAND_AUTH_DB_FILE")?.takeIf { it.isNotBlank() } ?: "config/auth-db.json",
                smsWebhookUrl = smsWebhookUrl,
                smsDevMode = smsDevMode,
                smsAllowedPhones = smsAllowedPhones,
                wechatAuthUrl = env("REPLAND_WECHAT_AUTH_URL")?.takeIf { it.isNotBlank() },
                wechatAppId = env("WECHAT_APP_ID")?.takeIf { it.isNotBlank() },
                wechatAppSecret = env("WECHAT_APP_SECRET")?.takeIf { it.isNotBlank() },
                wechatApiBase = env("WECHAT_API_BASE")?.takeIf { it.isNotBlank() } ?: "https://api.weixin.qq.com",
                publicBaseUrl = env("REPLAND_PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() },
                mem0ApiKey = env("MEM0_API_KEY")?.takeIf { it.isNotBlank() },
                mem0ApiBase = env("MEM0_API_BASE")?.takeIf { it.isNotBlank() } ?: "https://api.mem0.ai",
                authAdminToken = env("REPLAND_AUTH_ADMIN_TOKEN")?.takeIf { it.isNotBlank() },
                wechatQrAppId = env("REPLAND_WECHAT_QR_APP_ID")?.trim()?.takeIf(String::isNotEmpty),
                wechatQrAppSecret = env("REPLAND_WECHAT_QR_APP_SECRET")?.trim()?.takeIf(String::isNotEmpty),
                wechatQrCallbackUrl = env("REPLAND_WECHAT_QR_CALLBACK_URL")?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }
}

// ── 路由装配（提取成函数便于 testApplication 覆盖） ─────────────────────────

fun Application.replandModule(
    registry: AccountRegistry,
    notifyService: NotifyService?,
    feishuConfigured: Boolean,
    confirmations: ConfirmationStore? = null,
    ledgerCopy: LedgerCopyService? = null,
    meetings: MeetingCalendarService? = null,
    authService: AuthService? = null,
    authAdminToken: String? = null,
    mem0Gateway: Mem0Gateway? = null,
    teamStates: TeamStateStore? = null,
    wechatQrLogin: WechatQrLogin? = null,
) {
    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }

    /**
     * 统一鉴权（T3 补齐）：accounts.json 预置 token 优先（零回归），其次账号库登录 token。
     * 账号 token 需要队伍上下文（body/query 的 teamId；缺省取该账号首个成员位）。
     */
    fun ApplicationCall.resolveRequester(teamId: String?): AccountBinding? {
        val token = request.bearerToken()
        registry.authenticate(token)?.let { return it }
        val service = authService ?: return null
        return service.resolveBinding(token, teamId)
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(feishuConfigured = feishuConfigured, boundAccounts = registry.size))
        }

        // T4b：配置页「测试连接」——校验账号 token 并回显绑定状态（登录 token 可带 ?teamId=）
        get("/v1/me") {
            val requester = call.resolveRequester(call.request.queryParameters["teamId"]) ?: run {
                call.respond(HttpStatusCode.Unauthorized, NotifyResponse("unauthorized", "账号 token 无效"))
                return@get
            }
            call.respond(
                MeResponse(
                    teamId = requester.teamId,
                    memberId = requester.memberId,
                    memberName = requester.memberName,
                    isCaptain = requester.isCaptain,
                    feishuBound = requester.openId.isNotBlank(),
                ),
            )
        }

        // T3 补齐：账号级信息（登录 token；含全部队伍成员位）
        get("/v1/auth/account-me") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginResponse("disabled", "账号服务未启用"))
                return@get
            }
            val account = service.authenticate(call.request.bearerToken()) ?: run {
                call.respond(HttpStatusCode.Unauthorized, LoginResponse("unauthorized", "账号 token 无效"))
                return@get
            }
            call.respond(service.accountMe(account))
        }

        // 账号管理：登出（吊销调用方自己的 token；多设备并存互不影响）
        post("/v1/auth/logout") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LogoutResponse("disabled", "账号服务未启用"))
                return@post
            }
            val body = runCatching { call.receive<LogoutRequest>() }.getOrDefault(LogoutRequest())
            val token = body.token.ifBlank { call.request.bearerToken() }
            if (token.isBlank()) {
                call.respond(LogoutResponse("invalid", "缺少 token"))
                return@post
            }
            val ok = service.logout(token)
            call.respond(LogoutResponse(if (ok) "ok" else "not_found", if (ok) "已退出登录" else "token 不存在或已失效"))
        }

        // 账号管理：管理端账号清单（X-Auth-Admin-Token 保护；手机号脱敏）
        get("/v1/auth/accounts") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, AdminAccountsResponse("disabled"))
                return@get
            }
            if (authAdminToken.isNullOrBlank() || call.request.headers["X-Auth-Admin-Token"] != authAdminToken) {
                call.respond(HttpStatusCode.Forbidden, AdminAccountsResponse("forbidden", "需要管理端 token"))
                return@get
            }
            call.respond(AdminAccountsResponse("ok", accounts = service.accountsAdmin()))
        }

        // §12 mem0 云记忆同步（可选远端层；本地 JSON 仍是主副本，离线优先不变）：
        // 账号 token 鉴权，mem0 user_id = accountId；画像要点/参赛档案按 kind 元数据区分
        val memorySyncLimiter = RateLimiter(MEMORY_SYNC_RATE_MS)
        val memorySearchLimiter = RateLimiter(MEMORY_SEARCH_RATE_MS)
        post("/v1/memory/sync") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, MemorySyncResponse("disabled", "账号服务未启用"))
                return@post
            }
            val gateway = mem0Gateway ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, MemorySyncResponse("disabled", "记忆云同步未启用（服务端未配置 MEM0_API_KEY）"))
                return@post
            }
            val account = service.authenticate(call.request.bearerToken()) ?: run {
                call.respond(HttpStatusCode.Unauthorized, MemorySyncResponse("unauthorized", "账号 token 无效"))
                return@post
            }
            val body = call.receive<MemorySyncRequest>()
            val items = body.facts.map { it.trim() }.filter { it.isNotEmpty() }.map { it to "profile" } +
                body.archives.map { it.trim() }.filter { it.isNotEmpty() }.map { it to "archive" }
            if (items.isEmpty()) {
                call.respond(MemorySyncResponse("invalid", "没有可同步的记忆（画像要点 / 参赛档案均为空）"))
                return@post
            }
            // 上限防护：mem0 按量计费（LLM 抽取），防单次大批量与超长文本
            if (items.size > MAX_MEMORY_ITEMS) {
                call.respond(MemorySyncResponse("invalid", "单次最多同步 $MAX_MEMORY_ITEMS 条（当前 ${items.size}），请分批同步"))
                return@post
            }
            val oversized = items.firstOrNull { it.first.length > MAX_MEMORY_ITEM_CHARS }
            if (oversized != null) {
                call.respond(MemorySyncResponse("invalid", "单条内容不能超过 $MAX_MEMORY_ITEM_CHARS 字符，请精简后重试"))
                return@post
            }
            if (!memorySyncLimiter.tryAcquire(account.accountId)) {
                call.respond(HttpStatusCode.TooManyRequests, MemorySyncResponse("rate_limited", "同步太频繁（每分钟一次），请稍后再试"))
                return@post
            }
            var accepted = 0
            var skipped = 0
            items.forEach { (text, kind) -> if (gateway.add(account.accountId, text, kind)) accepted++ else skipped++ }
            call.respond(MemorySyncResponse("ok", "云端解析中，稍后可搜索", accepted = accepted, skipped = skipped))
        }

        post("/v1/memory/search") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, MemorySearchResponse("disabled", "账号服务未启用"))
                return@post
            }
            val gateway = mem0Gateway ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, MemorySearchResponse("disabled", "记忆云同步未启用（服务端未配置 MEM0_API_KEY）"))
                return@post
            }
            val account = service.authenticate(call.request.bearerToken()) ?: run {
                call.respond(HttpStatusCode.Unauthorized, MemorySearchResponse("unauthorized", "账号 token 无效"))
                return@post
            }
            val body = call.receive<MemorySearchRequest>()
            if (body.query.isBlank()) {
                call.respond(MemorySearchResponse("invalid", "请输入搜索词"))
                return@post
            }
            if (!memorySearchLimiter.tryAcquire(account.accountId)) {
                call.respond(HttpStatusCode.TooManyRequests, MemorySearchResponse("rate_limited", "搜索太频繁，请稍后再试"))
                return@post
            }
            call.respond(MemorySearchResponse("ok", memories = gateway.search(account.accountId, body.query.trim())))
        }

        // T3 补齐：手机号验证码登录（§13 双通道之一；dev 模式响应回显 devCode）
        post("/v1/auth/sms/request") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, SmsRequestResponse("disabled", "账号服务未启用"))
                return@post
            }
            val body = call.receive<SmsRequestRequest>()
            call.respond(service.requestSmsCode(body.phone))
        }

        post("/v1/auth/sms/verify") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginResponse("disabled", "账号服务未启用"))
                return@post
            }
            val body = call.receive<SmsVerifyRequest>()
            call.respond(
                service.verifySmsCode(body.phone, body.code, body.displayName, body.teamId, body.memberId),
            )
        }

        post("/v1/auth/wechat/qr/start") {
            call.response.headers.append("Cache-Control", "no-store")
            val response = wechatQrLogin?.start()
            if (response == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, WechatQrStartResponse("disabled", "微信扫码登录暂未开通"))
            } else {
                call.respond(response)
            }
        }

        post("/v1/auth/wechat/qr/poll") {
            call.response.headers.append("Cache-Control", "no-store")
            val login = wechatQrLogin
            if (login == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginResponse("disabled", "微信扫码登录暂未开通"))
            } else {
                call.respond(login.poll(call.receive<WechatQrPollRequest>().pollToken))
            }
        }

        get("/v1/auth/wechat/qr/callback") {
            call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            val state = call.request.queryParameters["state"].orEmpty()
            val code = call.request.queryParameters["code"].orEmpty()
            if (wechatQrLogin?.complete(state, code) == true) {
                call.respondHtmlPage("授权成功", "Repland 将自动登录，可以关闭此页面。")
            } else {
                call.respondHtmlPage("授权未完成", "请回到 Repland 刷新二维码后重试。")
            }
        }

        // 旧版本 App 的微信配对接口。
        post("/v1/auth/wechat/start") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, WechatStartResponse("disabled", "账号服务未启用"))
                return@post
            }
            val body = call.receive<WechatStartRequest>()
            call.respond(service.startWechatPairing(body.displayName, body.teamId, body.memberId))
        }

        post("/v1/auth/wechat/complete") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginResponse("disabled", "账号服务未启用"))
                return@post
            }
            if (authAdminToken == null || call.request.headers["X-Auth-Admin-Token"] != authAdminToken) {
                call.respond(HttpStatusCode.Unauthorized, LoginResponse("unauthorized", "需要管理员令牌（X-Auth-Admin-Token）"))
                return@post
            }
            val body = call.receive<WechatCompleteRequest>()
            call.respond(
                service.completeWechatPairing(
                    body.pairingCode, body.unionId, body.openId, body.displayName, body.teamId, body.memberId,
                ),
            )
        }

        post("/v1/auth/wechat/poll") {
            val service = authService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LoginResponse("disabled", "账号服务未启用"))
                return@post
            }
            val body = call.receive<WechatPollRequest>()
            call.respond(service.pollWechatPairing(body.pairingCode))
        }

        // T3 补齐：自托管微信授权页（部署零外部前端资产）：
        // App start → 本页跳微信 OAuth（snsapi_base）→ 微信回调本服务 → 换 unionid/openid → 自动 complete → App 轮询
        get("/v1/auth/wechat/page") {
            val state = call.request.queryParameters["state"].orEmpty().trim()
            val authorizeUrl = authService?.wechatAuthorizeUrl(state)
            if (authorizeUrl == null) {
                call.respondHtmlPage("无法授权", "链接无效或微信登录未启用，请在 Repland 内重新发起微信登录。")
                return@get
            }
            call.respondRedirect(authorizeUrl)
        }

        get("/v1/auth/wechat/callback") {
            val service = authService ?: run {
                call.respondHtmlPage("无法授权", "账号服务未启用，请联系管理员。")
                return@get
            }
            val state = call.request.queryParameters["state"].orEmpty().trim()
            val code = call.request.queryParameters["code"].orEmpty().trim()
            if (state.isEmpty() || code.isEmpty()) {
                call.respondHtmlPage("授权未完成", "微信回调缺少授权参数，请在 Repland 内重新发起微信登录。")
                return@get
            }
            val identity = service.exchangeWechatCode(code)
            val login = identity?.let { service.completeWechatPairing(state, it.unionId, it.openId) }
            if (login?.status == "ok") {
                call.respondHtmlPage("授权成功", "授权已完成，请回到 Repland 点击「我已授权，取回 token」。本页面可以关闭。")
            } else {
                call.respondHtmlPage("授权失败", login?.detail ?: "微信授权未成功，请在 Repland 内重新发起微信登录。")
            }
        }

        // T4b：卡片确认增量拉取（§13 推送通知 + 拉取起步；App 并入本地主副本）
        get("/v1/confirmations") {
            val requester = call.resolveRequester(call.request.queryParameters["teamId"]) ?: run {
                call.respond(HttpStatusCode.Unauthorized, NotifyResponse("unauthorized", "账号 token 无效"))
                return@get
            }
            val store = confirmations ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, NotifyResponse("unavailable", "确认台账未启用"))
                return@get
            }
            val after = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val items = store.since(requester.teamId, after).takeLast(MAX_CONFIRMATIONS_PER_PULL)
            call.respond(ConfirmationsResponse(items = items, cursor = items.maxOfOrNull { it.seq } ?: after))
        }

        // §13 表级同步第一块：队伍会话快照双向同步（本地 Room 仍是主副本；revision 守卫，冲突返回服务端当前版）
        put("/v1/teams/{teamId}/state") {
            val teamId = call.parameters["teamId"].orEmpty().trim()
            val requester = call.resolveRequester(teamId) ?: run {
                call.respond(HttpStatusCode.Unauthorized, TeamStateResponse("unauthorized", "账号 token 无效"))
                return@put
            }
            if (teamId != requester.teamId) {
                call.respond(HttpStatusCode.Forbidden, TeamStateResponse("forbidden", "账号与队伍不匹配"))
                return@put
            }
            val store = teamStates ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, TeamStateResponse("unavailable", "团队状态同步未启用"))
                return@put
            }
            val body = call.receive<TeamStatePutRequest>()
            if (body.payload.isBlank()) {
                call.respond(TeamStateResponse("invalid", "快照内容为空"))
                return@put
            }
            when (val result = store.put(teamId, body.baseRevision, body.payload, body.updatedByMember.ifBlank { requester.memberName })) {
                is TeamStateStore.PutResult.Accepted -> call.respond(
                    TeamStateResponse(
                        "ok",
                        revision = result.entry.revision,
                        updatedAtEpochMillis = result.entry.updatedAtEpochMillis,
                        updatedByMember = result.entry.updatedByMember,
                    ),
                )
                is TeamStateStore.PutResult.Conflict -> call.respond(
                    HttpStatusCode.Conflict,
                    TeamStateResponse(
                        "conflict",
                        "服务器已有更新的版本，请先拉取",
                        revision = result.current.revision,
                        updatedAtEpochMillis = result.current.updatedAtEpochMillis,
                        updatedByMember = result.current.updatedByMember,
                        payload = result.current.payload,
                    ),
                )
            }
        }

        get("/v1/teams/{teamId}/state") {
            val teamId = call.parameters["teamId"].orEmpty().trim()
            val requester = call.resolveRequester(teamId) ?: run {
                call.respond(HttpStatusCode.Unauthorized, TeamStateResponse("unauthorized", "账号 token 无效"))
                return@get
            }
            if (teamId != requester.teamId) {
                call.respond(HttpStatusCode.Forbidden, TeamStateResponse("forbidden", "账号与队伍不匹配"))
                return@get
            }
            val store = teamStates ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, TeamStateResponse("unavailable", "团队状态同步未启用"))
                return@get
            }
            val entry = store.current(teamId) ?: run {
                call.respond(TeamStateResponse("not_found", "该队伍还没有云端快照"))
                return@get
            }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            if (entry.revision <= since) {
                call.respond(TeamStateResponse("current", revision = entry.revision))
                return@get
            }
            call.respond(
                TeamStateResponse(
                    "ok",
                    revision = entry.revision,
                    updatedAtEpochMillis = entry.updatedAtEpochMillis,
                    updatedByMember = entry.updatedByMember,
                    payload = entry.payload,
                ),
            )
        }

        post("/v1/notify") {
            val body = call.receive<NotifyRequest>()
            val requester = call.resolveRequester(body.teamId) ?: run {
                call.respond(HttpStatusCode.Unauthorized, NotifyResponse("unauthorized", "账号 token 无效"))
                return@post
            }
            if (body.teamId != requester.teamId) {
                call.respond(HttpStatusCode.Forbidden, NotifyResponse("forbidden", "账号与队伍不匹配"))
                return@post
            }
            val service = notifyService
            if (service == null) {
                call.respond(NotifyResponse("degraded", "推送服务未启用", feishuDisabled = true))
                return@post
            }
            val result = service.dispatch(body, requester)
            call.respond(NotifyResponse(status = result.name, detail = "", feishuDisabled = false))
        }

        // T4c：台账条目副本（§5 只做副本不做同步；异步写多维表格，响应只表示入队结果）
        post("/v1/ledger") {
            val body = call.receive<LedgerCopyRequest>()
            val requester = call.resolveRequester(body.teamId) ?: run {
                call.respond(HttpStatusCode.Unauthorized, NotifyResponse("unauthorized", "账号 token 无效"))
                return@post
            }
            if (body.teamId != requester.teamId) {
                call.respond(HttpStatusCode.Forbidden, NotifyResponse("forbidden", "账号与队伍不匹配"))
                return@post
            }
            val service = ledgerCopy ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, LedgerCopyResponse("disabled", "多维表格副本未启用"))
                return@post
            }
            call.respond(LedgerCopyResponse(status = service.offer(body).name))
        }

        // T4c：会议写飞书日历（§6 队长确认 → 队长主日历建日程；幂等键 teamId+时间窗）
        post("/v1/meetings") {
            val body = call.receive<MeetingCreateRequest>()
            val requester = call.resolveRequester(body.teamId) ?: run {
                call.respond(HttpStatusCode.Unauthorized, NotifyResponse("unauthorized", "账号 token 无效"))
                return@post
            }
            if (body.teamId != requester.teamId) {
                call.respond(HttpStatusCode.Forbidden, NotifyResponse("forbidden", "账号与队伍不匹配"))
                return@post
            }
            val service = meetings ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, MeetingCreateResponse("disabled", "日历写入未启用"))
                return@post
            }
            val (result, eventId) = service.create(body, requester)
            call.respond(MeetingCreateResponse(status = result.name, eventId = eventId))
        }
    }
}

private const val MAX_CONFIRMATIONS_PER_PULL = 200

// mem0 云同步防护（mem0 按量计费：限流与上限防成本滥用）
private const val MAX_MEMORY_ITEMS = 100
private const val MAX_MEMORY_ITEM_CHARS = 8000
private const val MEMORY_SYNC_RATE_MS = 60_000L
private const val MEMORY_SEARCH_RATE_MS = 10_000L

/** 账号级固定窗口限流（内存版；宽松语义，竞态下可能多放行 1-2 次，可接受）。 */
private class RateLimiter(private val windowMs: Long) {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** true = 放行（窗口外首次调用） */
    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = last[key]
        if (prev != null && now - prev < windowMs) return false
        last[key] = now
        return true
    }
}

/** 自托管微信授权页的结果页（内联样式，微信内 WebView 直接渲染；服务端受控文案，无需转义）。 */
private suspend fun ApplicationCall.respondHtmlPage(title: String, message: String) {
    val html = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>
                body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
                       display: flex; align-items: center; justify-content: center;
                       min-height: 100vh; margin: 0; background: #f2f3f5; color: #1f2329; }
                .card { background: #fff; border-radius: 16px; padding: 32px 28px; max-width: 320px;
                        text-align: center; box-shadow: 0 4px 24px rgba(0, 0, 0, .08); }
                h1 { font-size: 20px; margin: 0 0 12px; }
                p { font-size: 15px; line-height: 1.6; color: #646a73; margin: 0; }
            </style>
        </head>
        <body>
            <div class="card"><h1>$title</h1><p>$message</p></div>
        </body>
        </html>
    """.trimIndent()
    respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
}

private fun io.ktor.server.request.ApplicationRequest.bearerToken(): String =
    headers["Authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()

// ── 入口 ────────────────────────────────────────────────────────────────

fun main() {
    val config = ServerConfig.fromEnv()
    val registry = AccountRegistry.load(Paths.get(config.accountsFile))
    val confirmations = ConfirmationStore(Paths.get(config.confirmationsFile))
    val http = HttpClient(CIO)

    // T3 补齐：账号登录（手机验证码 + 微信配对；短信网关/微信凭证全部服务端持有，§13）
    // 自托管微信授权页：公众号凭证 + 公网地址齐备 → OAuth code 换身份；REPLAND_WECHAT_AUTH_URL 可覆盖为外部 H5
    val authService = AuthService(
        store = AuthStore(Paths.get(config.authDbFile)),
        smsSender = config.smsWebhookUrl?.let { WebhookSmsSender(http, it) },
        smsDevMode = config.smsDevMode,
        allowedPhones = config.smsAllowedPhones,
        wechatAuthUrl = config.wechatAuthUrl,
        wechatAppId = config.wechatAppId,
        wechatAppSecret = config.wechatAppSecret,
        wechatApiBase = config.wechatApiBase,
        publicBaseUrl = config.publicBaseUrl,
        wechatCodeExchanger = config.wechatAppId?.takeIf { config.wechatAppSecret != null }
            ?.let { WechatHttpClient(http, it, config.wechatAppSecret!!, config.wechatApiBase) },
    )
    val wechatQrAppId = config.wechatQrAppId
    val wechatQrAppSecret = config.wechatQrAppSecret
    val wechatQrCallbackUrl = config.wechatQrCallbackUrl
    val wechatQrLogin = if (wechatQrAppId != null && wechatQrAppSecret != null && wechatQrCallbackUrl != null) {
        WechatQrLogin(
            authService, wechatQrAppId, wechatQrCallbackUrl,
            WechatHttpClient(http, wechatQrAppId, wechatQrAppSecret, config.wechatApiBase, fetchProfile = true),
        )
    } else null

    if (config.smsDevMode && config.smsAllowedPhones.isEmpty()) {
        LoggerFactory.getLogger("repland-auth").warn(
            "验证码会回显给任意请求方（未配 REPLAND_SMS_ALLOWED_PHONES）——公网部署必须设内测名单",
        )
    }

    // §12 mem0 云记忆同步（可选远端层；MEM0_API_KEY 服务端持有，BYOK）
    val mem0Gateway = config.mem0ApiKey?.let { HttpMem0Client(http, it, config.mem0ApiBase) }

    // §13 表级同步第一块：队伍会话快照（config/teams/{teamId}.json，Postgres 后置）
    val teamStateStore = TeamStateStore(Paths.get(config.authDbFile).parent?.resolve("teams"))

    val feishuConfigured = config.feishuAppId != null && config.feishuAppSecret != null
    var notifyService: NotifyService? = null
    var ledgerCopy: LedgerCopyService? = null
    var meetingService: MeetingCalendarService? = null
    if (feishuConfigured) {
        val tokenManager = TokenManager(
            fetcher = TokenManager.defaultFetcher(http, config.feishuAppId!!, config.feishuAppSecret!!, config.feishuApiBase),
        )
        notifyService = NotifyService(
            registry = registry,
            feishu = FeishuClient(http, tokenManager, config.feishuApiBase),
        )
        // T4c：多维表格台账副本（§5；env 未配置 → 降级不启用，App 端响应 disabled）
        val bitableConfigured = config.bitableAppToken != null && config.bitableTableId != null
        if (bitableConfigured) {
            ledgerCopy = LedgerCopyService(
                FeishuBitableClient(http, tokenManager, config.feishuApiBase, config.bitableAppToken!!, config.bitableTableId!!),
            ).also { it.start(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        }
        // T4c：会议写日历（§6；日历 = 队长主日历，calendar_id 即 open_id）
        meetingService = MeetingCalendarService(registry, FeishuCalendarClient(http, tokenManager, config.feishuApiBase))
        // T4b：长连接事件订阅 + 卡片回调（凭证缺失/连接失败不阻断 HTTP 服务）
        runCatching {
            FeishuLongConnection(
                appId = config.feishuAppId!!,
                appSecret = config.feishuAppSecret!!,
                cardService = CardActionService(registry, confirmations),
            ).start()
        }.onFailure { failure ->
            LoggerFactory.getLogger("repland-server")
                .error("飞书长连接启动失败（HTTP 服务不受影响）：{}", failure.message)
        }
    }

    embeddedServer(Netty, host = config.host, port = config.port) {
        replandModule(
            registry, notifyService, feishuConfigured, confirmations, ledgerCopy, meetingService,
            authService, config.authAdminToken, mem0Gateway, teamStateStore, wechatQrLogin,
        )
    }.start(wait = true)
}
