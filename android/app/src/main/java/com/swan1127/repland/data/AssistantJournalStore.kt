package com.swan1127.repland.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 助手日记里的一条主动留痕：助手在 [at] 主动说了 [summary]（主题 [topic]），
 * [replied] 如实记录用户此后有没有回话。没回话就是没有后续依据，不据此编造进度。
 */
data class JournalEntry(
    val id: String,
    val date: LocalDate,
    val at: LocalDateTime,
    val topic: String,
    val summary: String,
    val evidence: List<String>,
    val replied: Boolean,
)

/** 同一天多次刷新按主题成链留存（借鉴 Today AI #6）；聊天只展示最近一份，历史走这里。 */
data class AssistantJournal(val entries: List<JournalEntry> = emptyList())

/**
 * 助手日记落盘：独立 JSON 文件，不进 Room、不进队伍快照。这是"主动行为问责面"（借鉴 #5），
 * 不是记忆推断——它只记录助手主动说过的话和用户回没回话，绝不沉淀成推测信号。
 * 落盘范式与 [com.swan1127.repland.data.team.HumanProfileStore] 一致：文件 + Mutex + IO。
 */
class AssistantJournalStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "assistant-journal.json"))

    private val mutex = Mutex()

    suspend fun load(): AssistantJournal = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { AssistantJournalCodec.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull() ?: AssistantJournal()
        }
    }

    suspend fun save(journal: AssistantJournal) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(AssistantJournalCodec.toJson(journal), Charsets.UTF_8) }
        }
    }

    /** 数据管理一键清除本机数据时连带清除（助手日记属于本机个人数据）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }
}

object AssistantJournalCodec {

    private val DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    fun toJson(journal: AssistantJournal): String = JSONObject().apply {
        put("schema", 1)
        put("entries", JSONArray().apply {
            journal.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("date", entry.date.format(DATE))
                    put("at", entry.at.format(DATETIME))
                    put("topic", entry.topic)
                    put("summary", entry.summary)
                    put("evidence", JSONArray().apply { entry.evidence.forEach { put(it) } })
                    put("replied", entry.replied)
                })
            }
        })
    }.toString()

    fun fromJson(text: String): AssistantJournal? = runCatching {
        val json = JSONObject(text)
        val entries = mutableListOf<JournalEntry>()
        json.optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                arr.optJSONObject(i)?.let { o ->
                    runCatching {
                        entries += JournalEntry(
                            id = o.getString("id"),
                            date = LocalDate.parse(o.getString("date"), DATE),
                            at = LocalDateTime.parse(o.getString("at"), DATETIME),
                            topic = o.getString("topic"),
                            summary = o.optString("summary", ""),
                            evidence = o.optJSONArray("evidence")?.let { e ->
                                (0 until e.length()).mapNotNull { j -> e.optString(j).takeIf(String::isNotBlank) }
                            }.orEmpty(),
                            replied = o.optBoolean("replied", false),
                        )
                    }
                }
            }
        }
        AssistantJournal(entries)
    }.getOrNull()
}