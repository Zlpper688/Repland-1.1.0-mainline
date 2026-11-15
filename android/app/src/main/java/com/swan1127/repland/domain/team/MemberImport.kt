package com.swan1127.repland.domain.team

import java.time.DayOfWeek

/**
 * 批量邀请文本：一行一人，`姓名 | 角色 | 负责说明 | 空闲时段`，后三列可省略。
 * 同时兼容 Tab / 空格分隔；`#`、`//` 开头的行视为注释。
 */
data class ImportedMember(
    val name: String,
    val roleTag: String? = null,
    val responsibility: String = "",
    val availability: Map<DayOfWeek, List<FreeWindow>> = emptyMap(),
)

data class MemberImportResult(
    val members: List<ImportedMember>,
    val skippedLines: List<Int>,
) {
    val skippedCount: Int get() = skippedLines.size
}

object MemberImport {
    private val dayTime = Regex(
        "^(?:周|星期|礼拜)([一二三四五六日天1-7])(\\d{1,2})[:：](\\d{2})[-–—~至](\\d{1,2})[:：](\\d{2})$",
    )
    private val headerNames = setOf("姓名", "名字", "队员", "成员", "队员姓名")

    fun parse(text: String): MemberImportResult {
        val members = mutableListOf<ImportedMember>()
        val skipped = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val columns = splitColumns(line)
            val name = columns.firstOrNull()?.trim().orEmpty()
            if (name.isEmpty() || isHeader(columns)) {
                skipped += index + 1
                return@forEachIndexed
            }
            if (!seen.add(name)) {
                skipped += index + 1
                return@forEachIndexed
            }
            members += ImportedMember(
                name = name,
                roleTag = columns.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
                responsibility = columns.getOrNull(2)?.trim().orEmpty(),
                availability = parseAvailability(columns.getOrNull(3).orEmpty()),
            )
        }
        return MemberImportResult(members, skipped)
    }

    private fun splitColumns(line: String): List<String> = when {
        line.contains('|') || line.contains('｜') -> line.split(Regex("[|｜]")).map { it.trim() }
        line.contains('\t') -> line.split('\t').map { it.trim() }
        else -> line.split(Regex("\\s+"), limit = 3)
    }

    private fun isHeader(columns: List<String>): Boolean {
        val name = columns.firstOrNull().orEmpty()
        if (name !in headerNames) return false
        val rest = columns.drop(1)
        return rest.isEmpty() || rest.any { column -> listOf("角色", "负责", "空闲", "时间").any(column::contains) }
    }

    /** `周一19:00-22:00；周三 20:00-21:30` → 按星期分组；填不出来就留空走低置信度保守排期。 */
    fun parseAvailability(raw: String): Map<DayOfWeek, List<FreeWindow>> {
        if (raw.isBlank() || raw.trim() in setOf("无", "未填", "-")) return emptyMap()
        val byDay = linkedMapOf<DayOfWeek, MutableList<FreeWindow>>()
        raw.split(Regex("[；;、/]|，|,")).forEach { chunk ->
            val token = chunk.trim().replace(Regex("\\s+"), "")
            val match = dayTime.matchEntire(token) ?: return@forEach
            val day = dayOf(match.groupValues[1]) ?: return@forEach
            val startHour = match.groupValues[2].toInt()
            val startMinute = match.groupValues[3].toInt()
            val endHour = match.groupValues[4].toInt()
            val endMinute = match.groupValues[5].toInt()
            if (startMinute > 59 || endMinute > 59) return@forEach
            val start = startHour * 60 + startMinute
            val end = endHour * 60 + endMinute
            if (start !in 0..1439 || end !in 1..1440 || end <= start) return@forEach
            byDay.getOrPut(day) { mutableListOf() } += FreeWindow(start, end)
        }
        return byDay.mapValues { (_, windows) -> windows.sortedBy { it.startMinute } }
    }

    private fun dayOf(token: String): DayOfWeek? = when (token) {
        "一", "1" -> DayOfWeek.MONDAY
        "二", "2" -> DayOfWeek.TUESDAY
        "三", "3" -> DayOfWeek.WEDNESDAY
        "四", "4" -> DayOfWeek.THURSDAY
        "五", "5" -> DayOfWeek.FRIDAY
        "六", "6" -> DayOfWeek.SATURDAY
        "日", "天", "7" -> DayOfWeek.SUNDAY
        else -> null
    }
}
