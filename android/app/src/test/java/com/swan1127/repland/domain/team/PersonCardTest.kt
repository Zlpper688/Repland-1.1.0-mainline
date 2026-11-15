package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料卡：默认卡恒有；竞赛卡只能来自真实队伍；公开卡才出现在分享摘要里。 */
class PersonCardTest {

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

    @Test
    fun `empty book always has exactly one default card`() {
        val book = PersonCardBook()
        assertEquals(1, book.allCards().size)
        assertTrue(book.defaultCard().isDefault)
        assertFalse(book.hasCompeted)
        assertEquals(0, book.publicCards().size)
    }

    @Test
    fun `competition cards need real team evidence and lose handcrafted claims`() {
        val handcrafted = PersonCard(
            cardId = PersonCard.competitionCardId("t-FAKE"),
            kind = PersonCardKind.COMPETITION,
            name = "小林",
            headline = "我做过很多比赛",
        )
        val book = PersonCardBook(listOf(handcrafted))
        assertEquals(1, book.allCards().size)
        assertTrue(book.defaultCard().isDefault)
        assertTrue(book.competitionCards().isEmpty())

        val real = handcrafted.copy(teamId = "t-REAL", teamName = "真实队")
        val withReal = PersonCardBook(listOf(real))
        assertEquals(1, withReal.competitionCards().size)
        assertEquals(PersonCard.competitionCardId("t-REAL"), withReal.competitionCards().single().cardId)
    }

    @Test
    fun `syncTeams is idempotent and keeps user edits`() {
        val participation = CompetitionParticipation(
            teamId = "t-ABC12345",
            teamName = "星火战队",
            competitionName = "数学建模校赛",
            memberName = "小林",
            roleTag = "建模",
            responsibility = "负责模型与论文",
            result = "",
            teammateCount = 4,
            seenAtEpochMillis = 100,
        )
        var book = PersonCardBook().syncTeams(listOf(participation))
        val cardId = PersonCard.competitionCardId("t-ABC12345")
        var card = book.card(cardId)!!
        assertEquals("数学建模校赛", card.title)
        assertEquals("星火战队", card.teamName)
        assertEquals(4, card.teammateCount)
        assertEquals(CardVisibility.PRIVATE, card.visibility)

        book = book.editCompetition(cardId, contribution = "我写了论文和答辩", result = "校级一等奖",
            visibility = CardVisibility.PUBLIC, nowEpochMillis = 200)
        book = book.syncTeams(listOf(participation.copy(teammateCount = 5, seenAtEpochMillis = 300)))
        card = book.card(cardId)!!
        assertEquals(1, book.competitionCards().size)
        assertEquals("我写了论文和答辩", card.headline)
        assertEquals("校级一等奖", card.result)
        assertEquals(CardVisibility.PUBLIC, card.visibility)
        assertEquals(5, card.teammateCount)
        assertEquals("数学建模校赛", card.competitionName)
    }

    @Test
    fun `no matching team means only the default card`() {
        val other = team()
        other.joinAsMember("阿岚")
        val book = PersonCardBook().syncTeams(
            PersonCardSync.participations(
                states = listOf(other),
                archives = emptyList(),
                aliases = setOf(TeamRosterBook.keyOf("小林")),
                seenAtEpochMillis = 100,
            ),
        )
        assertEquals(1, book.competitionCards().size)

        val stranger = PersonCardBook().syncTeams(
            PersonCardSync.participations(
                states = listOf(other),
                archives = emptyList(),
                aliases = setOf(TeamRosterBook.keyOf("路人甲")),
                seenAtEpochMillis = 100,
            ),
        )
        assertTrue(stranger.competitionCards().isEmpty())
        assertEquals(1, stranger.allCards().size)
    }

    @Test
    fun `public summary only includes cards marked public`() {
        val participation = CompetitionParticipation(
            teamId = "t-ABC12345",
            teamName = "星火战队",
            competitionName = "数学建模校赛",
            memberName = "小林",
            roleTag = "建模",
            responsibility = "模型与论文",
            result = "",
            teammateCount = 4,
            seenAtEpochMillis = 100,
        )
        var book = PersonCardBook()
            .editDefault(
                name = "小林", headline = "能做模型也能写", abilities = "数学建模、论文写作",
                expectation = "想找会做可视化的队友",
                availability = mapOf(DayOfWeek.MONDAY to listOf(FreeWindow(19 * 60, 22 * 60))),
                dailyCapacityHours = 3.0, visibility = CardVisibility.PUBLIC, nowEpochMillis = 100,
            )
            .syncTeams(listOf(participation))

        var summary = book.publicSummary()!!
        assertTrue(summary.contains("数学建模"))
        assertTrue(summary.contains("团队预期"))
        assertFalse(summary.contains("星火战队"))

        book = book.setVisibility(PersonCard.competitionCardId("t-ABC12345"), CardVisibility.PUBLIC)
        summary = book.publicSummary()!!
        assertTrue(summary.contains("星火战队"))
        assertTrue(summary.contains("数学建模校赛"))

        val privateBook = PersonCardBook().setVisibility(PersonCard.DEFAULT_ID, CardVisibility.PRIVATE)
        assertNull(privateBook.publicSummary())
    }

    @Test
    fun `seedFromMember fills only placeholders`() {
        val member = MemberCard(
            memberId = "m-1",
            name = "小满",
            roleTag = "设计",
            weeklyAvailability = mapOf(DayOfWeek.SUNDAY to listOf(FreeWindow(9 * 60, 12 * 60))),
            dailyCapacityHours = 4.0,
        )
        val seeded = PersonCardBook().seedFromMember(member, 100).defaultCard()
        assertEquals("小满", seeded.name)
        assertEquals("设计", seeded.roleTag)
        assertEquals(4.0, seeded.dailyCapacityHours, 0.001)

        val named = PersonCardBook()
            .editDefault("我自己", "定位", "能力", "预期", emptyMap(), 2.0, CardVisibility.PRIVATE, 100)
            .seedFromMember(member, 200)
            .defaultCard()
        assertEquals("我自己", named.name)
        assertEquals(2.0, named.dailyCapacityHours, 0.001)
    }
}
