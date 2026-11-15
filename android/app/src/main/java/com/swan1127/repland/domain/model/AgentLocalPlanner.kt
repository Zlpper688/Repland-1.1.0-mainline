package com.swan1127.repland.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import org.json.JSONObject

/**
 * 群聊的本地兜底：没有密钥、密钥被拒、模型超时的时候，如果这句话形状确定，就直接把它
 * 排进待确认队列，而不是回一张报错卡了事。
 *
 * 只接 `set_available_time`。它的句式是「哪天 + 哪一段 + 有空还是没空」，可以穷举；
 * 其余都要判断，必须留给模型。这里产出的仍然只是工具调用——准入校验和确认卡一道不少，
 * 本地路径不绕开任何一道门。宁可回退成报错，也不猜出一个用户没说的档。
 */
object AgentLocalPlanner {
    const val TOOL: String = "set_available_time"

    private const val PREFIX = "早上|上午|中午|下午|晚上"
    private const val HOUR = """\d{1,2}|[零一二两三四五六七八九十]{1,3}"""
    private const val MINUTE = """半|\d{1,2}|[零一二两三四五六七八九十]{1,2}"""
    private const val CLOCK = "($PREFIX)?\\s*($HOUR)\\s*(?:点|时|：|:)\\s*($MINUTE)?\\s*分?"

    private val rangePattern = Regex("$CLOCK\\s*(?:到|至|-|—|~|–)\\s*$CLOCK")

    /** 单说一个时刻（不带起止段）；只用来问清「排哪个任务」和承接上一轮的时间。 */
    private val clockPattern = Regex(CLOCK)

    private val dayWordsPattern = Regex(
        "今天|明天|后天|大后天|今晚|今早|今上午|今下午|今晚上|本(?:周|星期|礼拜)[一二三四五六日天]?" +
            "|下(?:周|星期|礼拜)[一二三四五六日天]?|(?:周|星期|礼拜)[一二三四五六日天]",
    )

    private val timeOnlyFillers = Regex(
        "帮我|帮忙|麻烦|请|我想|我要|想要|安排|排(?:到|进|在|个|上|一下)?|放到|放进|放在|开始|的话|左右|吧|呢|啊|呀|在|一下|时候",
    )

    private val nonWordChars = Regex("[\\s，,。.!！?？:：；;、\"“”'‘’]+")

    /** 「每」是重复的唯一凭据：光一个「周六」说的是这周六，不能替用户登记往后每一周。 */
    private val weeklyPattern = Regex("(?:每|每逢|每个)\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天])")
    private val singleDayPattern = Regex(
        "(?:下|本|这)?(?:周|星期|礼拜)[一二三四五六日天]|大后天|后天|明天|今晚|今早|今下午|今天",
    )

    private val blockedWords = listOf("没空", "没时间", "没有时间", "不在", "有事", "别排", "不要排", "占用", "有课", "值班", "开会", "上班", "出差")
    private val freeWords = listOf("有空", "有时间", "方便", "能排", "都可以", "有功夫")

    /** 接不住就返回 null，让调用方照常报云端失败——兜底不该把含糊的话硬编成一个档。 */
    fun plan(utterance: String, today: LocalDate): AgentToolCall? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        val free = when {
            blockedWords.any(text::contains) -> false
            freeWords.any(text::contains) -> true
            else -> return null
        }
        val range = rangePattern.find(text) ?: return null
        val start = clockMinute(range.groupValues[1].ifEmpty { prefixOf(text) }, range.groupValues[2], range.groupValues[3])
            ?: return null
        // 后半句常常省掉「晚上」：不跟着跨到下午去，「晚上七点到九点半」会算出结束早于开始。
        val rawEnd = clockMinute(range.groupValues[4], range.groupValues[5], range.groupValues[6]) ?: return null
        val end = if (rawEnd <= start) rawEnd + 12 * 60 else rawEnd
        if (end > 1_440) return null

        val arguments = JSONObject()
            .put("startMinute", start)
            .put("endMinute", end)
            .put("free", free)
        weeklyPattern.find(text)?.groupValues?.get(1)?.let { weekday ->
            val date = weekdayOf(weekday, today) ?: return null
            arguments.put("date", date.toString()).put("repeatWeekly", true)
        } ?: run {
            arguments.put("date", singleDayPattern.find(text)?.value ?: return null)
        }
        return AgentToolCall(TOOL, arguments.toString())
    }

    /** 句子里第一个「几点」；识别不了返回 null。 */
    fun clockMinuteOf(text: String): Int? {
        val match = clockPattern.find(text) ?: return null
        return clockMinute(
            match.groupValues[1].ifEmpty { prefixOf(text) },
            match.groupValues[2],
            match.groupValues[3],
        )
    }

    /**
     * 「帮我排在今天中午一点」只说了时间。模型不该替用户从任务清单里挑一个，
     * 所以这句话要在进模型之前就变成一句反问。返回 null 表示还说了别的事。
     */
    fun timeOnlyClock(text: String): Int? {
        val minute = clockMinuteOf(text) ?: return null
        val residue = text
            .replace(clockPattern, " ")
            .replace(dayWordsPattern, " ")
            .replace(timeOnlyFillers, " ")
            .replace(nonWordChars, "")
        return minute.takeIf { residue.isEmpty() }
    }

    /** 「今晚」「明晚」里没有「晚上」二字，但说的就是晚上。 */
    private fun prefixOf(text: String): String = when {
        text.contains("晚") -> "晚上"
        text.contains("下午") -> "下午"
        text.contains("中午") -> "中午"
        text.contains("上午") || text.contains("早") -> "上午"
        else -> ""
    }

    private fun clockMinute(prefix: String, hourText: String, minuteText: String): Int? {
        var hour = number(hourText) ?: return null
        val minute = when {
            minuteText.isBlank() || minuteText == "零" -> 0
            minuteText == "半" -> 30
            else -> number(minuteText) ?: return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        if (prefix in setOf("下午", "晚上") && hour in 1..11) hour += 12
        if (prefix == "中午" && hour in 1..10) hour += 12
        return hour * 60 + minute
    }

    /** 语音和手打都会冒出「七点」「二十分」；只认阿拉伯数字的解析器接不住中文数字。 */
    private fun number(text: String): Int? {
        val token = text.trim()
        if (token.isEmpty()) return null
        token.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        val ten = token.indexOf('十')
        return when {
            token == "十" -> 10
            token.length == 1 -> digits[token[0]]
            ten == 0 -> digits[token[1]]?.plus(10)
            ten == token.length - 1 -> digits[token[0]]?.times(10)
            ten > 0 -> digits[token[0]]?.times(10)?.plus(digits[token[1]] ?: return null)
            else -> null
        }
    }

    private fun weekdayOf(raw: String, today: LocalDate): LocalDate? {
        val index = "一二三四五六日".indexOf(raw.replace("天", "日"))
        if (index < 0) return null
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(index + 1)))
    }
}
