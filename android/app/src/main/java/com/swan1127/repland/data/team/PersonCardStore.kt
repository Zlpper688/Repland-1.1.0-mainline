package com.swan1127.repland.data.team

import android.content.Context
import com.swan1127.repland.domain.team.CardVisibility
import com.swan1127.repland.domain.team.FreeWindow
import com.swan1127.repland.domain.team.PersonCard
import com.swan1127.repland.domain.team.PersonCardBook
import com.swan1127.repland.domain.team.PersonCardKind
import java.io.File
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人资料卡落盘（个人域，独立于队伍快照）：默认卡与竞赛履历跨队保留。
 * 与队友名册同一套做法：单 JSON 文件 + Mutex 串行读写，坏档回退「只有默认卡」。
 */
class PersonCardStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "person-cards.json"))

    private val mutex = Mutex()

    suspend fun load(): PersonCardBook = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { PersonCardCodec.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull() ?: PersonCardBook.empty()
        }
    }

    suspend fun save(book: PersonCardBook) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(PersonCardCodec.toJson(book), Charsets.UTF_8) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }
}

object PersonCardCodec {

    fun toJson(book: PersonCardBook): String = JSONObject().apply {
        put("schema", 1)
        put("cards", JSONArray().apply {
            book.allCards().forEach { card ->
                put(JSONObject().apply {
                    put("cardId", card.cardId)
                    put("kind", card.kind.name)
                    put("name", card.name)
                    put("headline", card.headline)
                    put("abilities", card.abilities)
                    put("expectation", card.expectation)
                    card.roleTag?.let { put("roleTag", it) }
                    card.teamId?.let { put("teamId", it) }
                    card.teamName?.let { put("teamName", it) }
                    card.competitionName?.let { put("competitionName", it) }
                    put("result", card.result)
                    put("teammateCount", card.teammateCount)
                    put("availability", JSONObject().apply {
                        card.weeklyAvailability.forEach { (day, windows) ->
                            put(day.name, JSONArray().apply {
                                windows.forEach { w -> put(JSONObject().put("s", w.startMinute).put("e", w.endMinute)) }
                            })
                        }
                    })
                    put("dailyCapacityHours", card.dailyCapacityHours)
                    put("visibility", card.visibility.name)
                    put("updatedAtEpochMillis", card.updatedAtEpochMillis)
                })
            }
        })
    }.toString()

    fun fromJson(text: String): PersonCardBook? = runCatching {
        val json = JSONObject(text)
        val cards = json.optJSONArray("cards")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::card) }
        }.orEmpty()
        PersonCardBook(cards)
    }.getOrNull()

    private fun card(json: JSONObject): PersonCard? = runCatching {
        val name = json.optString("name")
        val kind = runCatching { PersonCardKind.valueOf(json.optString("kind", "DEFAULT")) }
            .getOrDefault(PersonCardKind.DEFAULT)
        val teamId = json.optString("teamId", null)
        val teamName = json.optString("teamName", null)
        if (kind == PersonCardKind.COMPETITION && (teamId.isNullOrBlank() || teamName.isNullOrBlank())) {
            return@runCatching null
        }
        PersonCard(
            cardId = json.optString("cardId").ifBlank {
                if (kind == PersonCardKind.DEFAULT) PersonCard.DEFAULT_ID
                else PersonCard.competitionCardId(teamId.orEmpty())
            },
            kind = kind,
            name = name,
            headline = json.optString("headline", ""),
            abilities = json.optString("abilities", ""),
            expectation = json.optString("expectation", ""),
            roleTag = json.optString("roleTag", null),
            teamId = teamId,
            teamName = teamName,
            competitionName = json.optString("competitionName", null),
            result = json.optString("result", ""),
            teammateCount = json.optInt("teammateCount", 0),
            weeklyAvailability = availability(json.optJSONObject("availability")),
            dailyCapacityHours = json.optDouble("dailyCapacityHours", 3.0),
            visibility = runCatching { CardVisibility.valueOf(json.optString("visibility", "PRIVATE")) }
                .getOrDefault(CardVisibility.PRIVATE),
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis", 0L),
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
