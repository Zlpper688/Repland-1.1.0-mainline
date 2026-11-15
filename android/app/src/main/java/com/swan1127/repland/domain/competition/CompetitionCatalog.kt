package com.swan1127.repland.domain.competition

import java.time.LocalDate

/** 加分目录里的一条赛事：按学校种子版本固化，跨届共享；字段与种子 JSON 一一对应。 */
data class CompetitionCatalogEntry(
    val key: String,
    val name: String,
    val category: String,
    val catalogueYear: Int,
    val nationalInCatalog: Boolean,
    val nationalOrganizer: String,
    val provincialInCatalog: Boolean,
    val provincialOrganizer: String,
    val college: String,
    val club: String,
    val officialSite: String,
    val siteStatus: String,
    val tuimianEligible: Boolean,
    val englishHalfWeight: Boolean,
    val source: String,
    val sourceDate: String,
)

/** 平台上的一场具体赛事（届次实例）。窗口与状态是抓取快照，之后允许用户本地覆盖。 */
data class CompetitionInstance(
    val key: String,
    val title: String,
    val level: String,
    val state: String,
    val windowStart: String,
    val windowEnd: String,
    val signupGroups: Int,
    val signupPeople: Int,
    val platformUrl: String,
    val catalogueKey: String,
) {
    val parsedStart: LocalDate? get() = parseDate(windowStart)
    val parsedEnd: LocalDate? get() = parseDate(windowEnd)

    /** 窗口日期优先于平台状态：平台还挂着「比赛中」但窗口已过的按已结束看。 */
    fun phase(today: LocalDate): InstancePhase {
        val start = parsedStart
        val end = parsedEnd
        if (start != null && today.isBefore(start)) return InstancePhase.UPCOMING
        if (end != null && today.isAfter(end)) return InstancePhase.ENDED
        return when (state) {
            "比赛中" -> InstancePhase.SIGNUP_OPEN
            "未开始" -> InstancePhase.UPCOMING
            "已结束" -> InstancePhase.ENDED
            else -> InstancePhase.UNKNOWN
        }
    }

    val levelRank: Int
        get() = when (level) {
            "国家级" -> 3
            "省级" -> 2
            "市级" -> 1
            else -> 0
        }
}

enum class InstancePhase { SIGNUP_OPEN, UPCOMING, ENDED, UNKNOWN }

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

/** 一套加分口径：按生效年度版本化，学校政策改变时追加新版本而不改旧版。 */
data class PointsRuleVersion(
    val effectiveFrom: Int,
    val source: String,
    val sourceDate: String,
    val scope: String,
    val tuimian: TuimianRules,
    val scholarship: ScholarshipRules,
    val credit: CreditRules,
)

data class TuimianRules(
    val nationalFirst: Double,
    val nationalSecond: Double,
    val nationalThird: Double,
    val provincialFirst: Double,
    val provincialSecond: Double,
    val teamRatios: List<Double>,
    val englishHalfWeight: Boolean,
    val unofficialCatalogDropOneTier: Boolean,
    val specialEqualsFirst: Boolean,
)

data class ScholarshipRules(
    val aNationalFirstCny: Int,
    val internetPlusFirstCny: Int,
    val bClassWorkloadOnly: Boolean,
)

data class CreditRules(
    val minPerAward: Double,
    val maxPerAward: Double,
    val requiredTotal: Double,
    val deadlineNote: String,
)

/** 一所学校的种子：内置竞赛库的最小完整单元，换学校 = 换种子文件。 */
data class SchoolCompetitionSeed(
    val seedVersion: Int,
    val fetchedAt: String,
    val schoolCode: String,
    val schoolName: String,
    val catalogue: List<CompetitionCatalogEntry>,
    val instances: List<CompetitionInstance>,
    val ruleVersions: List<PointsRuleVersion>,
    val caveats: List<String>,
) {
    private val entryIndex = catalogue.associateBy { it.key }
    private val instanceIndex = instances.associateBy { it.key }

    fun entry(key: String): CompetitionCatalogEntry? = entryIndex[key]

    fun instance(key: String): CompetitionInstance? = instanceIndex[key]

    /** 口径按获奖年度版本化：取 effectiveFrom ≤ 年份里最新的那版。 */
    fun ruleVersionFor(awardYear: Int): PointsRuleVersion? =
        ruleVersions.filter { it.effectiveFrom <= awardYear }.maxByOrNull { it.effectiveFrom }

    fun ongoing(today: LocalDate): List<CompetitionInstance> =
        instances.filter { it.phase(today) == InstancePhase.SIGNUP_OPEN }
            .sortedWith(compareBy({ it.parsedEnd ?: LocalDate.MAX }, { -it.levelRank }))

    fun upcoming(today: LocalDate): List<CompetitionInstance> =
        instances.filter { it.phase(today) == InstancePhase.UPCOMING }
            .sortedWith(compareBy({ it.parsedStart ?: LocalDate.MAX }, { -it.levelRank }))

    fun searchCatalogue(raw: String, limit: Int = 20): List<CompetitionCatalogEntry> {
        val query = normalize(raw)
        if (query.isEmpty()) return emptyList()
        return catalogue.filter { normalize(it.name).contains(query) }
            .sortedWith(compareBy({ normalize(it.name).indexOf(query) }, { it.name.length }))
            .take(limit)
    }

    companion object {
        fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
    }
}
