package com.swan1127.repland.domain.team

/**
 * 按需求找队友：只读名册里的确定信息（角色、分工、备注、空闲、共同参赛次数），
 * 不调用模型、不编造资料；排序理由直接展示给用户，手动与对话走同一套规则。
 */
data class TeamNeed(
    val title: String,
    val roleHint: String = "",
    val hoursPerWeek: Double = 0.0,
) {
    val hasText: Boolean get() = title.isNotBlank() || roleHint.isNotBlank()
}

data class TeammateMatch(
    val person: RosterPerson,
    val score: Int,
    val hits: List<String>,
    val reasons: List<String>,
    val weeklyFreeMinutes: Int,
)

object TeammateMatchEngine {

    private val SEPARATORS = Regex("[\\s，。、；;：:,./|｜!?！？]+")

    fun rank(
        people: List<RosterPerson>,
        need: TeamNeed,
        excludeKeys: Set<String> = emptySet(),
    ): List<TeammateMatch> {
        if (!need.hasText) return emptyList()
        val requiredMinutes = (need.hoursPerWeek * 60).toInt().coerceAtLeast(0)
        return people
            .filter { it.key !in excludeKeys }
            .map { person -> match(person, need, requiredMinutes) }
            .sortedWith(
                compareByDescending<TeammateMatch> { it.score }
                    .thenByDescending { it.person.hasTeamed }
                    .thenByDescending { it.person.lastSeenEpochMillis }
                    .thenBy { it.person.name },
            )
    }

    private fun match(person: RosterPerson, need: TeamNeed, requiredMinutes: Int): TeammateMatch {
        val knowledge = chunks(need.title + " " + need.roleHint)
        val main = (person.roleTag.orEmpty() + " " + person.responsibility).lowercase()
        val note = person.note.lowercase()
        // 命中按需求里的原文片段展示；直接列分词碎片会出现「命中 ppt、pp、pt」。
        val mainHits = knowledge.flatMap { matchedSpans(it, main) }
        val noteHits = knowledge.flatMap { chunk ->
            if (matchedSpans(chunk, main).isEmpty()) matchedSpans(chunk, note) else emptyList()
        }
        val hits = (mainHits + noteHits).distinct()
        val weeklyFree = person.weeklyAvailability.values.sumOf { windows -> windows.sumOf { it.minutes } }

        var score = mainHits.size * 12 + noteHits.size * 4
        val reasons = mutableListOf<String>()
        if (mainHits.isNotEmpty()) {
            reasons += "命中分工：${mainHits.take(3).joinToString("、")}"
        }
        if (noteHits.isNotEmpty()) {
            reasons += "命中备注：${noteHits.distinct().take(3).joinToString("、")}"
        }
        if (mainHits.isEmpty() && noteHits.isEmpty()) {
            reasons += "能力关键词没命中"
        }
        if (person.hasTeamed) {
            score += 10
            reasons += buildString {
                append("一起 ${person.teamIds.size} 场")
                person.lastTeamName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }
        }
        if (requiredMinutes > 0) {
            when {
                weeklyFree <= 0 -> reasons += "空闲未登记"
                weeklyFree >= requiredMinutes -> {
                    score += 12
                    reasons += "每周空 ${hoursText(weeklyFree)}，够 ${hoursText(requiredMinutes)}"
                }
                else -> {
                    score += 4
                    reasons += "每周空 ${hoursText(weeklyFree)}，少于 ${hoursText(requiredMinutes)}"
                }
            }
        } else if (weeklyFree > 0) {
            score += 3
            reasons += "每周空 ${hoursText(weeklyFree)}"
        }
        person.roleTag?.takeIf { it.isNotBlank() }?.let { reasons += "角色：$it" }
        if (person.dailyCapacityHours >= 1) {
            score += minOf(4, person.dailyCapacityHours.toInt())
            reasons += "每日可投入 ${hoursText((person.dailyCapacityHours * 60).toInt())}"
        }
        return TeammateMatch(person, score, hits.take(4), reasons, weeklyFree)
    }

    /** 保留需求原文的词，用于命中后回显，不把「路演PPT」拆成碎片。 */
    private fun chunks(raw: String): List<String> {
        val seen = linkedSetOf<String>()
        return raw.split(SEPARATORS).filter { chunk ->
            chunk.isNotBlank() && chunk.length >= 2 && seen.add(chunk.lowercase())
        }
    }

    /**
     * 整词命中优先；否则用重叠二字片段定位，再把相邻片段并回一个词，
     * 例如「找开发写后端」对「开发 · 写后端」得到 [开发, 写后端]，而不是一串二字碎片。
     */
    private fun matchedSpans(chunk: String, target: String): List<String> {
        val lower = chunk.lowercase()
        if (target.contains(lower)) return listOf(chunk)
        if (lower.length < 2) return emptyList()
        val starts = (0..lower.length - 2).filter { target.contains(lower.substring(it, it + 2)) }
        if (starts.isEmpty()) return emptyList()
        val spans = mutableListOf<String>()
        var from = starts.first()
        var to = from
        starts.drop(1).forEach { start ->
            if (start == to + 1) {
                to = start
            } else {
                spans += chunk.substring(from, to + 2)
                from = start
                to = start
            }
        }
        spans += chunk.substring(from, to + 2)
        return spans
    }

    private fun hoursText(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}h" else "%.1f".format(minutes / 60f).removeSuffix(".0") + "h"
}
