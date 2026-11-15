package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按需求找队友：只按确定信息命中，理由可解释；在队里的人不重复推荐。 */
class TeammateMatchTest {

    private fun person(
        name: String,
        roleTag: String? = null,
        responsibility: String = "",
        freeMinutes: Int = 0,
        capacity: Double = 3.0,
        teams: Set<String> = emptySet(),
        lastSeen: Long = 0L,
        note: String = "",
    ) = RosterPerson(
        key = TeamRosterBook.keyOf(name),
        name = name,
        roleTag = roleTag,
        responsibility = responsibility,
        weeklyAvailability = if (freeMinutes > 0) {
            mapOf(DayOfWeek.MONDAY to listOf(FreeWindow(19 * 60, 19 * 60 + freeMinutes)))
        } else {
            emptyMap()
        },
        dailyCapacityHours = capacity,
        teamIds = teams,
        lastSeenEpochMillis = lastSeen,
        note = note,
    )

    @Test
    fun `rank matches keywords and availability with plain reasons`() {
        val need = TeamNeed(title = "找开发写后端", hoursPerWeek = 2.0)
        val ranked = TeammateMatchEngine.rank(
            listOf(
                person("阿岚", roleTag = "开发", responsibility = "写后端 API", freeMinutes = 180, capacity = 4.0),
                person("老白", roleTag = "设计", responsibility = "做界面和海报"),
            ),
            need,
        )

        val first = ranked.first()
        assertEquals("阿岚", first.person.name)
        assertTrue(first.hits.contains("开发"))
        assertTrue(first.reasons.any { it.startsWith("每周空") && it.contains("够") })
        assertEquals("老白", ranked.last().person.name)
        assertTrue(ranked.last().reasons.contains("能力关键词没命中"))
        assertTrue(ranked.last().reasons.contains("空闲未登记"))
    }

    @Test
    fun `people already in the team are excluded`() {
        val people = listOf(
            person("阿岚", roleTag = "开发"),
            person("老白", roleTag = "开发"),
        )
        val ranked = TeammateMatchEngine.rank(people, TeamNeed("开发"), excludeKeys = setOf(TeamRosterBook.keyOf("阿岚")))
        assertEquals(listOf("老白"), ranked.map { it.person.name })
    }

    @Test
    fun `teamed history tips the order and shows the count`() {
        val need = TeamNeed("开发")
        val fresh = person("小满", roleTag = "开发")
        val known = person("小周", roleTag = "开发", teams = setOf("t-1", "t-2"), lastSeen = 50)

        val ranked = TeammateMatchEngine.rank(listOf(fresh, known), need)
        assertEquals("小周", ranked.first().person.name)
        assertTrue(ranked.first().reasons.any { it.contains("一起 2 场") })
    }

    @Test
    fun `notes count as weaker evidence`() {
        val ranked = TeammateMatchEngine.rank(
            listOf(person("阿岚", note = "之前做过路演 PPT")),
            TeamNeed(title = "路演 PPT"),
        )
        val match = ranked.single()
        assertTrue(match.hits.contains("路演"))
        assertTrue(match.reasons.any { it.startsWith("命中") })
    }

    @Test
    fun `reasons show the need word instead of token fragments`() {
        val ranked = TeammateMatchEngine.rank(
            listOf(person("阿岚", note = "会Vue和PPT，后端也能补")),
            TeamNeed(title = "PPT"),
        )
        val reasons = ranked.single().reasons.joinToString()
        assertTrue(reasons.contains("命中备注：PPT"))
        assertTrue(!reasons.contains("pp、") && !reasons.contains("、pp") && !reasons.contains("、pt"))
    }

    @Test
    fun `blank need ranks nobody`() {
        val ranked = TeammateMatchEngine.rank(listOf(person("阿岚", roleTag = "开发")), TeamNeed("  "))
        assertTrue(ranked.isEmpty())
    }
}
