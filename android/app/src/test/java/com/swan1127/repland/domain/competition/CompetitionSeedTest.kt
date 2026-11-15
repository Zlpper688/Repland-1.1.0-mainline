package com.swan1127.repland.domain.competition

import com.swan1127.repland.data.competition.CompetitionSeedCodec
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 竞赛库：种子解码、阶段判定、按获奖年度选口径、加分规则。 */
class CompetitionSeedTest {

    private val today = LocalDate.of(2026, 11, 9)

    private val fixture = """
        {
        "seedVersion":1,"fetchedAt":"2026-11-09","schoolCode":"SAU","schoolName":"沈阳航空航天大学",
        "catalogue":[
        {"key":"c23-A-1","name":"中国国际大学生创新大赛","category":"A","catalogueYear":2023,"nationalInCatalog":true,"nationalOrganizer":"教育部","provincialInCatalog":true,"provincialOrganizer":"辽宁省教育厅","college":"计算机学院","club":"创意IT俱乐部","officialSite":"https://cy.ncss.cn","siteStatus":"verified_http200","tuimianEligible":true,"englishHalfWeight":false,"source":"fae","sourceDate":"2025-09-03"},
        {"key":"c22-9","name":"全国大学生英语竞赛","category":"B","catalogueYear":2022,"nationalInCatalog":true,"nationalOrganizer":"高校外语教学研究会","provincialInCatalog":true,"provincialOrganizer":"","college":"外国语学院","club":"","officialSite":"","siteStatus":"待核","tuimianEligible":true,"englishHalfWeight":true,"source":"xlsx","sourceDate":"2022-06-02"}],
        "instances":[
        {"key":"i-open","title":"创新大赛校赛","level":"校级","state":"比赛中","windowStart":"2026-06-01","windowEnd":"2099-12-31","signupGroups":5,"signupPeople":12,"platformUrl":"https://cxcy.sau.edu.cn/comp/info?id=i-open","catalogueKey":"c23-A-1"},
        {"key":"i-upcoming","title":"某省赛","level":"省级","state":"未开始","windowStart":"2099-01-01","windowEnd":"2099-02-01","signupGroups":0,"signupPeople":0,"platformUrl":"","catalogueKey":""},
        {"key":"i-ended","title":"已结束赛","level":"省级","state":"比赛中","windowStart":"2026-01-01","windowEnd":"2026-02-01","signupGroups":0,"signupPeople":0,"platformUrl":"","catalogueKey":""}],
        "rules":{"versions":[
        {"effectiveFrom":2023,"source":"fae","sourceDate":"2025-09-03","scope":"航空宇航学院推免细则（院级）","tuimian":{"nationalFirst":20,"nationalSecond":12,"nationalThird":5,"provincialFirst":15,"provincialSecond":10,"teamRatios":[0.85,0.65,0.4],"englishHalfWeight":true,"unofficialCatalogDropOneTier":true,"specialEqualsFirst":true},"scholarship":{"aNationalFirstCny":3000,"internetPlusFirstCny":10000,"bClassWorkloadOnly":true},"credit":{"minPerAward":0.5,"maxPerAward":3,"requiredTotal":3,"deadlineNote":"第七学期期末"}},
        {"effectiveFrom":2024,"source":"fae2","sourceDate":"2025-09-03","scope":"材料学院推免细则（院级）","tuimian":{"nationalFirst":18,"nationalSecond":10,"nationalThird":4,"provincialFirst":14,"provincialSecond":9,"teamRatios":[0.8,0.6,0.4],"englishHalfWeight":true,"unofficialCatalogDropOneTier":true,"specialEqualsFirst":true},"scholarship":{"aNationalFirstCny":3000,"internetPlusFirstCny":10000,"bClassWorkloadOnly":true},"credit":{"minPerAward":0.5,"maxPerAward":3,"requiredTotal":3,"deadlineNote":"第七学期期末"}}],
        "caveats":["校级统一办法原文未公开"]}
        }
    """.trimIndent()

    private fun seed(): SchoolCompetitionSeed = CompetitionSeedCodec.decode(fixture)

    private fun input(
        year: Int = 2023,
        level: AwardLevel = AwardLevel.NATIONAL,
        grade: AwardGrade = AwardGrade.FIRST,
        isTeam: Boolean = false,
        teamRank: Int = 1,
        english: Boolean = false,
        category: String = "A",
        nationalIn: Boolean = true,
        provincialIn: Boolean = true,
    ) = AwardInput(year, level, grade, isTeam, teamRank, english, category, nationalIn, provincialIn)

