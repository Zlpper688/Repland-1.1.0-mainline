package com.swan1127.repland.data

import android.content.Context
import com.swan1127.repland.domain.model.LearningMemoryPointSnapshot
import com.swan1127.repland.domain.model.LearningMemorySnapshot
import com.swan1127.repland.domain.model.MemoryEntry
import com.swan1127.repland.domain.model.MemoryModel
import com.swan1127.repland.domain.model.MemoryReference
import com.swan1127.repland.domain.model.MemoryState
import com.swan1127.repland.domain.model.MemoryStateCodec
import com.swan1127.repland.domain.model.RecallGrade
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskExecutionLog
import com.swan1127.repland.domain.model.TaskPriority
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ReviewPoint(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val content: String,
    val createdOn: LocalDate,
    val stage: Int,
    val memory: MemoryState? = null,
) {
    val dueOn: LocalDate get() = memory?.let { MemoryModel.dueOn(it) }
        ?: createdOn.plusDays(EBBINGHAUS_DAYS[stage.coerceIn(0, EBBINGHAUS_DAYS.lastIndex)].toLong())

    val isGraduated: Boolean get() = memory == null && stage >= EBBINGHAUS_DAYS.size

    fun memoryState(): MemoryState = memory
        ?: MemoryModel.fromLadderStage(createdOn, stage, importance = 3)

    fun graded(grade: RecallGrade, today: LocalDate, gain: Double = 1.0): ReviewPoint =
        if (isGraduated) this else copy(memory = MemoryModel.grade(memoryState(), grade, today, gain))

    companion object {
        val EBBINGHAUS_DAYS = listOf(1, 2, 4, 7, 15)

        fun candidatesFrom(task: Task, logs: List<TaskExecutionLog>): List<String> = buildList {
            task.completionResult?.takeIf(String::isNotBlank)?.trim()?.let(::add)
            logs.filter { it.taskId == task.id }.forEach { log ->
                log.feedback.completionResult?.takeIf(String::isNotBlank)?.trim()?.let(::add)
                log.feedback.completedContent?.takeIf(String::isNotBlank)?.trim()?.let(::add)
            }
        }.distinct()

        fun shouldSeed(existing: List<ReviewPoint>, taskId: String, content: String): Boolean {
            val trimmed = content.trim()
            return trimmed.isNotEmpty() && existing.none { it.taskId == taskId && it.content.trim() == trimmed }
        }
    }
}

class AuxiliaryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("repland-aux", Context.MODE_PRIVATE)
    private val memoryPrefs = context.applicationContext.getSharedPreferences("repland-memory-v1", Context.MODE_PRIVATE)

    /** 全在本地算，默认开着：记忆不开，规划就只能按空白时间排，用户不会因此少看到什么。 */
    var memoryReferenceEnabled: Boolean
        get() = synchronized(MEMORY_LOCK) {
            runCatching { memoryPrefs.getBoolean(KEY_REFERENCE_ENABLED, true) }.getOrDefault(true)
        }
        set(value) = synchronized(MEMORY_LOCK) {
            memoryPrefs.edit().putBoolean(KEY_REFERENCE_ENABLED, value).apply()
        }

    val memoryGain: Double
        get() = synchronized(MEMORY_LOCK) {
            MemoryStateCodec.gain(
                runCatching {
                    (prefs.getString(KEY_MEMORY_GAIN, null) ?: memoryPrefs.getString(KEY_GAIN, null))
                        ?.toDoubleOrNull()
                }.getOrNull() ?: 1.0,
            )
        }

    fun reviewPoints(): List<ReviewPoint> = synchronized(MEMORY_LOCK) {
        val raw = runCatching { prefs.getString(KEY_REVIEW_POINTS, null) }.getOrNull() ?: return@synchronized emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyList()
        (0 until array.length()).mapNotNull { index ->
            val stored = array.optJSONObject(index) ?: return@mapNotNull null
            val createdOn = runCatching { LocalDate.parse(stored.optString("createdOn")) }.getOrNull()
                ?: return@mapNotNull null
            val id = stored.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val taskId = stored.optString("taskId").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val content = stored.optString("content").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            ReviewPoint(
                id = id,
                taskId = taskId,
                taskTitle = stored.optString("taskTitle"),
                content = content,
                createdOn = createdOn,
                stage = stored.optInt("stage", 0),
                memory = stored.optJSONObject("memory")?.let { state ->
                    MemoryStateCodec.decode(
                        state.keys().asSequence().associateWith { key ->
                            if (state.isNull(key)) null else state.optString(key)
                        },
                    )
                },
            )
        }.distinctBy(ReviewPoint::id)
    }

    fun addReviewPointsFor(
        task: Task,
        logs: List<TaskExecutionLog> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): Int = synchronized(MEMORY_LOCK) {
        val existing = reviewPoints()
        val fresh = ReviewPoint.candidatesFrom(task, logs)
            .filter { ReviewPoint.shouldSeed(existing, task.id, it) }
            .map { content -> newPoint(task.id, task.displayName, content, today, task.userPriority) }
        if (fresh.isNotEmpty()) writeReviewPoints(existing + fresh)
        fresh.size
    }

    /**
     * 隐式回忆证据：一轮专注盖满算「想起来了」，中途收掉算「模模糊糊」。
     * 每项每天只记一条，`reviews` 因此是「有证据的天数」而不是点击次数。
     * 同日新种下的点不会被这条证据推进——模型算出的当日可提取率是 1，增长系数天然是 0。
     */
    fun recordRecallEvidence(
        taskId: String,
        grade: RecallGrade,
        today: LocalDate = LocalDate.now(),
    ): Boolean = synchronized(MEMORY_LOCK) {
        val points = reviewPoints()
        val targets = points.filter {
            it.taskId == taskId && !it.isGraduated && it.memoryState().lastEventOn != today
        }
        if (targets.isEmpty()) return@synchronized false
        val gain = memoryGain
        val updated = points.map { point ->
            if (point in targets) point.graded(grade, today, gain) else point
        }
        // 证据和它推导出的整体增益必须同一次落盘：进程重启不能把它们拆开，否则画像会自我强化。
        val nextGain = targets.fold(gain) { acc, point ->
            MemoryModel.observeGain(MemoryModel.retrievabilityAt(point.memoryState(), today), grade, acc)
        }
        val previousPoints = prefs.getString(KEY_REVIEW_POINTS, null)
        val previousGain = prefs.getString(KEY_MEMORY_GAIN, null)
        val saved = runCatching {
            prefs.edit()
                .putString(KEY_REVIEW_POINTS, reviewPointsJson(updated))
                .putString(KEY_MEMORY_GAIN, nextGain.toString())
                .commit()
        }.getOrDefault(false)
        if (!saved) {
            // SharedPreferences changes its in-memory map even when commit fails. Restore that map
            // before allowing a retry so one answer cannot be applied twice after a disk failure.
            prefs.edit()
                .putString(KEY_REVIEW_POINTS, previousPoints)
                .putString(KEY_MEMORY_GAIN, previousGain)
                .apply()
        }
        saved
    }

    /**
     * 记忆画像。不受参考开关约束——开关管的是「自动计划能不能引用记忆」，
     * 而画像是给用户和助手看的现状描述，关掉引用时也该看得见。
     */
    fun memoryProfileSummary(today: LocalDate = LocalDate.now()): MemoryReference = synchronized(MEMORY_LOCK) {
        MemoryReference.of(
            reviewPoints().filterNot(ReviewPoint::isGraduated).map { point ->
                MemoryEntry(point.taskId, point.content, point.memoryState())
            },
            today,
        )
    }

    fun memoryReference(today: LocalDate = LocalDate.now()): MemoryReference? =
        if (!memoryReferenceEnabled) null else memoryProfileSummary(today)

    fun learningMemorySnapshot(): LearningMemorySnapshot = synchronized(MEMORY_LOCK) {
        LearningMemorySnapshot(
            referenceEnabled = memoryReferenceEnabled,
            gain = memoryGain,
            points = reviewPoints().map { point ->
                LearningMemoryPointSnapshot(
                    point.id, point.taskId, point.taskTitle, point.content, point.createdOn, point.stage, point.memory,
                )
            },
        )
    }

    /** Shares the grading lock across UI/container store instances. Call on an IO dispatcher. */
    fun clearLearningMemory(): Unit = synchronized(MEMORY_LOCK) {
        check(prefs.edit().remove(KEY_REVIEW_POINTS).remove(KEY_MEMORY_GAIN).commit()) {
            "Learning memory could not be cleared."
        }
        check(memoryPrefs.edit().clear().commit()) { "Learning memory settings could not be cleared." }
    }

    // ---------------------------------------------------------- 小组记忆（mem0 形状）

    /** 群聊小组可长期依赖的用户事实；键唯一、值取最新，超过上限时淘汰最旧的。 */
    fun teamMemoryFacts(): List<Pair<String, String>> = synchronized(MEMORY_LOCK) {
        val raw = runCatching { memoryPrefs.getString(KEY_TEAM_FACTS, null) }.getOrNull()
            ?: return@synchronized emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to item.optString("value").trim()
        }
    }

    fun noteTeamMemoryFact(key: String, value: String): Boolean = synchronized(MEMORY_LOCK) {
        val trimmedKey = key.trim()
        val trimmedValue = value.trim()
        if (trimmedKey.isEmpty() || trimmedValue.isEmpty()) return@synchronized false
        val rest = teamMemoryFacts().filterNot { it.first == trimmedKey }
        val updated = (rest + (trimmedKey to trimmedValue)).takeLast(MAX_TEAM_FACTS)
        memoryPrefs.edit().putString(KEY_TEAM_FACTS, teamFactsJson(updated)).apply()
        true
    }

    fun forgetTeamMemoryFact(key: String): Boolean = synchronized(MEMORY_LOCK) {
        val trimmedKey = key.trim()
        val rest = teamMemoryFacts().filterNot { it.first == trimmedKey }
        if (rest.size == teamMemoryFacts().size) return@synchronized false
        memoryPrefs.edit().putString(KEY_TEAM_FACTS, teamFactsJson(rest)).apply()
        true
    }

    /** 最近的小组对话片段，最多 [MAX_TEAM_EPISODES] 条，新的在最后。 */
    fun teamMemoryEpisodes(): List<String> = synchronized(MEMORY_LOCK) {
        val raw = runCatching { memoryPrefs.getString(KEY_TEAM_EPISODES, null) }.getOrNull()
            ?: return@synchronized emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf(String::isNotBlank)
        }
    }

    fun appendTeamMemoryEpisode(summary: String): Unit = synchronized(MEMORY_LOCK) {
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return@synchronized
        val updated = (teamMemoryEpisodes() + trimmed).takeLast(MAX_TEAM_EPISODES)
        val array = JSONArray()
        updated.forEach(array::put)
        memoryPrefs.edit().putString(KEY_TEAM_EPISODES, array.toString()).apply()
    }

    fun teamMemoryCounts(): Pair<Int, Int> = synchronized(MEMORY_LOCK) {
        teamMemoryFacts().size to teamMemoryEpisodes().size
    }

    private fun teamFactsJson(facts: List<Pair<String, String>>): String {
        val array = JSONArray()
        facts.forEach { (key, value) -> array.put(JSONObject().put("key", key).put("value", value)) }
        return array.toString()
    }

    private fun newPoint(
        taskId: String,
        taskTitle: String,
        content: String,
        today: LocalDate,
        priority: TaskPriority,
    ): ReviewPoint = ReviewPoint(
        id = UUID.randomUUID().toString(),
        taskId = taskId,
        taskTitle = taskTitle,
        content = content,
        createdOn = today,
        stage = 0,
        memory = MemoryModel.seed(MemoryModel.importanceOf(priority), today),
    )

    private fun writeReviewPoints(points: List<ReviewPoint>) {
        prefs.edit().putString(KEY_REVIEW_POINTS, reviewPointsJson(points)).apply()
    }

    private fun reviewPointsJson(points: List<ReviewPoint>): String {
        val array = JSONArray()
        points.forEach { point ->
            val memory = point.memory?.let { state ->
                JSONObject().apply {
                    MemoryStateCodec.encode(state).forEach { (key, value) -> put(key, value) }
                }
            }
            array.put(
                JSONObject()
                    .put("id", point.id)
                    .put("taskId", point.taskId)
                    .put("taskTitle", point.taskTitle)
                    .put("content", point.content)
                    .put("createdOn", point.createdOn.toString())
                    .put("stage", point.stage)
                    .put("memory", memory),
            )
        }
        return array.toString()
    }

    private companion object {
        const val KEY_REVIEW_POINTS = "review-points"
        const val KEY_REFERENCE_ENABLED = "reference-enabled"
        const val KEY_GAIN = "gain"
        const val KEY_MEMORY_GAIN = "memory-gain-v1"
        const val KEY_TEAM_FACTS = "team-memory-facts"
        const val KEY_TEAM_EPISODES = "team-memory-episodes"
        const val MAX_TEAM_FACTS = 24
        const val MAX_TEAM_EPISODES = 6
        val MEMORY_LOCK = Any()
    }
}
