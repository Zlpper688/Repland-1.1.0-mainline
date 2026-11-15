package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.data.competition.CompetitionSeedCodec
import com.swan1127.repland.domain.competition.AwardGrade
import com.swan1127.repland.domain.competition.AwardLevel
import com.swan1127.repland.domain.competition.CompetitionAward
import com.swan1127.repland.domain.competition.CompetitionTrackStatus
import com.swan1127.repland.domain.competition.CompetitionTracking
import com.swan1127.repland.domain.competition.CompetitionWebFetcher
import com.swan1127.repland.domain.competition.CompetitionWebResult
import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentToolResult
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.TeamCollabState
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竞赛库工具面：查比赛/我的截止（读）与关注/登记获奖（写入选确认），数据来自种子快照。 */
class TeamToolboxCompetitionTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9)

    private val fixture = """
        {
        "seedVersion":1,"fetchedAt":"2026-11-09","schoolCode":"SAU","schoolName":"沈阳航空航天大学",
        "catalogue":[
        {"key":"c23-A-1","name":"中国国际大学生创新大赛","category":"A","catalogueYear":2023,"nationalInCatalog":true,"nationalOrganizer":"教育部","provincialInCatalog":true,"provincialOrganizer":"辽宁省教育厅","college":"计算机学院","club":"创意IT俱乐部","officialSite":"https://cy.ncss.cn","siteStatus":"verified_http200","tuimianEligible":true,"englishHalfWeight":false,"source":"fae","sourceDate":"2025-09-03"},
        {"key":"c22-9","name":"全国大学生英语竞赛","category":"B","catalogueYear":2022,"nationalInCatalog":true,"nationalOrganizer":"高校外语教学研究会","provincialInCatalog":true,"provincialOrganizer":"","college":"外国语学院","club":"","officialSite":"","siteStatus":"待核","tuimianEligible":true,"englishHalfWeight":true,"source":"xlsx","sourceDate":"2022-06-02"}],
        "instances":[
        {"key":"i-open","title":"创新大赛校赛","level":"校级","state":"比赛中","windowStart":"2026-06-01","windowEnd":"2099-12-31","signupGroups":5,"signupPeople":12,"platformUrl":"https://cxcy.sau.edu.cn/comp/info?id=i-open","catalogueKey":"c23-A-1"},
        {"key":"i-upcoming","title":"某省赛","level":"省级","state":"未开始","windowStart":"2099-01-01","windowEnd":"2099-02-01","signupGroups":0,"signupPeople":0,"platformUrl":"","catalogueKey":""},
        {"key":"i-english","title":"英语竞赛校赛","level":"校级","state":"比赛中","windowStart":"2026-10-01","windowEnd":"2099-06-30","signupGroups":2,"signupPeople":4,"platformUrl":"","catalogueKey":"c22-9"}],
        "rules":{"versions":[
        {"effectiveFrom":2023,"source":"fae","sourceDate":"2025-09-03","scope":"航空宇航学院推免细则（院级）","tuimian":{"nationalFirst":20,"nationalSecond":12,"nationalThird":5,"provincialFirst":15,"provincialSecond":10,"teamRatios":[0.85,0.65,0.4],"englishHalfWeight":true,"unofficialCatalogDropOneTier":true,"specialEqualsFirst":true},"scholarship":{"aNationalFirstCny":3000,"internetPlusFirstCny":10000,"bClassWorkloadOnly":true},"credit":{"minPerAward":0.5,"maxPerAward":3,"requiredTotal":3,"deadlineNote":"第七学期期末"}},
        {"effectiveFrom":2024,"source":"fae2","sourceDate":"2025-09-03","scope":"材料学院推免细则（院级）","tuimian":{"nationalFirst":18,"nationalSecond":10,"nationalThird":4,"provincialFirst":14,"provincialSecond":9,"teamRatios":[0.8,0.6,0.4],"englishHalfWeight":true,"unofficialCatalogDropOneTier":true,"specialEqualsFirst":true},"scholarship":{"aNationalFirstCny":3000,"internetPlusFirstCny":10000,"bClassWorkloadOnly":true},"credit":{"minPerAward":0.5,"maxPerAward":3,"requiredTotal":3,"deadlineNote":"第七学期期末"}}],
        "caveats":["校级统一办法原文未公开"]}
        }
    """.trimIndent()

    private fun seed() = CompetitionSeedCodec.decode(fixture)

    private fun team(): TeamCollabState = TeamCollabState.create(
        teamId = "t1",
        teamName = "演示队",
        competitionDeadline = LocalDate.of(2026, 12, 20),
        captain = MemberCard("m-cap", "阿岚", roleTag = "队长"),
        now = { LocalDate.of(2026, 11, 9).atTime(9, 0) },
    )

    private fun trackingRow(key: String, deadline: LocalDate?) = CompetitionTracking(
        instanceKey = key,
        title = "追踪·$key",
        level = "省级",
        catalogueKey = "",
        status = CompetitionTrackStatus.WATCHING,
        deadlineEpochDay = deadline?.toEpochDay(),
        remindEnabled = true,
        note = "",
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    private fun toolbox(
        tracking: List<CompetitionTracking> = emptyList(),
        followed: MutableList<String> = mutableListOf(),
        registered: MutableList<String> = mutableListOf(),
        awards: List<CompetitionAward> = emptyList(),
        fetcher: CompetitionWebFetcher? = null,
    ): TeamToolbox = TeamToolbox(
        team(),
        "m-cap",
        today,
        competitionSeed = seed(),
        competitionTracking = tracking,
        followCompetition = { instance -> followed += instance.key },
        registerAward = { award -> registered += award.title },
        competitionAwards = awards,
        competitionWebFetcher = fetcher,
    )

    private fun TeamToolbox.read(name: String, args: JSONObject = JSONObject()): AgentToolResult =
        runBlocking { runRead(name, args) }

    private fun TeamToolbox.validate(tool: String, args: JSONObject): String? =
        specs().first { it.name == tool }.validate!!(args)

    private fun action(tool: String, args: String) = AgentAction(
        id = "a1",
        toolName = tool,
        label = tool,
        summary = "",
        argumentsJson = args,
    )

    @Test
    fun `search lists instances with keys and points header`() {
        val text = toolbox().read("competition_search").summary
        assertTrue(text, text.contains("沈阳航空航天大学"))
        assertTrue(text, text.contains("国一 18"))
        assertTrue(text, text.contains("[i-open]「创新大赛校赛」"))
        assertTrue(text, text.contains("[i-upcoming]「某省赛」"))
    }

    @Test
    fun `search by query falls back to catalogue`() {
        val text = toolbox().read("competition_search", JSONObject().put("query", "大学生英语")).summary
        assertTrue(text, text.contains("全国大学生英语竞赛"))
        assertTrue(text, text.contains("B类"))
        assertTrue(text, text.contains("c22-9"))
    }

    @Test
    fun `deadlines show tracking with days left`() {
        val box = toolbox(tracking = listOf(trackingRow("i-open", LocalDate.of(2026, 11, 15))))
        val text = box.read("competition_deadlines").summary
        assertTrue(text, text.contains("我关注的比赛（1）"))
        assertTrue(text, text.contains("剩 6 天"))
        assertTrue(text, text.contains("提醒开"))
        assertTrue(text, !text.contains("[i-open]「创新大赛校赛」"))
    }

    @Test
    fun `follow validates unknown instance and confirms known`() {
        val box = toolbox()
        assertNotNull(box.validate("follow_competition", JSONObject().put("instanceKey", "不存在的比赛")))
        assertNull(box.validate("follow_competition", JSONObject().put("instanceKey", "i-open")))
        val followed = mutableListOf<String>()
        val box2 = toolbox(followed = followed)
        val result = runBlocking { box2.runWrite(action("follow_competition", """{"instanceKey":"i-open"}""")) }
        assertTrue(result.ok)
        assertEquals(listOf("i-open"), followed)
        assertTrue(result.summary, result.summary.contains("已关注「创新大赛校赛」"))
    }

    @Test
    fun `follow rejects already tracked`() {
        val box = toolbox(tracking = listOf(trackingRow("i-open", null)))
        val error = box.validate("follow_competition", JSONObject().put("instanceKey", "i-open"))
        assertNotNull(error)
        assertTrue(error!!, error.contains("已经在你的追踪里"))
    }

    @Test
    fun `register award validates and lands in history`() {
        val box = toolbox()
        assertNotNull(box.validate("register_award", JSONObject().put("title", "")))
        assertNotNull(box.validate("register_award", JSONObject().put("title", "x").put("level", "宇宙级")))
        val registered = mutableListOf<String>()
        val box2 = toolbox(registered = registered)
        val result = runBlocking {
            box2.runWrite(
                action(
                    "register_award",
                    """{"title":"数学建模国一","level":"国家级","grade":"一等","awardYear":2024}""",
                ),
            )
        }
        assertTrue(result.ok)
        assertEquals(listOf("数学建模国一"), registered)
        assertTrue(result.summary, result.summary.contains("推免参考 18 分"))
        assertTrue(result.summary, result.summary.contains("未关联目录赛事"))
    }

    @Test
    fun `register award with instance anchor applies english half weight`() {
        val registered = mutableListOf<String>()
        val box = toolbox(registered = registered)
        val result = runBlocking {
            box.runWrite(
                action(
                    "register_award",
                    """{"title":"英语国一","level":"国家级","grade":"一等","awardYear":2024,"instanceKey":"i-english"}""",
                ),
            )
        }
        assertTrue(result.ok)
        assertTrue(result.summary, result.summary.contains("推免参考 9 分"))
        assertTrue(result.summary, result.summary.contains("外语类竞赛按 50% 计分"))
    }

    // ---------- 两层资讯：内部知识检索 + 官网抓取 ----------

    private class FakeFetcher(private val ok: Boolean = true) : CompetitionWebFetcher {
        var lastUrl: String? = null
        override suspend fun fetch(url: String): CompetitionWebResult {
            lastUrl = url
            return CompetitionWebResult(
                ok = ok,
                url = url,
                finalUrl = url,
                statusCode = if (ok) 200 else 503,
                fetchedAt = "2026-11-09 12:00",
                title = "通知标题",
                text = if (ok) "报名通知正文：11月30日截止" else "",
                error = if (ok) "" else "HTTP 503：页面打不开（可能已改版或下线）",
            )
        }
    }

    private fun award() = CompetitionAward(
        awardId = "aw1",
        instanceKey = "i-open",
        title = "创新大赛校赛",
        catalogueKey = "c23-A-1",
        level = AwardLevel.NATIONAL,
        grade = AwardGrade.FIRST,
        awardYear = 2026,
        isTeam = true,
        teamRank = 2,
        teammates = "小舟、阿岚",
        note = "",
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    @Test
    fun `knowledge renders rules catalogue tracking and awards with sources`() {
        val box = toolbox(
            tracking = listOf(trackingRow("i-open", LocalDate.of(2026, 11, 15))),
            awards = listOf(award()),
        )
        val text = box.read("competition_knowledge").summary
        assertTrue(text, text.contains("【加分口径版本】"))
        assertTrue(text, text.contains("2024 年起的口径（来源：fae2 · 2025-09-03 · 口径范围：材料学院推免细则（院级））"))
        assertTrue(text, text.contains("国一 18"))
        assertTrue(text, text.contains("【加分目录】"))
        assertTrue(text, text.contains("中国国际大学生创新大赛"))
        assertTrue(text, text.contains("【我的比赛追踪】"))
        assertTrue(text, text.contains("【我的获奖履历】"))
        assertTrue(text, text.contains("2026 · 国家级一等「创新大赛校赛」团队（第 2 队）"))
        assertTrue(text, text.contains("内部知识快照"))
        assertTrue(text, text.contains("competition_web"))
    }

    @Test
    fun `knowledge kind filter narrows sections`() {
        val ruleOnly = toolbox().read("competition_knowledge", JSONObject().put("kind", "rule")).summary
        assertTrue(ruleOnly, ruleOnly.contains("【加分口径版本】"))
        assertTrue(ruleOnly, !ruleOnly.contains("【加分目录】"))
        val awardOnly = toolbox().read("competition_knowledge", JSONObject().put("kind", "award")).summary
        assertTrue(awardOnly, awardOnly.contains("还没有登记过获奖"))
        val miss = toolbox().read(
            "competition_knowledge",
            JSONObject().put("kind", "catalogue").put("query", "机器人"),
        ).summary
        assertTrue(miss, miss.contains("加分目录里没有名称含「机器人」"))
    }

    @Test
    fun `web fetch prefers registered official site with provenance`() {
        val fetcher = FakeFetcher()
        val text = toolbox(fetcher = fetcher)
            .read("competition_web", JSONObject().put("query", "创新大赛")).summary
        assertEquals("https://cy.ncss.cn", fetcher.lastUrl)
        assertTrue(text, text.contains("来源：https://cy.ncss.cn（HTTP 200，抓取于 2026-11-09 12:00 · 内置库登记官网）"))
        assertTrue(text, text.contains("标题：通知标题"))
        assertTrue(text, text.contains("报名通知正文：11月30日截止"))
    }

    @Test
    fun `web fetch failure and missing registration are honest`() {
        val text = toolbox(fetcher = FakeFetcher(ok = false))
            .read("competition_web", JSONObject().put("query", "创新大赛")).summary
        assertTrue(text, text.contains("抓取失败：HTTP 503"))
        val missing = toolbox(fetcher = FakeFetcher())
            .read("competition_web", JSONObject().put("query", "不存在的比赛")).summary
        assertTrue(missing, missing.contains("内置库里没有「不存在的比赛」的官网登记"))
        val nothing = toolbox(fetcher = FakeFetcher()).read("competition_web").summary
        assertTrue(nothing, nothing.contains("没有要抓的内容"))
    }

    @Test
    fun `web without fetcher reports no capability`() {
        val text = toolbox().read("competition_web", JSONObject().put("query", "创新大赛")).summary
        assertTrue(text, text.contains("本构建没有联网抓取能力"))
    }
}
