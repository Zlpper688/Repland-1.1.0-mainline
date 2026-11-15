package com.swan1127.repland.domain.model

import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

enum class RuleAction { CREATE, SCHEDULE, ROUTINE, BUSY, CANCEL, COMPLETE, PROGRESS, POSTPONE }
enum class RuleIntentKind { QUERY, PLAN, CLARIFY }
enum class RuleQueryKind { TASKS, ADVICE, SPREAD }

data class RuleTask(
    val id: String,
    val name: String,
    val durationMinutes: Int? = null,
    val plannedDate: LocalDate? = null,
)

data class RuleItem(
    val action: RuleAction,
    val name: String,
    val date: LocalDate? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val durationMinutes: Int? = null,
    val durationExplicit: Boolean = false,
    val dueDate: LocalDate? = null,
    val dueMinute: Int? = null,
    val weekdays: List<Int>? = null,
    val progress: Int? = null,
    val move: Boolean = false,
    val actualMinutes: Int? = null,
    val period: String? = null,
)

data class RulePlan(
    val intent: RuleIntentKind,
    val action: RuleAction?,
    val items: List<RuleItem>,
    val draftItems: List<RuleItem>,
    val mustAsk: Boolean,
    val needsAI: Boolean,
    val reasons: List<String>,
    val confidence: Double,
    val contextTaskId: String? = null,
    val contextTaskName: String? = null,
    val preferredDate: LocalDate? = null,
    val dateExplicit: Boolean = false,
    val recordOnly: Boolean = false,
    val review: Boolean = false,
    val reviewProgress: Int? = null,
    val queryKind: RuleQueryKind = RuleQueryKind.TASKS,
    val gapMinutes: Int? = null,
)

/**
 * The deterministic reading of a sentence: names, actions and calendar slots.
 * The model owns language judgment; this parser owns dates, clocks, durations,
 * recurrence and the review/postpone protocol, so a slot can never be invented.
 */
