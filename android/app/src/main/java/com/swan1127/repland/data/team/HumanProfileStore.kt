package com.swan1127.repland.data.team

import android.content.Context
import com.swan1127.repland.domain.team.ArchiveEntry
import com.swan1127.repland.domain.team.HumanProfileMemory
import com.swan1127.repland.domain.team.ProfileFact
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人记忆域落盘（§12 T5）：独立 JSON 文件，不进队伍快照（队伍快照是团队域真相源）。
 * 跟随账号：当前为单账号本地副本，账号体系接入后按账号隔离命名。
 */
class HumanProfileStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "human-profile.json"))

    private val mutex = Mutex()

    suspend fun load(): HumanProfileMemory = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { HumanProfileCodec.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull() ?: HumanProfileMemory()
        }
    }

    suspend fun save(memory: HumanProfileMemory) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(HumanProfileCodec.toJson(memory), Charsets.UTF_8) }
        }
    }

    /** 数据管理一键清除个人数据时连带清除（个人数据范围）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }
}

/** 个人域编解码：画像要点 + 参赛档案（含最终台账，复用 TeamStateCodec 的台账序列化）。 */
object HumanProfileCodec {

    private val DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun toJson(memory: HumanProfileMemory): String = JSONObject().apply {
        put("schema", 1)
        put("facts", JSONArray().apply {
            memory.facts.forEach { fact ->
                put(JSONObject().apply {
                    put("factId", fact.factId)
                    put("content", fact.content)
                    put("at", fact.at.format(DATETIME))
                    fact.sourceTeamId?.let { put("sourceTeamId", it) }
                })
            }
        })
        put("archives", JSONArray().apply {
            memory.archives.forEach { archive ->
                put(JSONObject().apply {
                    put("archiveId", archive.archiveId)
                    put("teamId", archive.teamId)
                    put("teamName", archive.teamName)
                    archive.competitionName?.let { put("competitionName", it) }
                    put("result", archive.result)
                    put("archivedAt", archive.archivedAt.format(DATETIME))
                    put("archivedBy", archive.archivedBy)
                    put("stats", JSONObject().apply { archive.stats.forEach { (key, value) -> put(key, value) } })
                    put("finalLedger", JSONArray().apply { archive.finalLedger.forEach { put(TeamStateCodec.ledgerEntry(it)) } })
                    put("workLinks", JSONArray().apply { archive.workLinks.forEach { put(it) } })
                })
            }
        })
    }.toString()

    fun fromJson(text: String): HumanProfileMemory? = runCatching {
        val json = JSONObject(text)
        val memory = HumanProfileMemory()
        json.optJSONArray("facts")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                arr.optJSONObject(i)?.let { f ->
                    runCatching {
                        memory.facts += ProfileFact(
                            factId = f.getString("factId"),
                            content = f.getString("content"),
                            at = LocalDateTime.parse(f.getString("at"), DATETIME),
                            sourceTeamId = f.optString("sourceTeamId", null),
                        )
                    }
                }
            }
        }
        json.optJSONArray("archives")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                arr.optJSONObject(i)?.let { a ->
                    runCatching {
                        memory.archives += ArchiveEntry(
                            archiveId = a.getString("archiveId"),
                            teamId = a.getString("teamId"),
                            teamName = a.getString("teamName"),
                            competitionName = a.optString("competitionName", null),
                            result = a.getString("result"),
                            archivedAt = LocalDateTime.parse(a.getString("archivedAt"), DATETIME),
                            archivedBy = a.optString("archivedBy", ""),
                            stats = buildMap {
                                a.optJSONObject("stats")?.let { s -> s.keys().forEach { key -> put(key, s.optInt(key)) } }
                            },
                            finalLedger = a.optJSONArray("finalLedger")?.let { l ->
                                (0 until l.length()).mapNotNull { j -> l.optJSONObject(j)?.let { TeamStateCodec.ledgerEntry(it) } }
                            }.orEmpty(),
                            workLinks = a.optJSONArray("workLinks")?.let { l ->
                                (0 until l.length()).mapNotNull { j -> l.optString(j).takeIf(String::isNotBlank) }
                            }.orEmpty(),
                        )
                    }
                }
            }
        }
        memory
    }.getOrNull()
}
