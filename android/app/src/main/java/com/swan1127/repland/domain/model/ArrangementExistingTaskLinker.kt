package com.swan1127.repland.domain.model

/**
 * "把开会改到下午三点" addresses work that already exists. The local
 * interpreter only sees the sentence, so its candidate carries no task
 * reference; without this linking step confirmation would create a second task
 * with the same name instead of moving the scheduled one.
 *
 * Only reschedule phrasing triggers linking. A sentence such as "再复习一次高数"
 * is genuinely new work and must stay unlinked.
 */
object ArrangementExistingTaskLinker {
    private val rescheduleVerbs =
        Regex("(改到|改成|改在|换到|换成|挪到|挪成|调到|调成|移到|移成|调整到|提前到|推迟到|延后到|时间改成)")

    private val noise =
        Regex(
            "(今天|明天|后天|大后天|今晚|今早|今下午|上午|下午|晚上|中午|早上|下周|本周|这周|" +
                "周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]|" +
                "改到|改成|改在|换到|换成|挪到|挪成|调到|调成|移到|移成|调整到|提前到|推迟到|延后到|时间改成|" +
                "把|请|帮我|这个|那个|一下|时间|安排|" +
                "\\d{1,2}[:：]\\d{2}|\\d{1,2}\\s*(?:点|时)(?:半|一刻|三刻|\\d{1,2}\\s*分?)?|" +
                "(?:一|二|两|三|四|五|六|七|八|九|十)\\s*(?:点|时)(?:半|一刻|三刻)?)",
        )

    fun link(
        candidates: List<ArrangementCandidate>,
        existing: List<ArrangementExistingTask>,
        utterance: String,
    ): List<ArrangementCandidate> {
        if (candidates.isEmpty() || existing.isEmpty()) return candidates
        if (!rescheduleVerbs.containsMatchIn(utterance)) return candidates
        val normalizedUtterance = normalize(utterance)
        val mentioned = existing.filter { task ->
            val title = normalize(task.title)
            title.length >= 2 && normalizedUtterance.contains(title)
        }
        return candidates.map { candidate ->
            if (candidate.existingTaskId != null) candidate else {
                val ownName = normalize(candidate.title.replace(noise, ""))
                val byOwnName = existing.firstOrNull { task ->
                    val title = normalize(task.title)
                    title.length >= 2 &&
                        (ownName == title ||
                            (ownName.length >= 2 && (ownName.contains(title) || title.contains(ownName))))
                }
                val match = byOwnName ?: mentioned.singleOrNull()?.takeIf { candidates.size == 1 }
                if (match == null) {
                    candidate
                } else {
                    candidate.copy(
                        title = match.title,
                        category = match.category,
                        existingTaskId = match.id,
                        durationMinutes = candidate.durationMinutes ?: match.durationMinutes,
                        priority = candidate.priority ?: match.priority,
                    )
                }
            }
        }
    }

    private fun normalize(text: String): String =
        text.replace(Regex("[\\s，。；;、:：!！?？\"'“”‘’]"), "")
}