object RuleIntentParser {
    private val CN_NUM = mapOf(
        '零' to 0.0, '一' to 1.0, '二' to 2.0, '两' to 2.0, '俩' to 2.0, '三' to 3.0, '四' to 4.0,
        '五' to 5.0, '六' to 6.0, '七' to 7.0, '八' to 8.0, '九' to 9.0, '十' to 10.0,
    )
    private val WEEKDAY = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7)
    private val STATUS_ACTIONS = setOf(RuleAction.COMPLETE, RuleAction.CANCEL, RuleAction.PROGRESS, RuleAction.POSTPONE)

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace('：', ':')
        .replace('，', ',').replace('、', ',')
        .replace(Regex("[。！？!?；;]")) { if (it.value == "？" || it.value == "?") "?" else "," }
        .replace(Regex("\\s+"), " ")
        .trim()

    fun categoryFor(text: String): TaskCategory = when {
        listOf("课", "复习", "作业", "考试", "背", "论文", "实验").any(text::contains) -> TaskCategory.COURSE
        listOf("会议", "工作", "报告", "邮件", "客户").any(text::contains) -> TaskCategory.OFFICE
        listOf("跑步", "运动", "吃", "家", "休息", "医生", "玩").any(text::contains) -> TaskCategory.LEISURE
        else -> TaskCategory.UNSPECIFIED
    }

    fun clarifyReply(reasons: List<String>): String {
        val has = reasons::contains
        return when {
            has("multi-intent") -> "这句里有两件事，我先记哪一件？拆成两句告诉我更稳。"
            has("conditional") -> "这件事带着条件，要我先按常规排上，还是等条件确认了再排？"
            has("pronoun") -> "你说的「那个」我还不确定是哪一项，能说一下名字吗？"
            has("unsupported-recurrence") -> "这种重复方式（隔天/每月）我还没法直接落到日程，先告诉我按哪几天排？"
            has("fixed-block") -> "课表这块我还没接上，先告诉我周几、几点到几点？"
            has("busy-window-missing") -> "要留出哪天的哪个时段？例如「明天下午三点到五点别排事」。"
            has("missing-name") -> "要给哪件事排时间？"
            has("bulk-move") -> "批量挪动我还没法直接执行，先说是哪几项、挪到哪天？"
            has("ambiguous") -> "这句我有两种理解：是要新增，还是改已有安排？"
            has("english-intent") -> "这句我不太确定要做什么，可以用中文说一下任务和时间吗？"
            has("cross-midnight") -> "这个时段跨天了，固定安排不能跨天；要拆成两段，还是换个结束时间？"
            has("ambiguous-weekend") -> "「下周末」有周六和周日两天，先定哪一天？"
            has("relative-month") -> "「下个月」还没法直接落到具体日期，给我一个日子或「下月几号」？"
            has("people") -> "这件事还涉及别人，先确认下具体是哪项任务、安排在什么时候？"
            else -> "这句我还没把握，能补一下要做什么、什么时候吗？"
        }
    }

    // ---- calendar -------------------------------------------------------

    private fun cnToNumber(raw: String?): Double? {
        if (raw == null) return null
        if (raw.isEmpty()) return null
        raw.toDoubleOrNull()?.let { return it }
        if (raw == "半") return 0.5
        if (raw.length == 1) CN_NUM[raw[0]]?.let { return it }
        if (raw == "十") return 10.0
        if (raw.startsWith("十")) return 10.0 + (CN_NUM[raw[1]] ?: 0.0)
        if (raw.endsWith("十")) return (CN_NUM[raw[0]] ?: 0.0) * 10
        val parts = raw.split("十")
        if (parts.size == 2) {
            val left = parts[0].firstOrNull()?.let(CN_NUM::get) ?: 0.0
            val right = parts[1].firstOrNull()?.let(CN_NUM::get) ?: 0.0
            return left * 10 + right
        }
        return null
    }

    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? = runCatching {
        val candidate = LocalDate.of(today.year, month, day)
        if (candidate < today) LocalDate.of(today.year + 1, month, day) else candidate
    }.getOrNull()

    private fun nextWeekday(today: LocalDate, target: Int, weekOffset: Int): LocalDate =
        today.plusDays((target - today.dayOfWeek.value).toLong()).plusWeeks(weekOffset.toLong())

    private data class DateHit(val date: LocalDate?, val span: String, val unsupported: Boolean = false)

    private fun parseDate(text: String, today: LocalDate): DateHit? {
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(text)?.let { match ->
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val date = runCatching { LocalDate.of(match.groupValues[1].toInt(), month, day) }.getOrNull()
            if (date != null) return DateHit(date, match.value)
        }
        Regex("(\\d{1,2})[月/](\\d{1,2})[号日]?").find(text)?.let { match ->
            monthDay(today, match.groupValues[1].toInt(), match.groupValues[2].toInt())
                ?.let { return DateHit(it, match.value) }
        }
        Regex("(\\d{1,2})\\s*[号日](?!\\s*[点时分])").find(text)?.let { match ->
            val day = match.groupValues[1].toInt()
            if (day in 1..31) monthDay(today, today.monthValue, day)?.let { return DateHit(it, match.value) }
        }
        Regex("(\\d{1,2})\\.(\\d{1,2})(?!\\d)(?!\\s*(?:个)?\\s*(?:小时|钟头|分钟|分|min|h|hr))")
            .find(text)?.let { match ->
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                if (month in 1..12 && day in 1..31) monthDay(today, month, day)?.let { return DateHit(it, match.value) }
            }
        Regex("(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])").find(text)?.let { match ->
            val target = WEEKDAY[match.groupValues[2]] ?: return@let
            val prefix = match.groupValues[1]
            val offset = when (prefix) { "下下" -> 2; "下" -> 1; else -> 0 }
            var date = nextWeekday(today, target, offset)
            if (prefix != "下" && prefix != "下下" && date < today) date = date.plusWeeks(1)
            return DateHit(date, match.value)
        }
        val words = listOf(
            "大后天" to 3L, "后天" to 2L, "明晚" to 1L, "明早" to 1L, "明天" to 1L, "明日" to 1L,
            "明儿" to 1L, "今晚" to 0L, "今早" to 0L, "今天" to 0L, "今日" to 0L, "昨天" to -1L, "昨日" to -1L,
        )
        words.forEach { (word, delta) ->
            if (text.contains(word)) return DateHit(today.plusDays(delta), word)
        }
        if (text.contains("月底")) return DateHit(today.with(TemporalAdjusters.lastDayOfMonth()), "月底")
        if (text.contains("下个月")) return DateHit(null, "下个月", unsupported = true)
        return null
    }

    private data class RecurrenceHit(val weekdays: List<Int>?, val span: String, val unsupported: Boolean = false)

    private fun parseRecurrence(text: String): RecurrenceHit? {
        Regex("(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])\\s*(?:到|至|-|~)\\s*(?:周|星期|礼拜)?([一二三四五六日天])")
            .find(text)?.let { match ->
                val start = WEEKDAY[match.groupValues[2]] ?: return@let
                val end = WEEKDAY[match.groupValues[3]] ?: return@let
                val weekdays = (start..end).toList().ifEmpty { listOf(start) }
                return RecurrenceHit(weekdays, match.value)
            }
        // A spoken pair ("周二和周四") must win over the shorter 每周X match, or
        // Thursday would silently disappear from the weekly arrangement.
        Regex("(?:周|星期|礼拜)([一二三四五六日天])\\s*(?:和|与|及|、|,)\\s*(?:周|星期|礼拜)?([一二三四五六日天])")
            .find(text)?.let { match ->
                val weekdays = listOfNotNull(WEEKDAY[match.groupValues[1]], WEEKDAY[match.groupValues[2]])
                    .distinct().sorted()
                return RecurrenceHit(weekdays, match.value)
            }
        Regex("每(?:周|星期|礼拜)(?:周|星期|礼拜)?([一二三四五六日天]+)").find(text)?.let { match ->
            val weekdays = match.groupValues[1].mapNotNull { WEEKDAY[it.toString()] }.distinct().sorted()
            return RecurrenceHit(weekdays, match.value)
        }
        Regex("每(?:周|星期|礼拜)([1-7,，、和及]+)").find(text)?.let { match ->
            val weekdays = match.groupValues[1].split(Regex("[,，、和及]")).mapNotNull(String::toIntOrNull)
                .filter { it in 1..7 }.distinct().sorted()
            return RecurrenceHit(weekdays, match.value)
        }
        Regex("每天|每日|天天").find(text)?.let { return RecurrenceHit((1..7).toList(), it.value) }
        Regex("工作日|周一到周五|星期一到星期五|周一至周五").find(text)?.let {
            return RecurrenceHit((1..5).toList(), it.value)
        }
        if (text.contains("周末")) return RecurrenceHit(listOf(6, 7), "周末")
        Regex("隔天|隔日|每隔一天|每两天|每月|每个月|每周两次|一周两次|一周三次").find(text)?.let {
            return RecurrenceHit(null, it.value, unsupported = true)
        }
        return null
    }

    private fun applyPeriod(hour: Double, period: String): Double = when (period) {
        "凌晨", "早上", "早晨", "上午" -> hour
        "中午" -> if (hour < 12) hour + 12 else hour
        "下午", "傍晚" -> if (hour < 12) hour + 12 else hour
        "晚上", "夜里", "今晚", "明晚" -> when {
            hour == 12.0 -> 0.0
            hour < 12 -> hour + 12
            else -> hour
        }
        else -> hour
    }

    private data class ClockPart(val minute: Int, val hour: Double)

    private fun parseClockPart(rawHour: String, rawMinute: String, rawPeriod: String): ClockPart? {
        var hour = cnToNumber(rawHour) ?: return null
        if (hour > 23) return null
        var minute = if (rawMinute.isBlank()) 0.0 else cnToNumber(rawMinute) ?: return null
        when (rawMinute) {
            "半" -> minute = 30.0
            "一刻" -> minute = 15.0
            "三刻" -> minute = 45.0
        }
        if (minute > 59) return null
        hour = applyPeriod(hour, rawPeriod)
        return ClockPart((hour * 60 + minute).roundToInt(), hour)
    }

    private data class TimeHit(
        val startMinute: Int,
        val endMinute: Int? = null,
        val durationMinutes: Int? = null,
        val crossMidnight: Boolean = false,
        val span: String,
        val endSpan: String? = null,
        val startAmbiguous: Boolean = false,
    )

    private const val CLOCK = "(\\d{1,2}|[一二两三四五六七八九十]{1,3}|半)"
    private const val MINUTE = "(\\d{1,2}|[一二两三四五六七八九十]{1,3}|半|一刻|三刻)"
    private const val PERIOD = "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明晚)?"

    private fun parseTimes(text: String): TimeHit? {
        val rangePattern = Regex(
            "$PERIOD\\s*$CLOCK\\s*(?:点|时|:)\\s*$MINUTE?分?\\s*(?:到|至|-|—|~|–)\\s*" +
                "$PERIOD\\s*$CLOCK\\s*(?:点|时|:)\\s*$MINUTE?分?",
        )
        rangePattern.find(text)?.let { range ->
            val start = parseClockPart(range.groupValues[2], range.groupValues[3], range.groupValues[1])
            val end = parseClockPart(
                range.groupValues[5], range.groupValues[6],
                range.groupValues[4].ifBlank { range.groupValues[1] },
            )
            if (start != null && end != null) {
                var endMinute = end.minute
                var crossMidnight = false
                if (endMinute <= start.minute) {
                    endMinute += 24 * 60
                    crossMidnight = true
                }
                return TimeHit(
                    startMinute = start.minute,
                    endMinute = endMinute,
                    durationMinutes = endMinute - start.minute,
                    crossMidnight = crossMidnight,
                    span = range.value,
                    startAmbiguous = range.groupValues[1].isBlank() && start.hour <= 6,
                )
            }
        }
        val singlePattern = Regex("$PERIOD\\s*$CLOCK\\s*(?:点|时|:)\\s*$MINUTE?分?")
        val single = singlePattern.find(text) ?: return null
        val clockValue = parseClockPart(single.groupValues[2], single.groupValues[3], single.groupValues[1])
            ?: return null
        var endMinute: Int? = null
        var durationMinutes: Int? = null
        var endSpan: String? = null
        var crossMidnight = false
        val tail = text.substring(single.range.last + 1)
        val continuedPattern = Regex(
            "(?:写到|做到|干到|学到|练到|开到|打到|跑到|看到|读到|背到|玩到|用到|持续到|一直到|直到)\\s*" +
                "$PERIOD\\s*$CLOCK\\s*(?:点|时)\\s*$MINUTE?分?",
        )
        continuedPattern.find(tail)?.let { continued ->
            val end = parseClockPart(
                continued.groupValues[2], continued.groupValues[3],
                continued.groupValues[1].ifBlank { single.groupValues[1] },
            )
            if (end != null) {
                endMinute = end.minute
                if (endMinute <= clockValue.minute) {
                    endMinute += 24 * 60
                    crossMidnight = true
                }
                durationMinutes = endMinute - clockValue.minute
                endSpan = continued.value
            }
        }
        return TimeHit(
            startMinute = clockValue.minute,
            endMinute = endMinute,
            durationMinutes = durationMinutes,
            crossMidnight = crossMidnight,
            span = single.value,
            endSpan = endSpan,
            startAmbiguous = single.groupValues[1].isBlank() && clockValue.hour <= 6,
        )
    }

    private val barePeriods = linkedMapOf(
        "凌晨" to 5 * 60, "早上" to 7 * 60, "早晨" to 7 * 60, "中午" to 12 * 60, "傍晚" to 18 * 60,
        "晚上" to 20 * 60, "夜里" to 22 * 60, "今晚" to 20 * 60, "明晚" to 20 * 60,
    )

    private data class BareTime(val startMinute: Int, val span: String)

    private fun parseBarePeriod(text: String): BareTime? {
        barePeriods.forEach { (word, minute) -> if (text.contains(word)) return BareTime(minute, word) }
        return null
    }

    private fun periodIn(text: String): String? = listOf(
        "凌晨", "早上", "上午", "中午", "下午", "傍晚", "晚上", "夜里", "今晚", "明晚",
    ).firstOrNull(text::contains)

    private data class DurationHit(val minutes: Int?, val days: Int?, val span: String)

    private fun parseDuration(text: String): DurationHit? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:个)?\\s*(?:小时|钟头|h|hr|hours?)", RegexOption.IGNORE_CASE)
            .find(text)?.let { return DurationHit((it.groupValues[1].toDouble() * 60).roundToInt(), null, it.value) }
        Regex("(半|一|两|俩|二|三|四|五|六|七|八|九|十)\\s*个?\\s*半\\s*(?:小时|钟头)")
            .find(text)?.let {
                val base = cnToNumber(it.groupValues[1]) ?: return@let
                return DurationHit(((base + 0.5) * 60).roundToInt(), null, it.value)
            }
        Regex("(半|一|两|俩|二|三|四|五|六|七|八|九|十)\\s*个?\\s*(?:小时|钟头)")
            .find(text)?.let {
                return DurationHit(((cnToNumber(it.groupValues[1]) ?: 0.0) * 60).roundToInt(), null, it.value)
            }
        Regex("(\\d+)\\s*(?:分钟|min|minutes?)", RegexOption.IGNORE_CASE).find(text)?.let {
            return DurationHit(it.groupValues[1].toInt(), null, it.value)
        }
        Regex("([一二两俩三四五六七八九十]{1,3})\\s*(?:分钟|分)(?!之)").find(text)?.let {
            val minutes = if (it.groupValues[1] == "半") 30.0 else cnToNumber(it.groupValues[1]) ?: 0.0
            return DurationHit(minutes.roundToInt(), null, it.value)
        }
        Regex("(半)\\s*(?:分钟|分)").find(text)?.let { return DurationHit(30, null, it.value) }
        Regex("(\\d+)\\s*天").find(text)?.let { return DurationHit(null, it.groupValues[1].toInt(), it.value) }
        return null
    }

    private data class DueHit(val cue: Boolean, val clockBefore: Boolean)

    private fun parseDue(text: String): DueHit? {
        if (!Regex("之前|以前|截止|截至|前把|前要|前交|前完成|前做|点(?:半)?前").containsMatchIn(text)) return null
        return DueHit(cue = true, clockBefore = Regex("点(?:半)?前").containsMatchIn(text))
    }

    private fun stripName(text: String, spans: List<String?>): String {
        var value = text
        spans.filterNotNull().filter(String::isNotBlank).sortedByDescending(String::length)
            .forEach { value = value.replace(it, " ") }
        value = value
            .replace(Regex("^(帮|帮我|麻烦|请|我想|我要|想要|给我|我|能不能|可以|顺便|然后|再|就|先|只|从|开始|得)\\s*"), "")
            .replace(
                Regex(
                    "(帮我|麻烦|谢谢|记一下|就行|一下|大概|大约|差不多|左右|看着办|你看着排|你安排|合适时间|挑个合适时间|看着空档|看着空挡|和合适时间|排成|别排事|别排了|别排|不用排|不要排|晚饭后|早饭后|饭后|起床后|下课后|睡前|考完|吃完|回家后|找个时间|找时间|抽个时间|抽时间|最近|开始|再往后拖两天|再往后拖|往后拖|拖两天|拖几天|持续到|一直到|直到|之前|以前|截止|截至)",
                ),
                " ",
            )
            .replace(Regex("(没做完|没完成|没搞完|只写了一半|写了一半|只做了一半|做了一半|只完成了一半|完成了一半|做到一半|再给我|给我|额外|只给我)"), " ")
            .replace("没", " ")
            .replace(Regex("(今天|今日|明天|明日|后天|大后天|昨天|昨日|下下周|下周|下星期|下礼拜|本周|这周|这星期|这礼拜)"), " ")
            .replace(
                Regex(
                    "(安排|排一下|排|放到|放进|放在|挪到|移到|改到|换到|调到|调整到|提前到|推迟到|顺延到|延到|记一下|记下|记着|先记|只记|取消|完成|做完|写完|搞定|交了|做到|进度|补上|往后推|顺延|推迟|改天)",
                ),
                " ",
            )
            .replace(Regex("(把|的|了|吧|呗|啊|呀|呢|吗|的话|这个|那个|这件事)"), " ")
            .replace(Regex("(写|做|看|背|买|练|读|听|跑|吃|交|开|打|学|记|取|洗|修|改|约|订|寄|发)个"), "$1")
            .replace(Regex("(哦不|不对|说错了|而是|不是)"), " ")
            .replace(Regex("[,\\s]+"), " ")
            .trim()
        value = value.replace(Regex("^(和|从|再|成)+"), "").replace(Regex("(和|成|放|再|前)+$"), "")
        value = value.replace(Regex("^前\\s*"), "")
        value = value.replace(Regex("^[我你\\s]+"), "").replace(Regex("^在\\s*"), "").replace(Regex("^[我你\\s]+"), "")
        if (Regex("^[是为就再和从成放我你\\s]+$").matches(value)) value = ""
        return value.trim(' ', ':')
    }

    private const val BA_VERBS =
        "收拾|整理|写|交|发|送|取|拿|买|洗|修|改|做|背|看|读|练|跑|开|打|准备|完成|处理|提交|打印|寄|还|带|装|挂|订|约|复习"

    private fun baObjectName(text: String): String? {
        val match = Regex("把\\s*([^,，。；;！？!?]{1,16}?)\\s*($BA_VERBS)(?:一下|一遍|了|好|完|出去|过去|上去|下来|回来|给|到)?")
            .find(text) ?: return null
        val obj = match.groupValues[1]
            .replace(Regex("^(明天|今天|后天|这周|下周|上午|下午|晚上|中午|的|我|你)+"), "").trim()
        return if (obj.isNotBlank()) match.groupValues[2] + obj else null
    }

    private fun parseProgress(text: String): Int? {
        Regex("([一二两三四五六七八九十]{1,2})\\s*分之\\s*([一二两三四五六七八九十])").find(text)?.let {
            val denominator = cnToNumber(it.groupValues[1])
            val numerator = cnToNumber(it.groupValues[2])
            if (denominator != null && numerator != null && denominator != 0.0 && numerator <= denominator) {
                return ((numerator / denominator) * 100).roundToInt()
            }
        }
        if (Regex("一半|50%|百分之五十").containsMatchIn(text)) return 50
        return null
    }

    private fun findContextTask(text: String, tasks: List<RuleTask>): RuleTask? {
        val named = tasks.filter { it.name.isNotBlank() && text.contains(it.name) }
            .sortedByDescending { it.name.length }
        if (named.isNotEmpty()) return named.first()
        return tasks.filter { it.name.isNotBlank() && it.name.length >= 3 && it.name.all(text::contains) }
            .sortedByDescending { it.name.length }
            .firstOrNull()
    }

    private fun typeDefaultMinutes(name: String): Int = when {
        Regex("报告|作业|周报|PPT", RegexOption.IGNORE_CASE).containsMatchIn(name) -> 60
        Regex("组会|开会|会议|讨论").containsMatchIn(name) -> 60
        Regex("快递|盖章|教材|买").containsMatchIn(name) -> 30
        Regex("跑|背|练|冥想").containsMatchIn(name) -> 30
        Regex("复习|准备|比赛|答辩").containsMatchIn(name) -> 90
        else -> 60
    }

    // ---- guards and cues ------------------------------------------------

    private val CORRECTION = listOf("不是", "哦不", "不对", "而是", "说错了")
    private val CONDITIONAL = listOf("如果", "要是", "除非", "否则", "万一")
    private val PRONOUN = listOf("那个", "这个", "它", "刚才", "之前那个", "第一个")
    private val ANCHOR = listOf("晚饭后", "早饭后", "起床后", "下课后", "睡前", "考完", "吃完", "回家后")
    private val QUANTITY = listOf("两章", "三章", "一套", "两套", "三套", "两篇", "三篇", "两节", "三节")
    private val TEAM = listOf("队友", "组员", "他们", "她们", "小王", "小李", "老师")
    private val QUERY = listOf(
        "有空吗", "还剩多少", "还有多少", "有多少时间", "今天有什么", "安排是什么", "来得及",
        "先做什么", "该不该", "怎么办", "太累", "不想学", "休息",
    )
    private val SPREAD = listOf("排松", "太紧", "别排太满", "排得太满", "别排那么紧", "留点缓冲", "留半小时", "均匀")

    private val DIRECT_QUERY = Regex(
        "还剩(?:几|多少)|还有(?:什么|哪些|多少)(?:没|要|需要|待)?(?:做|完成|干)?|好累|不想动|" +
            "先(?:做|干|安排|写|学)哪|哪个.{0,3}先|怎么(?:安排|排|规划).{0,4}(?:比较|才)?好|来不来得及|该不该|要不要",
    )
    private val HAS_ACTION_VERB = Regex(
        "安排|排|放到|放进|放在|挪|改到|移到|换到|取消|完成|做完|写完|搞定|顺延|推迟|记一下|记着|先记|只记|补上|没做完|没完成|没搞完|开始",
    )
    private val HAS_PLAN_SIGNAL = Regex("写|开|复习|准备|背|跑|练|取|买|交|做|排|安排|题|报告|作业")
    private val MOVE_CUE = Regex("挪到|移到|改到|换到|调到|调整到|提前到|推迟到|推后到|顺延到|延到")
    private val PLACE_CUE = Regex("放到|放进|放在")
    private val DONE_CUE = Regex(
        "做完|写完|搞定|交了|完成了|已经.*完|吃完(?!饭)|看完|读完|背完|跑完|练完|开完|学完|弄完|干完|搞完|复习完|" +
            "交完|发完|买完|收拾完|整理完|画完|改完|编完|洗完了|准备好了|交上去了|发出去了|考完了",
    )
    private val PROGRESS_CUE = Regex(
        "没做完|没完成|没搞完|没搞定|没写完|没背完|没练完|没看完|没学完|没交|没写|只写了一半|写了一半|做了一半|" +
            "只完成了一半|完成了一半|做到一半|到一半|一半|分之|写了个开头|写个开头|开了个头|只写|只做|只复习|补上|进度",
    )
    private val POSTPONE_CUE = Regex("顺延|往后推|往后拖|再拖|拖几天|拖两天|拖一天|推迟|改天|推后到")
    private val BUSY_CUE = Regex("别排事|别排了|不要排")
    private val CANCEL_CUE = Regex("取消|不做了|别做|算了|不开了|不上了|不去了")
    private val CAPTURE_CUE = Regex("记一下|记着|先记|只记|别排|不用排")
    private val CORRECTION_HEAD = Regex("(?:哦不|不对|说错了)[，,]?\\s*(?:是)?\\s*(.*)$")
    private val CORRECTION_NOT = Regex("不是([^，,]*)[，,]\\s*(?:是)?\\s*(.*)$")
    private val REVIEW_CUE = Regex(
        "没做完|没完成|没搞完|没搞定|没写完|没背完|没练完|没看完|没学完|没交|只写了一半|写了一半|做了一半|" +
            "做到一半|一半|分之|写了个开头|补上",
    )
    private val EXTRA_CUE = Regex("再(?:给我|排|安排|留)?\\s*(?:[一二两三四五六七八九十百\\d]+|半)?\\s*个?\\s*(?:小时|钟头|分钟|分|min)|再排|额外")

    private val QUERY_CLAUSE = Regex("^(?:写|做|干|学|练|玩|跑|背|看|读|改|复习)到")
    private val ACTION_CLAUSE = Regex(
        "安排|排|挪|改到|移到|换到|调到|取消|完成|做完|写完|记一下|记着|复习|写|开|取|买|跑|背|练|准备|交|做|看|读|整理|收拾|打|约|送|发",
    )
    private val MULTI_CUE = Regex("然后|顺便|同时|接着|随后|之后")
    private val ENGLISH_TOKEN = Regex("\\b(PPT|min|h|hr|pm|am)\\b", RegexOption.IGNORE_CASE)

    // ---- parse ----------------------------------------------------------

    fun parse(utterance: String, today: LocalDate, tasks: List<RuleTask> = emptyList()): RulePlan {
        val text = normalize(utterance)
        val reasons = mutableListOf<String>()
        val hardReasons = mutableListOf<String>()
        fun query(kind: RuleQueryKind = RuleQueryKind.TASKS, gap: Int? = null) = RulePlan(
            intent = RuleIntentKind.QUERY, action = null, items = emptyList(), draftItems = emptyList(),
            mustAsk = false, needsAI = false, reasons = emptyList(), confidence = 0.7,
            queryKind = kind, gapMinutes = gap,
        )
        if (text.isBlank()) return RulePlan(
            intent = RuleIntentKind.CLARIFY, action = null, items = emptyList(), draftItems = emptyList(),
            mustAsk = true, needsAI = true, reasons = listOf("empty"), confidence = 0.0,
        )

        if (DIRECT_QUERY.containsMatchIn(text) && !Regex("记一下|记着|提醒我|别忘").containsMatchIn(text)) {
            return query(RuleQueryKind.ADVICE)
        }
        if (SPREAD.any(text::contains)) return query(RuleQueryKind.SPREAD, gap = 30)
        if (Regex("不排事|不想排").containsMatchIn(text) && Regex("休息|放松|歇").containsMatchIn(text)) {
            return query()
        }
        val hasActionVerb = HAS_ACTION_VERB.containsMatchIn(text)
        val hasPlanSignal = HAS_PLAN_SIGNAL.containsMatchIn(text)
        if (!hasActionVerb && !hasPlanSignal && QUERY.any(text::contains)) return query(RuleQueryKind.ADVICE)
        if (!hasActionVerb && !hasPlanSignal && Regex("[?？]|吗$|多少|什么|哪|怎么|为什么").containsMatchIn(text)) {
            return query(RuleQueryKind.ADVICE)
        }

        if (CORRECTION.any(text::contains)) reasons += "correction"
        if (CONDITIONAL.any(text::contains)) {
            reasons += "conditional"; hardReasons += "conditional"
        }
        if (PRONOUN.any(text::contains)) {
            reasons += "pronoun"; hardReasons += "pronoun"
        }
        if (Regex("下周末|下个周末").containsMatchIn(text)) {
            reasons += "ambiguous-weekend"; hardReasons += "ambiguous-weekend"
        }
        if (ANCHOR.any(text::contains)) reasons += "relative-anchor"
        if (QUANTITY.any(text::contains)) reasons += "quantity"
        if (TEAM.any(text::contains)) reasons += "people"
        val englishStripped = ENGLISH_TOKEN.replace(text, "")
        val hasEnglish = Regex("[A-Za-z]{3,}").containsMatchIn(englishStripped)
        if (hasEnglish) reasons += "english"

        val recurrence = parseRecurrence(text)
        if (recurrence?.unsupported == true) {
            reasons += "unsupported-recurrence"; hardReasons += "unsupported-recurrence"
        }

        val correctionHead = CORRECTION_HEAD.find(text)
        val correctionNot = if (correctionHead == null) CORRECTION_NOT.find(text) else null
        val correction = correctionHead ?: correctionNot
        val correctedText = when {
            correctionHead != null -> correctionHead.groupValues[1].ifBlank { null }
            correctionNot != null -> correctionNot.groupValues[2].ifBlank { correctionNot.groupValues[1].ifBlank { null } }
            else -> null
        }
        val correctedSource = when {
            correctionHead != null -> null
            correctionNot != null -> correctionNot.groupValues[1].ifBlank { null }
            else -> null
        }
        val moveTarget = Regex("(?:挪到|移到|改到|换到|调到|调整到|提前到|推迟到|推后到|顺延到|延到)(.*)$")
            .find(text)?.groupValues?.get(1)

        val hasAnchor = ANCHOR.any(text::contains)
        val bareTargetTime = moveTarget?.let(::parseBarePeriod)
        val bareTime = if (!hasAnchor && !Regex("\\d|点|时|:").containsMatchIn(text)) parseBarePeriod(text) else null

        val fallbackDate = parseDate(text, today)
        val fallbackTime = parseTimes(text)
        val targetDate = moveTarget?.let { parseDate(it, today) }
        val targetTime: TimeHit? = moveTarget?.let { target ->
            parseTimes(target) ?: bareTargetTime?.let { TimeHit(it.startMinute, span = it.span) }
        }
        val correctedDate = correctedText?.let { parseDate(it, today) }
        var correctedTime = correctedText?.let(::parseTimes)
        if (correctedTime?.startAmbiguous == true && fallbackTime != null && fallbackTime.startMinute >= 12 * 60) {
            correctedTime = correctedTime.copy(startMinute = correctedTime.startMinute + 12 * 60, startAmbiguous = false)
        }
        val dateHit = correctedDate ?: targetDate ?: fallbackDate
        val timeHit = correctedTime ?: targetTime ?: fallbackTime ?: bareTime?.let {
            TimeHit(it.startMinute, span = it.span)
        }
        if (dateHit?.unsupported == true) {
            reasons += "relative-month"; hardReasons += "relative-month"
        }
        if (timeHit?.crossMidnight == true) {
            reasons += "cross-midnight"; hardReasons += "cross-midnight"
        }
        if (timeHit?.startAmbiguous == true) reasons += "ambiguous-clock"
        val durationHit = parseDuration(text)
        if (durationHit?.days != null) reasons += "multi-day"

        if (text.contains("课") && Regex("取消|改到|挪|停|调课").containsMatchIn(text)) {
            reasons += "fixed-block"; hardReasons += "fixed-block"
        }
        if (Regex("(这周|本周|下周)").containsMatchIn(text) && Regex("(都|全部|所有)").containsMatchIn(text) &&
            Regex("(挪|推|移|调)").containsMatchIn(text)
        ) {
            reasons += "bulk-move"; hardReasons += "bulk-move"
        }
        if (Regex("看着办|随你|随便|你定|都行").containsMatchIn(text)) {
            reasons += "ambiguous"; hardReasons += "ambiguous"
        }
        if (text.contains("排") && Regex("下周|这周|本周").containsMatchIn(text) && dateHit == null && recurrence == null) {
            reasons += "ambiguous"; hardReasons += "ambiguous"
        }
        if (hasEnglish && dateHit == null && recurrence == null) hardReasons += "english-intent"

        if (BUSY_CUE.containsMatchIn(text) && text.contains("事") && timeHit?.endMinute == null) {
            reasons += "busy-window-missing"; hardReasons += "busy-window-missing"
        }
        val contextTask = findContextTask(text, tasks)
        val due = parseDue(text)
        val captureCue = CAPTURE_CUE.containsMatchIn(text)
        val doneCue = DONE_CUE.containsMatchIn(text)
        val progressCue = PROGRESS_CUE.containsMatchIn(text)
        val postponeCue = POSTPONE_CUE.containsMatchIn(text)
        val busyBlock = timeHit?.endMinute != null && BUSY_CUE.containsMatchIn(text)
        val targetHasClock = targetTime?.startMinute != null
        // 「周五前」「三点前」 states a deadline; it must not become a placed block even
        // when the sentence names an existing task (otherwise 三点前 could end after 三点).
        val deadlineOnly = due?.cue == true && (dateHit?.date != null || due.clockBefore)

        var action = RuleAction.CREATE
        when {
            busyBlock -> action = RuleAction.BUSY
            CANCEL_CUE.containsMatchIn(text) -> action = RuleAction.CANCEL
            postponeCue -> action = if (targetHasClock) RuleAction.SCHEDULE else RuleAction.POSTPONE
            progressCue -> action = RuleAction.PROGRESS
            doneCue && due?.cue != true -> action = RuleAction.COMPLETE
            MOVE_CUE.containsMatchIn(text) -> action = RuleAction.SCHEDULE
            PLACE_CUE.containsMatchIn(text) -> action = if (contextTask != null) RuleAction.SCHEDULE else RuleAction.CREATE
            recurrence != null && recurrence.unsupported != true -> action = RuleAction.ROUTINE
            captureCue -> action = RuleAction.CREATE
            deadlineOnly -> action = RuleAction.CREATE
            contextTask != null && (dateHit != null || timeHit != null) -> action = RuleAction.SCHEDULE
        }

        val clauses = text.split(Regex("[，,]")).map(String::trim).filter(String::isNotEmpty)
        val intentClauses = clauses.filterNot(QUERY_CLAUSE::containsMatchIn)
        val clauseSignals = intentClauses.count { clause ->
            parseDate(clause, today) != null || parseTimes(clause) != null || parseBarePeriod(clause) != null
        }
        val actionClauses = intentClauses.count(ACTION_CLAUSE::containsMatchIn)
        if (intentClauses.size >= 2 && actionClauses >= 2 && (MULTI_CUE.containsMatchIn(text) || clauseSignals >= 2)) {
            reasons += "multi-intent"; hardReasons += "multi-intent"
        }

        val spans = listOf(
            fallbackDate?.span, fallbackTime?.span, targetDate?.span, targetTime?.span,
            correctedDate?.span, correctedTime?.span, correctedSource, durationHit?.span,
            recurrence?.span, timeHit?.endSpan, bareTime?.span,
        )
        var name = contextTask?.name ?: baObjectName(text) ?: stripName(text, spans)
        if (action == RuleAction.POSTPONE && contextTask == null &&
            Regex("[一二两三四五六七八九十\\d]+天").matches(name)
        ) {
            name = ""
        }
        // A no-schedule window does not need a name; every other action does.
        if (name.isBlank() && action != RuleAction.BUSY) {
            reasons += "missing-name"; hardReasons += "missing-name"
        }

        val explicitDuration = timeHit?.durationMinutes ?: durationHit?.minutes
        val period = periodIn(text)
        val scheduleDuration = explicitDuration ?: contextTask?.durationMinutes ?: typeDefaultMinutes(name)
        val items = mutableListOf<RuleItem>()
        when (action) {
            RuleAction.BUSY -> items += RuleItem(
                RuleAction.BUSY, name, date = dateHit?.date ?: today,
                startMinute = timeHit?.startMinute, endMinute = timeHit?.endMinute,
                durationMinutes = timeHit?.durationMinutes, weekdays = recurrence?.weekdays,
            )
            RuleAction.ROUTINE -> items += RuleItem(
                RuleAction.ROUTINE, name, date = null,
                startMinute = timeHit?.startMinute, endMinute = timeHit?.endMinute,
                durationMinutes = timeHit?.durationMinutes ?: durationHit?.minutes ?: typeDefaultMinutes(name),
                durationExplicit = explicitDuration != null,
                weekdays = recurrence?.weekdays, period = period,
            )
            RuleAction.SCHEDULE -> items += RuleItem(
                RuleAction.SCHEDULE, name, date = dateHit?.date,
                startMinute = timeHit?.startMinute, endMinute = timeHit?.endMinute,
                durationMinutes = scheduleDuration, durationExplicit = explicitDuration != null,
                move = true, period = period,
            )
            RuleAction.CANCEL, RuleAction.COMPLETE -> {
                val extra = if (action == RuleAction.COMPLETE && Regex("多花|多用|超时").containsMatchIn(text)) {
                    (contextTask?.durationMinutes ?: typeDefaultMinutes(name)) + (durationHit?.minutes ?: 0)
                } else null
                items += RuleItem(action, name, actualMinutes = extra)
            }
            RuleAction.POSTPONE -> {
                var date = dateHit?.date
                val base = contextTask?.plannedDate
                val drag = Regex("(?:再)?(?:往后)?(?:拖|推)([一二两三四五六七八九十\\d]+)天").find(text)
                if (date == null && base != null && drag != null) {
                    cnToNumber(drag.groupValues[1])?.let { date = base.plusDays(it.toLong()) }
                }
                if (date == null && base != null && text.contains("一天")) date = base.plusDays(1)
                items += RuleItem(RuleAction.POSTPONE, name, date = date)
            }
            RuleAction.PROGRESS -> {
                val progress = parseProgress(text)
                items += RuleItem(RuleAction.PROGRESS, name, progress = progress)
                if (dateHit?.date != null) {
                    items += RuleItem(
                        RuleAction.CREATE, name, date = dateHit.date,
                        startMinute = timeHit?.startMinute, period = period,
                        durationMinutes = timeHit?.durationMinutes ?: durationHit?.minutes,
                        durationExplicit = explicitDuration != null,
                    )
                }
            }
            RuleAction.CREATE -> {
                if (due?.cue == true && (dateHit?.date != null || due.clockBefore)) {
                    val dueDate = dateHit?.date ?: today
                    val dueMinute = if (due.clockBefore) timeHit?.startMinute else null
                    items += RuleItem(
                        RuleAction.CREATE, name,
                        date = if (due.clockBefore && dateHit?.date == null) dueDate else null,
                        dueDate = dueDate, dueMinute = dueMinute,
                        durationMinutes = durationHit?.minutes, durationExplicit = durationHit?.minutes != null,
                    )
                } else {
                    items += RuleItem(
                        RuleAction.CREATE, name, date = dateHit?.date,
                        startMinute = timeHit?.startMinute, endMinute = timeHit?.endMinute,
                        durationMinutes = timeHit?.durationMinutes ?: durationHit?.minutes
                            ?: if (captureCue) null else typeDefaultMinutes(name),
                        durationExplicit = explicitDuration != null, period = period,
                    )
                }
            }
        }

        val reviewTurn = action == RuleAction.PROGRESS && REVIEW_CUE.containsMatchIn(text) &&
            (EXTRA_CUE.containsMatchIn(text) || items.size > 1)
        if (reviewTurn && items.size == 1 && durationHit?.minutes != null) {
            items += RuleItem(
                RuleAction.CREATE, name, date = dateHit?.date,
                durationMinutes = durationHit.minutes, durationExplicit = true, period = period,
            )
        }

        val mustAsk = hardReasons.isNotEmpty()
        val hasSchedule = action == RuleAction.CREATE || action == RuleAction.SCHEDULE ||
            action == RuleAction.ROUTINE || action == RuleAction.BUSY
        val confidence = when {
            mustAsk -> 0.2
            reasons.isNotEmpty() -> 0.55
            hasSchedule && (dateHit != null || recurrence != null) && (timeHit != null || action == RuleAction.CREATE) -> 0.95
            else -> 0.75
        }
        val dateExplicit = dateHit?.date != null || timeHit != null || bareTime != null
        return RulePlan(
            intent = when {
                mustAsk -> RuleIntentKind.CLARIFY
                else -> RuleIntentKind.PLAN
            },
            action = action,
            items = if (mustAsk) emptyList() else items,
            draftItems = items,
            mustAsk = mustAsk,
            needsAI = mustAsk || reasons.isNotEmpty(),
            reasons = (hardReasons + reasons).distinct(),
            confidence = confidence,
            contextTaskId = contextTask?.id,
            contextTaskName = contextTask?.name,
            preferredDate = dateHit?.date,
            dateExplicit = dateExplicit,
            recordOnly = captureCue,
            review = reviewTurn && !mustAsk,
            reviewProgress = if (reviewTurn && !mustAsk) items.firstOrNull()?.progress else null,
            queryKind = RuleQueryKind.TASKS,
        )
    }
}
