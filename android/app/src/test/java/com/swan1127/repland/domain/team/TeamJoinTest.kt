package com.swan1127.repland.domain.team

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队伍码加入：码由 teamId 派生；新队员无管理权限，并如实通知队长。 */
class TeamJoinTest {

    private val now = LocalDateTime.of(2026, 11, 9, 9, 0)

    private fun team(teamId: String = "t-ABC12345") = TeamCollabState.create(
        teamId = teamId,
        teamName = "星火战队",
        competitionDeadline = LocalDate.of(2026, 12, 20),
        captain = MemberCard("m-captain", "小林"),
        now = { now },
    )

    @Test
    fun `invite code is deterministic and derived from team id`() {
        assertEquals("ABC12345", inviteCodeOf("t-abc12345"))
        assertEquals("ABC12345", inviteCodeOf("t-abc12345-9f2e"))
        assertEquals(inviteCodeOf("t-abc12345"), team("t-abc12345").inviteCode)
        assertTrue(inviteCodeOf("t-").isEmpty())
    }

    @Test
    fun `join as member adds a permissionless teammate and notifies captain`() {
        val state = team()
        val joined = state.joinAsMember(" 小周 ")
        assertEquals("小周", joined.name)
        assertFalse(joined.isCaptain)
        assertTrue(joined.permissions.isEmpty())
        assertTrue(state.members.any { it.memberId == joined.memberId })
        assertEquals(2, state.members.size)
        assertTrue(state.notifications.any { it.message.contains("小周 加入") && it.audience == "队长" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `join rejects duplicate names`() {
        team().joinAsMember("小林")
    }
}
