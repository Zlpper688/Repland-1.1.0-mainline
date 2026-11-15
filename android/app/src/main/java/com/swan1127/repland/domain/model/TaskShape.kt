package com.swan1127.repland.domain.model

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一件事在时间上的形态。不是第二套任务系统，是同一个 [Task] 的三种验收标准。
 *
 * 三类的差别不在标签好不好看，而在"怎么算做完了"：
 * 短期立刻算准时到场，一天内算当天结清并留痕，长期算投入的时长与阶段推进。
 */
enum class TaskHorizon {
    /** 用户没说。按一天内对待，不凭空替他决定要闹钟还是要长期投入。 */
    UNSPECIFIED,

    /** 某个时间点要去做一件立刻的事（起床、检查、赴约）。主打准时提醒。 */
    IMMEDIATE,

    /** 当天用当天解决（写实验报告）。事后只留痕，不反复提醒。 */
    TODAY,

    /** 要持续投入的事（备考、练一项能力、比赛）。量化标准是学习时长。 */
    LONG_TERM,
    ;

    val isSpecified: Boolean get() = this != UNSPECIFIED

    /** 没说的时候按一天内走：这是应用本来的形态，也是最少惊喜的一种。 */
    val effective: TaskHorizon get() = if (this == UNSPECIFIED) TODAY else this
}

/** 长期任务的一个阶段。内容和总时长在 [Task.description] / [Task.totalDurationMinutes] 上。 */
data class TaskMilestone(
    val title: String,
    val dueDate: LocalDate? = null,
    val done: Boolean = false,
)

object TaskHorizonPolicy {
    /** 短期立刻必须有一个时间点，否则"准时"无从谈起。 */
    fun needsMoment(horizon: TaskHorizon): Boolean = horizon.effective == TaskHorizon.IMMEDIATE

    /** 长期必须说清每天投多久，否则"量化"无从谈起。 */
    fun needsDailyInvest(horizon: TaskHorizon): Boolean = horizon.effective == TaskHorizon.LONG_TERM

    fun momentOf(task: Task): java.time.LocalDateTime? {
        if (!needsMoment(task.horizon)) return null
        val date = task.scheduledForDate ?: return null
        val minute = task.atMinute ?: return null
        return date.atStartOfDay().plusMinutes(minute.toLong())
    }

    /** 长期任务的进度按已投入时长算，而不是按"感觉做了多少"。 */
    fun investProgress(task: Task): Int? {
        val target = task.totalDurationMinutes?.takeIf { it > 0 } ?: return null
        val invested = task.actualDurationMinutes ?: 0
        return ((invested * 100L) / target).toInt().coerceIn(0, 100)
    }
}

/**
 * 捕获时那把绿→红的点尺。左边缓、右边重，可以不选。
 *
 * 存 0..100 的原始值而不是只存档位：AI 排期要能分出"同一档里谁更重"，
 * 而界面上的档位仍然只有 [TaskPriority] 那五个，用户不必学一套新词。
 */
object TaskImportance {
    const val DOTS = 9
    const val MIN = 0
    const val MAX = 100

    fun ofDot(index: Int): Int = (index.coerceIn(0, DOTS - 1) * MAX) / (DOTS - 1)

    fun dotOf(importance: Int?): Int? =
        importance?.let { ((it.coerceIn(MIN, MAX) * (DOTS - 1)) + MAX / 2) / MAX }

    fun priorityOf(importance: Int?): TaskPriority = when {
        importance == null -> TaskPriority.UNSPECIFIED
        importance >= 88 -> TaskPriority.REQUIRED
        importance >= 63 -> TaskPriority.HIGH
        importance >= 38 -> TaskPriority.MEDIUM
        else -> TaskPriority.LOW
    }

    /** 反过来：老任务只有档位，点尺要能回显出一个不撒谎的位置。 */
    fun importanceOf(priority: TaskPriority): Int? = when (priority) {
        TaskPriority.UNSPECIFIED -> null
        TaskPriority.REQUIRED -> MAX
        TaskPriority.HIGH -> 75
        TaskPriority.MEDIUM -> 50
        TaskPriority.LOW -> 12
    }
}

/** 标签。无标签也是一种标签——收纳里它有自己的格子，不是"漏掉了"。 */
object TaskTags {
    const val MAX_COUNT = 6
    const val MAX_LENGTH = 12
    const val UNTAGGED = "未标签"
    private const val SEPARATOR = "|"

    fun normalize(raw: List<String>): List<String> = raw.asSequence()
        .map { it.replace(SEPARATOR, " ").replace("\n", " ").trim() }
        .filter { it.isNotEmpty() }
        .map { if (it.length > MAX_LENGTH) it.take(MAX_LENGTH) else it }
        .distinct()
        .take(MAX_COUNT)
        .toList()

    fun encode(tags: List<String>): String = normalize(tags).joinToString(SEPARATOR)

    fun decode(value: String): List<String> =
        if (value.isBlank()) emptyList() else normalize(value.split(SEPARATOR))

    /** 收纳的分格键：空标签归到"未标签"，而不是被过滤掉。 */
    fun bucketsOf(task: Task): List<String> = task.tags.ifEmpty { listOf(UNTAGGED) }

    fun matches(task: Task, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return task.displayName.contains(needle, ignoreCase = true) ||
            task.description.contains(needle, ignoreCase = true) ||
            task.tags.any { it.contains(needle, ignoreCase = true) }
    }
}

object TaskMilestones {
    fun encode(milestones: List<TaskMilestone>): String {
        if (milestones.isEmpty()) return ""
        val array = JSONArray()
        milestones.forEach { milestone ->
            array.put(
                JSONObject()
                    .put("title", milestone.title)
                    .put("due", milestone.dueDate?.toEpochDay())
                    .put("done", milestone.done),
            )
        }
        return array.toString()
    }

    fun decode(value: String): List<TaskMilestone> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                TaskMilestone(
                    title = title,
                    dueDate = if (item.isNull("due")) null else LocalDate.ofEpochDay(item.optLong("due")),
                    done = item.optBoolean("done"),
                )
            }
        }.getOrDefault(emptyList())
    }
}