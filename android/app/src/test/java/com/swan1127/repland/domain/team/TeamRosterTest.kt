package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨队名册：历史队友自动记、想了解单独放、跨队合并取并集。 */
class TeamRosterTest {

    private val now = LocalDateTime.of(2026, 11, 9, 9, 0)

    private fun team(
        teamId: String = "t-ABC12345",
        teamName: String = "星火战队",
        captain: MemberCard = MemberCard("m-captain", "小林"),
    ) = TeamCollabState.create(
        teamId = teamId,
        teamName = teamName,
        competitionDeadline = LocalDate.of(2026, 12, 20),
        captain = captain,
        now = { now },
    )

    private fun captainId(state: TeamCollabState) = state.members.first { it.isCaptain }.memberId

    @Test
    fun `recordTeam skips the captain and accumulates teams`() {
        val first = team()
        first.joinAsMember(" 阿岚 ")
        var book = TeamRosterBook().recordTeam(first, seenAtEpochMillis = 100)

        assertNull(book.person(TeamRosterBook.keyOf("小林")))
        val alan = book.person(TeamRosterBook.keyOf("阿岚"))!!
        assertTrue(alan.hasTeamed)
        assertEquals(setOf("t-ABC12345"), alan.teamIds)
        assertEquals("星火战队", alan.lastTeamName)
        assertTrue(book.candidates().isEmpty())

        val second = team(teamId = "t-SECOND", teamName = "第二队", captain = MemberCard("m-c2", "小周"))
        second.addMemberBy(
            captainId(second),
            MemberCard(
                memberId = "m-alan",
                name = "阿岚",
                roleTag = "开发",
                weeklyAvailability = mapOf(DayOfWeek.MONDAY to listOf(FreeWindow(19 * 60, 22 * 60))),
                scheduleConfidence = ScheduleConfidence.HIGH,
                dailyCapacityHours = 2.5,
            ),
        )
        book = book.recordTeam(second, seenAtEpochMillis = 200)

        val merged = book.person(TeamRosterBook.keyOf("阿岚"))!!
        assertEquals(setOf("t-ABC12345", "t-SECOND"), merged.teamIds)
        assertEquals("开发", merged.roleTag)
        assertEquals(2.5, merged.dailyCapacityHours, 0.001)
        assertEquals(1, merged.weeklyAvailability[DayOfWeek.MONDAY]?.size)
        assertEquals("第二队", merged.lastTeamName)
        assertEquals(200L, merged.lastSeenEpochMillis)
    }

    @Test
    fun `pin keeps a candidate apart until they actually team up`() {
        var book = TeamRosterBook().pin("小满", note = "设计很强，先记着", seenAtEpochMillis = 10)
        assertEquals("小满", book.candidates().single().name)
        assertFalse(book.candidates().single().hasTeamed)
        assertTrue(book.teammates().isEmpty())

        val state = team()
        state.addMemberBy(captainId(state), MemberCard(memberId = "m-man", name = "小满"))
        book = book.recordTeam(state, seenAtEpochMillis = 20)

        assertTrue(book.candidates().isEmpty())
        val teammate = book.teammates().single()
        assertEquals("小满", teammate.name)
        assertEquals("设计很强，先记着", teammate.note)
    }

    @Test
    fun `merge unions team ids and keeps the fresher details`() {
        val first = team()
        first.joinAsMember("阿岚")
        val fromDisk = TeamRosterBook().recordTeam(first, seenAtEpochMillis = 100)
        val inSession = TeamRosterBook().pin("阿岚", note = "课少，能扛开发", seenAtEpochMillis = 300)

        val merged = fromDisk.merge(inSession).person(TeamRosterBook.keyOf("阿岚"))!!
        assertEquals(setOf("t-ABC12345"), merged.teamIds)
        assertEquals("课少，能扛开发", merged.note)
        assertEquals(300L, merged.lastSeenEpochMillis)
        assertTrue(merged.hasTeamed)
    }

    @Test
    fun `names normalize across whitespace and case and can be forgotten`() {
        assertEquals(TeamRosterBook.keyOf("阿 岚"), TeamRosterBook.keyOf("阿岚"))
        assertEquals(TeamRosterBook.keyOf(" Alan "), TeamRosterBook.keyOf("alan"))

        val book = TeamRosterBook().pin("小满").pin("老白")
        val next = book.forget(TeamRosterBook.keyOf(" 小满 "))
        assertEquals(1, next.people.size)
        assertNull(next.person(TeamRosterBook.keyOf("小满")))
        assertEquals(book, book.forget(TeamRosterBook.keyOf("不存在")))
    }
}
