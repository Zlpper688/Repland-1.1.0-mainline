package com.swan1127.repland.data.team

import android.content.Context
import android.content.SharedPreferences

/**
 * 自托管后端连接配置（T4a）。
 *
 * 后端就是官方服务器（DEFAULT_BASE_URL，VPS 常驻）：App 出厂即指向它，
 * 用户登录拿到 token 即配置完成——不再有「队长自建、手发 token」这条路径。
 * BYOK 边界（FEISHU_INTEGRATION_NOTES §7）：飞书 app_id/app_secret 只存在于后端环境，
 * App 侧仅持有本人账号 token。未配置 = 推送通道关闭（§3 降级语义）。
 */
class BackendConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("team-backend", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var accountToken: String
        get() = prefs.getString(KEY_ACCOUNT_TOKEN, null) ?: ""
        set(value) = prefs.edit().putString(KEY_ACCOUNT_TOKEN, value.trim()).apply()

    /** 已配置 = 推送通道可用；否则 App 全部走本地通知 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && accountToken.isNotBlank()

    /**
     * 反射加载 src/remote 的联网实现（§14 照搬 CompatibleAiAdvisor 模式）：
     * release 构建无该类 → 反射失败 → NoopNotifier（离线边界）。
     * 每次调用新建实例：配置面 UI 运行时改配置后立即生效。
     */
    fun buildNotifier(): TeamNotifier {
        if (!isConfigured) return NoopNotifier
        return runCatching {
            Class.forName("com.swan1127.repland.data.team.BackendNotifier")
                .getDeclaredConstructor(String::class.java, String::class.java)
                .newInstance(baseUrl, accountToken) as TeamNotifier
        }.getOrDefault(NoopNotifier)
    }

    /** 配置面「测试连接」探测（T4b）：同款反射边界；release → 明确提示无联网能力。 */
    fun buildProber(): BackendProber = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpBackendProber")
            .getDeclaredConstructor()
            .newInstance() as BackendProber
    }.getOrDefault(BackendProber { _, _ ->
        BackendProbeReport(ok = false, httpCode = 0, message = "此构建不含联网能力（release 离线边界）")
    })

    // ---------- T4c：确认拉取 / 台账副本 / 会议日历 三通道 ----------

    /** 确认记录拉取游标（§13 增量）：seq 单调，持久化避免重复消费。 */
    var confirmationCursor: Long
        get() = prefs.getLong(KEY_CONFIRMATION_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_CONFIRMATION_CURSOR, value).apply()

    fun buildConfirmationPuller(): ConfirmationPuller = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpConfirmationPuller")
            .getDeclaredConstructor()
            .newInstance() as ConfirmationPuller
    }.getOrDefault(ConfirmationPuller { _, _, since -> ConfirmationPullResult(emptyList(), since) })

    fun buildLedgerSink(): LedgerSink {
        if (!isConfigured) return LedgerSink { _, _, _, _ -> }
        return runCatching {
            Class.forName("com.swan1127.repland.data.team.HttpLedgerSink")
                .getDeclaredConstructor(String::class.java, String::class.java)
                .newInstance(baseUrl, accountToken) as LedgerSink
        }.getOrDefault(LedgerSink { _, _, _, _ -> })
    }

    fun buildMeetingPoster(): MeetingPoster {
        if (!isConfigured) return MeetingPoster { _, _, _, _, _, _, _ -> }
        return runCatching {
            Class.forName("com.swan1127.repland.data.team.HttpMeetingPoster")
                .getDeclaredConstructor(String::class.java, String::class.java)
                .newInstance(baseUrl, accountToken) as MeetingPoster
        }.getOrDefault(MeetingPoster { _, _, _, _, _, _, _ -> })
    }

    // ---------- T3 补齐：账号登录四通道（联网实现反射自 src/remote；release 降级为明确提示） ----------

    fun buildSmsCodeRequester(): SmsCodeRequester = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as SmsCodeRequester
    }.getOrDefault(SmsCodeRequester { _, _ ->
        SmsCodeResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildSmsVerifier(): SmsVerifier = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as SmsVerifier
    }.getOrDefault(SmsVerifier { _, _, _, _, _, _ ->
        AuthLoginResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildWechatQrStarter(): WechatQrStarter = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as WechatQrStarter
    }.getOrDefault(WechatQrStarter { _ ->
        WechatQrStartResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildWechatQrPoller(): WechatQrPoller = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as WechatQrPoller
    }.getOrDefault(WechatQrPoller { _, _ ->
        AuthLoginResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildWechatAvatarDownloader(): WechatAvatarDownloader? = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as WechatAvatarDownloader
    }.getOrNull()

    // ---------- §12 mem0 云记忆同步（可选远端层；本地 JSON 仍是主副本） ----------

    fun buildMemoryPusher(): MemoryPusher = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpMemoryClient")
            .getDeclaredConstructor()
            .newInstance() as MemoryPusher
    }.getOrDefault(MemoryPusher { _, _, _, _ ->
        MemorySyncResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildMemorySearcher(): MemorySearcher = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpMemoryClient")
            .getDeclaredConstructor()
            .newInstance() as MemorySearcher
    }.getOrDefault(MemorySearcher { _, _, _ ->
        MemorySearchResult(false, "此构建不含联网能力（release 离线边界）")
    })

    // ---------- §13 表级同步第一块：队伍会话快照推拉 ----------

    /** 已知的服务端 revision（每队一个；0 = 本地还没见过云端版本）。 */
    fun knownServerRevision(teamId: String): Long = prefs.getLong(KEY_STATE_REVISION_PREFIX + teamId, 0L)

    fun setKnownServerRevision(teamId: String, revision: Long) {
        prefs.edit().putLong(KEY_STATE_REVISION_PREFIX + teamId, revision).apply()
    }

    fun buildTeamStatePusher(): TeamStatePusher = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpTeamStatePusher")
            .getDeclaredConstructor()
            .newInstance() as TeamStatePusher
    }.getOrDefault(TeamStatePusher { _, _, _, _, _, _ ->
        TeamStatePushResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildTeamStatePuller(): TeamStatePuller = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpTeamStatePuller")
            .getDeclaredConstructor()
            .newInstance() as TeamStatePuller
    }.getOrDefault(TeamStatePuller { _, _, _, _ ->
        TeamStatePullResult(false, "此构建不含联网能力（release 离线边界）")
    })

    fun buildAccountLogout(): AccountLogout = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as AccountLogout
    }.getOrDefault(AccountLogout { _, _ -> false })

    fun buildAccountMeFetcher(): AccountMeFetcher = runCatching {
        Class.forName("com.swan1127.repland.data.team.HttpAuthClient")
            .getDeclaredConstructor()
            .newInstance() as AccountMeFetcher
    }.getOrDefault(AccountMeFetcher { _, _ -> null })

    // ---------- 进入门槛（先登录或明确离线；§13 双通道登录在 BackendSettingsScreen） ----------

    /** 用户在登录门选择「先离线使用」：本次安装不再弹门（登录/退出会翻转它）。 */
    var offlineMode: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()

    private companion object {
        /** 官方服务器（VPS 常驻）：App 出厂即指向，登录通道全部打这里。 */
        const val DEFAULT_BASE_URL = "https://ilovemxt.xyz/repland"
        const val KEY_BASE_URL = "backend_base_url"
        const val KEY_ACCOUNT_TOKEN = "backend_account_token"
        const val KEY_CONFIRMATION_CURSOR = "confirmation_cursor"
        const val KEY_STATE_REVISION_PREFIX = "team_state_revision_"
        const val KEY_OFFLINE_MODE = "offline_mode"
    }
}
