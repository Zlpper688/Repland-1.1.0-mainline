package com.swan1127.repland.server

import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * 账号库持久化（T3 补齐，§13/§17）：JSON 文件起步（config/auth-db.json），结构与未来 Postgres 表一一对应
 * （accounts / tokens / memberships 三张表）；path 为 null 时纯内存（单测用）。
 * 与 accounts.json（队长预置的 team-scoped 投递绑定）互不覆盖：前者管登录身份，后者管飞书投递。
 */
class AuthStore(private val path: Path? = null) {

    @Serializable
    data class Snapshot(
        val accounts: List<AuthAccount> = emptyList(),
        val tokens: List<AuthTokenEntry> = emptyList(),
        val memberships: List<AuthMembership> = emptyList(),
    )

    private var snapshot = Snapshot()

    init {
        path?.let(::loadFromDisk)
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    fun accountByPhone(phone: String): AuthAccount? = synchronized(this) {
        snapshot.accounts.firstOrNull { it.phone == phone }
    }

    fun accountByWechat(unionId: String): AuthAccount? = synchronized(this) {
        snapshot.accounts.firstOrNull { it.wechatUnionId == unionId }
    }

    fun accountById(accountId: String): AuthAccount? = synchronized(this) {
        snapshot.accounts.firstOrNull { it.accountId == accountId }
    }

    /** token → 账号（未吊销集合内精确匹配）。 */
    fun accountByToken(token: String): AuthAccount? = synchronized(this) {
        val entry = snapshot.tokens.firstOrNull { it.token == token } ?: return null
        snapshot.accounts.firstOrNull { it.accountId == entry.accountId }
    }

    fun membershipsOf(accountId: String): List<AuthMembership> = synchronized(this) {
        snapshot.memberships.filter { it.accountId == accountId }
    }

    /** 管理端全量清单（X-Auth-Admin-Token 保护；只读，不做脱敏——脱敏在 AuthService 层做）。 */
    fun accountsSnapshot(): Pair<List<AuthAccount>, List<AuthMembership>> = synchronized(this) {
        snapshot.accounts to snapshot.memberships
    }

    fun membershipBySlot(teamId: String, memberId: String): AuthMembership? = synchronized(this) {
        snapshot.memberships.firstOrNull { it.teamId == teamId && it.memberId == memberId }
    }

    // ── 写入（调用方在 AuthService 内串行化，这里仍按 ConfirmationStore 风格加锁） ──

    fun insertAccount(account: AuthAccount) = synchronized(this) {
        snapshot = snapshot.copy(accounts = snapshot.accounts + account)
        persist()
        account
    }

    fun updateAccount(account: AuthAccount): Boolean = synchronized(this) {
        if (snapshot.accounts.none { it.accountId == account.accountId }) return false
        snapshot = snapshot.copy(accounts = snapshot.accounts.map { if (it.accountId == account.accountId) account else it })
        persist()
        true
    }

    fun issueToken(entry: AuthTokenEntry) = synchronized(this) {
        snapshot = snapshot.copy(tokens = snapshot.tokens + entry)
        persist()
        entry
    }

    /** 登出：吊销单个 token（多设备并存，只吊销调用方自己的这个）。 */
    fun revokeToken(token: String): Boolean = synchronized(this) {
        val existed = snapshot.tokens.any { it.token == token }
        if (!existed) return false
        snapshot = snapshot.copy(tokens = snapshot.tokens.filterNot { it.token == token })
        persist()
        true
    }

    /** 认领成员位：槽位空闲才写入；被占返回 null（幂等：同账号同槽已存在则原样返回）。 */
    fun claimMembership(membership: AuthMembership): AuthMembership? = synchronized(this) {
        val existing = snapshot.memberships.firstOrNull {
            it.teamId == membership.teamId && it.memberId == membership.memberId
        }
        when {
            existing != null && existing.accountId == membership.accountId -> existing
            existing != null -> null
            else -> {
                snapshot = snapshot.copy(memberships = snapshot.memberships + membership)
                persist()
                membership
            }
        }
    }

    private fun loadFromDisk(path: Path) {
        val file = path.toFile()
        if (!file.exists()) return
        val raw = file.readText()
        if (raw.isBlank()) return
        runCatching {
            snapshot = kotlinx.serialization.json.Json.decodeFromString(Snapshot.serializer(), raw)
        }
    }

    private fun persist() {
        val target = path ?: return
        runCatching {
            target.toFile().parentFile?.mkdirs()
            target.toFile().writeText(
                kotlinx.serialization.json.Json { encodeDefaults = true }
                    .encodeToString(Snapshot.serializer(), snapshot),
            )
        }
    }
}
