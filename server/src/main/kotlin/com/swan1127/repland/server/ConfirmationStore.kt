package com.swan1127.repland.server

import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Path

/**
 * 卡片确认台账（T4b，FEISHU_INTEGRATION_NOTES §4）：
 * 飞书卡片按钮回调 = 一次显式确认操作（等价 UI 确认，走既有草案→确认纪律），
 * 后端幂等记账后落盘；App 端经 GET /v1/confirmations 增量拉取并入本地主副本（§13 拉取起步）。
 *
 * T4a 同款起步方案：JSON 文件持久化（config/confirmations.json）；Postgres 后置。
 * path 为 null 时纯内存（单测用）。
 */
class ConfirmationStore(private val path: Path? = null) {

    private val records = ArrayList<ConfirmationRecord>()
    private val seenKeys = HashSet<String>()
    private var nextSeq = 1L

    init {
        path?.let(::loadFromDisk)
    }

    fun size(): Int = synchronized(this) { records.size }

    /**
     * 记账。幂等键 = kind + draftId + revision + 操作人（§4 待实操验证项）。
     * 重复确认返回 null（不产生新记录、不改 seq）。
     */
    fun record(
        teamId: String,
        kind: String,
        draftId: String,
        revision: Int,
        operatorMemberId: String,
        operatorName: String,
        operatorOpenId: String,
        decidedAt: String,
    ): ConfirmationRecord? = synchronized(this) {
        val key = idempotencyKey(kind, draftId, revision, operatorMemberId)
        if (key in seenKeys) return null
        val record = ConfirmationRecord(
            seq = nextSeq++,
            teamId = teamId,
            kind = kind,
            draftId = draftId,
            revision = revision,
            operatorMemberId = operatorMemberId,
            operatorName = operatorName,
            operatorOpenId = operatorOpenId,
            decidedAt = decidedAt,
        )
        records += record
        seenKeys += key
        persist()
        record
    }

    /** 增量拉取：该队伍 seq > after 的记录，seq 升序（游标语义见 ConfirmationsResponse）。 */
    fun since(teamId: String, after: Long): List<ConfirmationRecord> = synchronized(this) {
        records.filter { it.teamId == teamId && it.seq > after }
    }

    private fun loadFromDisk(path: Path) {
        val file = path.toFile()
        if (!file.exists()) return
        val raw = file.readText()
        if (raw.isBlank()) return
        val loaded = kotlinx.serialization.json.Json.decodeFromString(
            ListSerializer(ConfirmationRecord.serializer()),
            raw,
        )
        records += loaded
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1
        loaded.forEach { seenKeys += idempotencyKey(it.kind, it.draftId, it.revision, it.operatorMemberId) }
    }

    private fun persist() {
        val target = path ?: return
        runCatching {
            target.toFile().parentFile?.mkdirs()
            target.toFile().writeText(
                kotlinx.serialization.json.Json.encodeToString(
                    ListSerializer(ConfirmationRecord.serializer()),
                    records,
                ),
            )
        }
    }

    private fun idempotencyKey(kind: String, draftId: String, revision: Int, operatorMemberId: String) =
        "$kind|$draftId|$revision|$operatorMemberId"
}
