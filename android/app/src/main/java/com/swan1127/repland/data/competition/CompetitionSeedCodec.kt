package com.swan1127.repland.data.competition

import com.swan1127.repland.data.json.StrictJson
import com.swan1127.repland.domain.competition.CompetitionCatalogEntry
import com.swan1127.repland.domain.competition.CompetitionInstance
import com.swan1127.repland.domain.competition.CreditRules
import com.swan1127.repland.domain.competition.PointsRuleVersion
import com.swan1127.repland.domain.competition.SchoolCompetitionSeed
import com.swan1127.repland.domain.competition.ScholarshipRules
import com.swan1127.repland.domain.competition.TuimianRules

/** 种子 JSON → 域模型。用自带的那份严格解析器：坏种子直接抛错，而不是半读。 */
object CompetitionSeedCodec {

    fun decode(text: String): SchoolCompetitionSeed {
        val root = StrictJson.parseObject(text)
        val rules = root.map("rules")
        return SchoolCompetitionSeed(
            seedVersion = root.i("seedVersion"),
            fetchedAt = root.s("fetchedAt"),
            schoolCode = root.s("schoolCode"),
            schoolName = root.s("schoolName"),
            catalogue = root.list("catalogue").mapNotNull { (it as? Map<*, *>)?.let(::entry) },
            instances = root.list("instances").mapNotNull { (it as? Map<*, *>)?.let(::instance) },
            ruleVersions = rules.list("versions").mapNotNull { (it as? Map<*, *>)?.let(::ruleVersion) },
            caveats = rules.list("caveats").mapNotNull { it as? String },
        )
    }

    private fun entry(m: Map<*, *>) = CompetitionCatalogEntry(
        key = m.s("key"),
        name = m.s("name"),
        category = m.s("category"),
        catalogueYear = m.i("catalogueYear"),
        nationalInCatalog = m.b("nationalInCatalog"),
        nationalOrganizer = m.s("nationalOrganizer"),
        provincialInCatalog = m.b("provincialInCatalog"),
        provincialOrganizer = m.s("provincialOrganizer"),
        college = m.s("college"),
        club = m.s("club"),
        officialSite = m.s("officialSite"),
        siteStatus = m.s("siteStatus"),
        tuimianEligible = m.b("tuimianEligible"),
        englishHalfWeight = m.b("englishHalfWeight"),
        source = m.s("source"),
        sourceDate = m.s("sourceDate"),
    )

    private fun instance(m: Map<*, *>) = CompetitionInstance(
        key = m.s("key"),
        title = m.s("title"),
        level = m.s("level"),
        state = m.s("state"),
        windowStart = m.s("windowStart"),
        windowEnd = m.s("windowEnd"),
        signupGroups = m.i("signupGroups"),
        signupPeople = m.i("signupPeople"),
        platformUrl = m.s("platformUrl"),
        catalogueKey = m.s("catalogueKey"),
    )

    private fun ruleVersion(m: Map<*, *>): PointsRuleVersion {
        val t = m.map("tuimian")
        val s = m.map("scholarship")
        val c = m.map("credit")
        return PointsRuleVersion(
            effectiveFrom = m.i("effectiveFrom"),
            source = m.s("source"),
            sourceDate = m.s("sourceDate"),
            scope = m.s("scope"),
            tuimian = TuimianRules(
                nationalFirst = t.d("nationalFirst"),
                nationalSecond = t.d("nationalSecond"),
                nationalThird = t.d("nationalThird"),
                provincialFirst = t.d("provincialFirst"),
                provincialSecond = t.d("provincialSecond"),
                teamRatios = t.list("teamRatios").mapNotNull { it as? Double },
                englishHalfWeight = t.b("englishHalfWeight"),
                unofficialCatalogDropOneTier = t.b("unofficialCatalogDropOneTier"),
                specialEqualsFirst = t.b("specialEqualsFirst"),
            ),
            scholarship = ScholarshipRules(
                aNationalFirstCny = s.i("aNationalFirstCny"),
                internetPlusFirstCny = s.i("internetPlusFirstCny"),
                bClassWorkloadOnly = s.b("bClassWorkloadOnly"),
            ),
            credit = CreditRules(
                minPerAward = c.d("minPerAward"),
                maxPerAward = c.d("maxPerAward"),
                requiredTotal = c.d("requiredTotal"),
                deadlineNote = c.s("deadlineNote"),
            ),
        )
    }
}

private fun Map<*, *>.s(key: String): String = this[key] as? String ?: ""

private fun Map<*, *>.i(key: String): Int = (this[key] as? Double)?.toInt() ?: 0

private fun Map<*, *>.d(key: String): Double = this[key] as? Double ?: 0.0

private fun Map<*, *>.b(key: String): Boolean = this[key] as? Boolean ?: false

private fun Map<*, *>.map(key: String): Map<*, *> = this[key] as? Map<*, *> ?: emptyMap<Any?, Any?>()

private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any?>()
