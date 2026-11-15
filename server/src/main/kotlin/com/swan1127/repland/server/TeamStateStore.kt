package com.swan1127.repland.server

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 团队状态快照库（TEAM_COLLAB_DESIGN §13 表级同步的第一块）：
 * 按 teamId 存整份队伍会话快照（TeamStateCodec schema 1，与客户端 Room team_sessions payload 同构），
 * revision 单调递增；写入带 baseRevision 守卫——不匹配返回冲突并附服务端当前版本。
 * dir 为 null 时纯内存（单测用）；每队一个 JSON 文件（config/teams/{teamId}.json），Postgres 后置。
 */
class TeamStateStore(private val dir: Path? = null) {

    @Serializable
    data class Entry(
        val teamId: String,
        val revision: Long,
        val updatedAtEpochMillis: Long,
        val updatedByMember: String,
        val payload: String,
    )

    sealed class PutResult {
        data class Accepted(val entry: Entry) : PutResult()
        data class Conflict(val current: Entry) : PutResult()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        dir?.let(::loadFromDisk)
    }

    fun current(teamId: String): Entry? = entries[teamId]

    fun put(teamId: String, baseRevision: Long, payload: String, updatedByMember: String): PutResult = synchronized(this) {
        val current = entries[teamId]
        if (current != null && baseRevision != current.revision) return PutResult.Conflict(current)
        val entry = Entry(
            teamId = teamId,
            revision = (current?.revision ?: 0L) + 1L,
            updatedAtEpochMillis = System.currentTimeMillis(),
            updatedByMember = updatedByMember,
            payload = payload,
        )
        entries[teamId] = entry
        persist(entry)
        PutResult.Accepted(entry)
    }

    private fun loadFromDisk(dir: Path) {
        val folder = dir.toFile()
        if (!folder.isDirectory) return
        folder.listFiles { file -> file.extension == "json" }?.forEach { file ->
            runCatching {
                val entry = kotlinx.serialization.json.Json.decodeFromString(Entry.serializer(), file.readText())
                if (entry.teamId == file.nameWithoutExtension) entries[entry.teamId] = entry
            }
        }
    }

    private fun persist(entry: Entry) {
        val dir = dir ?: return
        runCatching {
            dir.toFile().mkdirs()
            dir.resolve("${entry.teamId}.json").toFile().writeText(
                kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Entry.serializer(), entry),
            )
        }
    }
}
