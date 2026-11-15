package com.swan1127.repland.data.team

import android.content.Context
import com.swan1127.repland.domain.team.FreeWindow
import com.swan1127.repland.domain.team.RosterPerson
import com.swan1127.repland.domain.team.ScheduleConfidence
import com.swan1127.repland.domain.team.TeamRosterBook
import java.io.File
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 队友名册落盘（个人域，独立于队伍快照）：换队、归档、清队伍都不丢历史队友。
 * 与 HumanProfileStore 同一套做法：单 JSON 文件 + Mutex 串行读写，坏档回退空名册。
 */
class TeamRosterStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "team-roster.json"))

    private val mutex = Mutex()

    suspend fun load(): TeamRosterBook = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { TeamRosterCodec.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull() ?: TeamRosterBook()
        }
    }

    suspend fun save(book: TeamRosterBook) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(TeamRosterCodec.toJson(book), Charsets.UTF_8) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }
}

object TeamRosterCodec {

    fun toJson(book: TeamRosterBook): String = JSONObject().apply {
        put("schema", 1)
        put("people", JSONArray().apply {
            book.people.forEach { person ->
                put(JSONObject().apply {
                    put("key", person.key)
                    put("name", person.name)
                    person.roleTag?.let { put("roleTag", it) }
                    put("responsibility", person.responsibility)
                    put("availability", JSONObject().apply {
                        person.weeklyAvailability.forEach { (day, windows) ->
                            put(day.name, JSONArray().apply {
                                windows.forEach { w -> put(JSONObject().put("s", w.startMinute).put("e", w.endMinute)) }
                            })
                        }
                    })
                    put("scheduleConfidence", person.scheduleConfidence.name)
                    put("dailyCapacityHours", person.dailyCapacityHours)
                    put("teamIds", JSONArray().apply { person.teamIds.forEach { put(it) } })
                    person.lastTeamId?.let { put("lastTeamId", it) }
                    person.lastTeamName?.let { put("lastTeamName", it) }
                    person.competitionName?.let { put("competitionName", it) }
                    put("lastSeenEpochMillis", person.lastSeenEpochMillis)
                    put("note", person.note)
                })
            }
        })
    }.toString()

    fun fromJson(text: String): TeamRosterBook? = runCatching {
        val json = JSONObject(text)
        val people = json.optJSONArray("people")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::person) }
        }.orEmpty()
        TeamRosterBook(people)
    }.getOrNull()

    private fun person(json: JSONObject): RosterPerson? = runCatching {
        val name = json.getString("name")
        if (name.isBlank()) return@runCatching null
        RosterPerson(
            key = json.optString("key").ifBlank { TeamRosterBook.keyOf(name) },
            name = name,
            roleTag = json.optString("roleTag", null),
            responsibility = json.optString("responsibility", ""),
            weeklyAvailability = availability(json.optJSONObject("availability")),
            scheduleConfidence = runCatching {
                ScheduleConfidence.valueOf(json.optString("scheduleConfidence", "HIGH"))
            }.getOrDefault(ScheduleConfidence.HIGH),
            dailyCapacityHours = json.optDouble("dailyCapacityHours", 3.0),
            teamIds = json.optJSONArray("teamIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
            }.orEmpty(),
            lastTeamId = json.optString("lastTeamId", null),
            lastTeamName = json.optString("lastTeamName", null),
            competitionName = json.optString("competitionName", null),
            lastSeenEpochMillis = json.optLong("lastSeenEpochMillis", 0L),
            note = json.optString("note", ""),
        )
    }.getOrNull()

    private fun availability(json: JSONObject?): Map<DayOfWeek, List<FreeWindow>> = buildMap {
        json?.let { days ->
            days.keys().forEach { key ->
                runCatching { DayOfWeek.valueOf(key) }.getOrNull()?.let { day ->
                    val windows = days.getJSONArray(key).let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { w ->
                                runCatching { FreeWindow(w.optInt("s"), w.optInt("e")) }.getOrNull()
                            }
                        }
                    }
                    if (windows.isNotEmpty()) put(day, windows)
                }
            }
        }
    }
}
