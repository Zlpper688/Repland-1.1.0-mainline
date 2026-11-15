package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.team.FreeWindow
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.Placement
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPlanDraft
import com.swan1127.repland.domain.team.TeamTask
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 决策摘要：把草案里「改什么/影响谁/风险」算清楚——任务名、工时、分工前后、冲突、低置信度、每人负载。 */
class TeamDecisionBriefTest {

    private val day = LocalDate.of(2026, 11, 10)

    private fun stateWithTasks(): TeamCollabState {
        val state = TeamCollabState.create(
            teamId = "t-brief", teamName = "摘要队", competitionDeadline = null,
            captain = MemberCard(
                "m-1", "阿岚", roleTag = "队长",
                weeklyAvailability = mapOf(DayOfWeek.MONDAY to listOf(FreeWindow(9 * 60, 12 * 60))),
            ),
        )
        state.addMember(MemberCard("m-2", "小柯", roleTag = "技术")) // 无空闲登记 → 低置信度
        state.tasks += TeamTask("k1", "调研", TaskTier.MID_TERM, estimatedHours = 4.0, assignedTo = null, createdBy = "m-1")
        state.tasks += TeamTask("k2", "原型", TaskTier.MID_TERM, estimatedHours = 6.0, assignedTo = "m-1", createdBy = "m-1")
        state.tasks += TeamTask("k3", "文案", TaskTier.MID_TERM, estimatedHours = 2.0, assignedTo = "m-1", createdBy = "m-1")
        state.tasks += TeamTask("k4", "路演", TaskTier.MID_TERM, estimatedHours = 1.0, createdBy = "m-1")
        return state
    }

    private fun draft(): TeamPlanDraft = TeamPlanDraft(
        placements = listOf(
            Placement("k1", "m-2", day, 18 * 60, 19 * 60, lowConfidence = true,
                conflicts = listOf(PersonalBlock("b-x", "m-2", day, 18 * 60, 19 * 60, "t-brief", "k1"))),
            Placement("k2", "m-2", day, 19 * 60, 21 * 60),
            Placement("k3", "m-1", day, 10 * 60, 10 * 60 + 30),
        ),
        unassigned = listOf("k4"),
    )

    @Test
    fun `lines carry task title member hours and assignee change`() {
        val brief = TeamDecisionBrief.of(draft(), stateWithTasks())
        assertEquals(3, brief.lines.size)
        val first = brief.lines[0]
        assertEquals("调研", first.taskTitle)
        assertEquals("小柯", first.memberName)
        assertEquals(1.0, first.hours, 0.001)
        assertTrue(first.conflict)
        assertTrue(first.lowConfidence)
        assertEquals("待认领→小柯", first.assigneeChange)
        assertEquals("阿岚→小柯", brief.lines[1].assigneeChange)
        assertNull(brief.lines[2].assigneeChange) // k3 本就归阿岚，不变
    }

    @Test
    fun `member loads aggregate per person sorted desc with confidence`() {
        val brief = TeamDecisionBrief.of(draft(), stateWithTasks())
        assertEquals(2, brief.memberLoads.size)
        assertEquals("m-2", brief.memberLoads[0].memberId) // 3.0h 排在前
        assertEquals(3.0, brief.memberLoads[0].addedHours, 0.001)
        assertTrue(brief.memberLoads[0].lowConfidence)
        assertEquals("m-1", brief.memberLoads[1].memberId)
        assertEquals(0.5, brief.memberLoads[1].addedHours, 0.001)
        assertFalse(brief.memberLoads[1].lowConfidence)
    }

    @Test
    fun `totals conflicts reassigns and unassigned titles`() {
        val brief = TeamDecisionBrief.of(draft(), stateWithTasks())
        assertEquals(3.5, brief.totalHours, 0.001)
        assertEquals(1, brief.conflictCount)
        assertEquals(2, brief.reassignCount)
        assertEquals(listOf("路演"), brief.unassignedTitles)
    }

    @Test
    fun `risk tiering flags destructive tools`() {
        fun action(tool: String) = AgentAction("a", tool, "label", "summary", "{}")
        assertEquals(TeamDecisionBrief.Risk.HIGH, TeamDecisionBrief.riskOf(action("cancel_team_task")))
        assertEquals(TeamDecisionBrief.Risk.HIGH, TeamDecisionBrief.riskOf(action("plan_team_week")))
        assertEquals(TeamDecisionBrief.Risk.NORMAL, TeamDecisionBrief.riskOf(action("add_team_member")))
        assertTrue(TeamDecisionBrief.consequenceOf(action("transfer_team_task"))!!.contains("负责人"))
        assertNull(TeamDecisionBrief.consequenceOf(action("add_team_member")))
    }

    @Test
    fun `hours text switches between minutes and hours`() {
        assertEquals("30分钟", TeamDecisionBrief.hoursText(0.5))
        assertEquals("3.5小时", TeamDecisionBrief.hoursText(3.5))
    }
}