    @Test
    fun `codec decodes every field of the seed`() {
        val s = seed()
        assertEquals(1, s.seedVersion)
        assertEquals("SAU", s.schoolCode)
        assertEquals("沈阳航空航天大学", s.schoolName)
        assertEquals(2, s.catalogue.size)
        assertEquals(3, s.instances.size)
        assertEquals(2, s.ruleVersions.size)
        assertEquals(listOf("校级统一办法原文未公开"), s.caveats)
        val e = s.entry("c23-A-1")!!
        assertEquals("中国国际大学生创新大赛", e.name)
        assertEquals("A", e.category)
        assertTrue(e.nationalInCatalog)
        assertEquals("教育部", e.nationalOrganizer)
        assertEquals("https://cy.ncss.cn", e.officialSite)
        assertEquals("verified_http200", e.siteStatus)
        assertTrue(e.tuimianEligible)
        val english = s.entry("c22-9")!!
        assertTrue(english.englishHalfWeight)
        assertEquals("待核", english.siteStatus)
        val i = s.instance("i-open")!!
        assertEquals("校级", i.level)
        assertEquals("比赛中", i.state)
        assertEquals(LocalDate.of(2026, 6, 1), i.parsedStart)
        assertEquals("c23-A-1", i.catalogueKey)
        assertEquals(5, i.signupGroups)
    }

    @Test
    fun `phase follows windows before platform state`() {
        val s = seed()
        assertEquals(InstancePhase.SIGNUP_OPEN, s.instance("i-open")!!.phase(today))
        assertEquals(InstancePhase.UPCOMING, s.instance("i-upcoming")!!.phase(today))
        assertEquals(InstancePhase.ENDED, s.instance("i-ended")!!.phase(today))
        assertEquals(listOf("i-open"), s.ongoing(today).map { it.key })
        assertEquals(listOf("i-upcoming"), s.upcoming(today).map { it.key })
    }

    @Test
    fun `rule version is picked by award year`() {
        val s = seed()
        assertEquals(2023, s.ruleVersionFor(2023)!!.effectiveFrom)
        assertEquals(2024, s.ruleVersionFor(2024)!!.effectiveFrom)
        assertEquals(2024, s.ruleVersionFor(2026)!!.effectiveFrom)
        assertNull(s.ruleVersionFor(2021))
    }

    @Test
    fun `calculator applies tiers english team and catalogue rules`() {
        val s = seed()
        assertEquals(20.0, PointsCalculator.preview(input(), s).tuimianPoints, 1e-9)
        assertEquals(13.0, PointsCalculator.preview(input(isTeam = true, teamRank = 2), s).tuimianPoints, 1e-9)
        assertEquals(10.0, PointsCalculator.preview(input(english = true), s).tuimianPoints, 1e-9)
        val special = PointsCalculator.preview(input(grade = AwardGrade.SPECIAL), s)
        assertEquals(20.0, special.tuimianPoints, 1e-9)
        assertEquals("特等奖按一等奖计", special.notes.first())
        assertEquals(12.0, PointsCalculator.preview(input(nationalIn = false), s).tuimianPoints, 1e-9)
        val provThird = PointsCalculator.preview(input(level = AwardLevel.PROVINCIAL, grade = AwardGrade.THIRD), s)
        assertEquals(0.0, provThird.tuimianPoints, 1e-9)
        val school = PointsCalculator.preview(input(level = AwardLevel.SCHOOL), s)
        assertEquals(0.0, school.tuimianPoints, 1e-9)
        assertEquals(0.0, PointsCalculator.preview(input(isTeam = true, teamRank = 5), s).tuimianPoints, 1e-9)
        assertEquals(18.0, PointsCalculator.preview(input(year = 2024), s).tuimianPoints, 1e-9)
        val ancient = PointsCalculator.preview(input(year = 2021), s)
        assertEquals(0.0, ancient.tuimianPoints, 1e-9)
        assertTrue(ancient.notes.first().contains("2021"))
        assertEquals(0.5, PointsCalculator.preview(input(), s).creditMin, 1e-9)
        assertEquals(3.0, PointsCalculator.preview(input(), s).creditMax, 1e-9)
        assertTrue(PointsCalculator.preview(input(category = "B"), s).scholarshipNote.contains("工作量"))
        assertTrue(PointsCalculator.preview(input(), s).scholarshipNote.contains("3000"))
        assertTrue(PointsCalculator.preview(input(), s).notes.first().contains("口径"))
    }

    @Test
    fun `search matches normalized names`() {
        val s = seed()
        assertEquals(listOf("c23-A-1"), s.searchCatalogue("创新大赛").map { it.key })
        assertTrue(s.searchCatalogue("英语").map { it.key }.contains("c22-9"))
        assertTrue(s.searchCatalogue("   ").isEmpty())
    }

    @Test
    fun `bundled sau seed decodes with full counts`() {
        val file = File("src/main/assets/competitions/sau-catalogue.json")
        assumeTrue(file.isFile)
        val s = CompetitionSeedCodec.decode(file.readText(Charsets.UTF_8))
        assertEquals("SAU", s.schoolCode)
        assertEquals(194, s.catalogue.size)
        assertEquals(1561, s.instances.size)
        assertEquals(1, s.ruleVersions.size)
        assertNotNull(s.entry("c23-A-1"))
        assertTrue(s.entry("c23-A-1")!!.name.contains("ACM"))
        assertEquals("c23-A-8", s.instance("MzA0OS1lOGQzNTU")!!.catalogueKey)
        assertNotNull(s.ruleVersionFor(2026))
        assertTrue(s.searchCatalogue("ACM").isNotEmpty())
        assertTrue(s.ongoing(LocalDate.of(2026, 11, 9)).isNotEmpty())
    }
}
