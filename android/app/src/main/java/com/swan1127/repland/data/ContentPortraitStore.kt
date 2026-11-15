package com.swan1127.repland.data

import android.content.Context
import com.swan1127.repland.domain.model.ContentPortrait
import com.swan1127.repland.domain.model.PortraitEvidence
import com.swan1127.repland.domain.model.PortraitPoint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 内容画像落盘。和助手日记同一套做法：一个 JSON 文件、一把锁、坏文件不炸应用。 */
class ContentPortraitStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "content-portrait.json"))

    private val mutex = Mutex()

    suspend fun load(): ContentPortrait = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { ContentPortraitCodec.fromJson(file.readText(Charsets.UTF_8)) }
                .getOrNull() ?: ContentPortrait()
        }
    }

    suspend fun save(portrait: ContentPortrait) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(ContentPortraitCodec.toJson(portrait), Charsets.UTF_8) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }
}

object ContentPortraitCodec {

    fun toJson(portrait: ContentPortrait): String = JSONObject().apply {
        put("schema", 1)
        put("version", portrait.version)
        put("updatedAt", portrait.updatedAtMillis ?: JSONObject.NULL)
        put("materialDays", portrait.materialDays)
        put("consentAt", portrait.consentAtMillis ?: JSONObject.NULL)
        put("points", JSONArray().apply {
            portrait.points.forEach { point ->
                put(JSONObject().put("text", point.text).put("evidence", JSONArray(point.evidenceIds)))
            }
        })
        put("evidence", JSONArray().apply {
            portrait.evidence.forEach { entry ->
                put(JSONObject().put("id", entry.id).put("text", entry.text))
            }
        })
        put("corrections", JSONArray(portrait.corrections))
    }.toString()

    fun fromJson(raw: String): ContentPortrait {
        val json = JSONObject(raw)
        return ContentPortrait(
            version = json.optInt("version", 0),
            updatedAtMillis = json.optLongOrNull("updatedAt"),
            materialDays = json.optInt("materialDays", 30),
            consentAtMillis = json.optLongOrNull("consentAt"),
            points = json.optJSONArray("points")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val text = item.optString("text").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val ids = item.optJSONArray("evidence")?.let { ids ->
                        (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }
                    }.orEmpty()
                    PortraitPoint(text, ids)
                }
            }.orEmpty(),
            evidence = json.optJSONArray("evidence")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    PortraitEvidence(id, item.optString("text"))
                }
            }.orEmpty(),
            corrections = json.optJSONArray("corrections")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
