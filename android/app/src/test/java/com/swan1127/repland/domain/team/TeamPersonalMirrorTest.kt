package com.swan1127.repland.domain.team

import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.SegmentAuthority
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskOrigin
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TeamTaskMirror
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团队 → 个人纯映射：镜像范围、锁定的个人时段、只带「在忙」的个人时间。 */
class TeamPersonalMirrorTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9) // 周一
    private val nowMillis = 1_800_000_000_000L

    private fun state(
        tasks: List<TeamTask>,
        blocks: List<PersonalBlock> = emptyList(),
    ): TeamCollabState {
        val captain = MemberCard("m1", "小柯", isCaptain = true)
        val state = TeamCollabState.create("team-1", "星火队", LocalDate.of(2026, 12, 20), captain)
        state.addMember(MemberCard("m2", "阿橙"))
        state.setBaseline("m1", emptyList(), tasks)
        state.blocks += blocks
        return state
    }

    @Test
    fun `only my or unclaimed active team tasks are mirrored with team origin`() {
        val source = listOf(
            TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 8.0, assignedTo = "m1"),
            TeamTask("t2", "报名材料", TaskTier.MID_TERM, estimatedHours = 2.0, assignedTo = "m2"),
            TeamTask("t3", "路演PPT", TaskTier.MID_TERM, estimatedHours = 8.0),
            TeamTask("t4", "已取消任务", TaskTier.MID_TERM, estimatedHours = 2.0,
                status = TeamTaskStatus.WONT, assignedTo = "m1"),
        )
        val mirrored = TeamPersonalMirror.tasks(state(source), "m1", nowMillis)

        assertEquals(listOf("team:team-1:t1", "team:team-1:t3"), mirrored.map { it.id })
        val first = mirrored.first()
        assertEquals(TaskOrigin.TEAM, first.origin)
        assertEquals("team-1", first.originRef)
        assertEquals("星火队", first.originLabel)
        assertEquals(TaskCategory.EXTRACURRICULAR, first.category)
        assertTrue(first.isTeamMirror)
        assertEquals(TaskStatus.NOT_STARTED, first.status)
        assertTrue("待认领任务也要进个人展示", mirrored.any { it.id.endsWith(":t3") })
        assertEquals("team-1", TeamTaskMirror.teamIdOf(first.id))
        assertEquals("t1", first.id.removePrefix("team:team-1:"))
    }

    @Test
    fun `personal segments are locked pins on the team lane for future own blocks only`() {
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 8.0, assignedTo = "m1")
        val blocks = listOf(
            PersonalBlock("b1", "m1", today.plusDays(1), 18 * 60, 20 * 60, "team-1", "t1"),
            PersonalBlock("b2", "m1", today.minusDays(1), 18 * 60, 20 * 60, "team-1", "t1"),
            PersonalBlock("b3", "m2", today.plusDays(1), 10 * 60, 11 * 60, "team-1", "t1"),
            PersonalBlock("b4", "m1", today.plusDays(1), 10 * 60, 11 * 60, "other-team", "t9"),
        )
        val segments = TeamPersonalMirror.segments(state(listOf(task), blocks), "m1", today)

        assertEquals(1, segments.size)
        val segment = segments.single()
        assertEquals("team-seg-b1", segment.id)
        assertEquals("team:team-1:t1", segment.taskId)
        assertTrue(segment.isLocked)
        assertEquals(SegmentAuthority.USER_PINNED, segment.authority)
        assertEquals(TeamPersonalMirror.TEAM_TRACK_ID, segment.trackId)
        assertEquals(today.plusDays(1), segment.date)
        // 团队约定的时间默认带响铃：提前量与飞书日历侧一致
        assertTrue(segment.reminderEnabled)
        assertEquals(TeamReminderDefaults.leadMinutes, segment.leadMinutes)
    }

    @Test
    fun `busy time keeps content private and never double counts own team mirrors`() {
        val course = WeeklyTimeBlock(
            id = "c1", title = "高数", kind = TimeBlockKind.COURSE,
            dayOfWeek = DayOfWeek.MONDAY, startMinute = 8 * 60, endMinute = 10 * 60,
            weekPattern = null, createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
        )
        val blocked = DateOverride(
            id = "o1", title = "体检", type = DateOverrideType.BLOCKED, date = today,
            startMinute = 14 * 60, endMinute = 15 * 60, createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
        )
        val personal = PlannedSegment(
            id = "s1", taskId = "personal-task", date = today, startMinute = 19 * 60, endMinute = 20 * 60,
        )
        val ownTeam = PlannedSegment(
            id = "s2", taskId = "team:team-1:t1", date = today, startMinute = 20 * 60, endMinute = 21 * 60,
        )
        val input = TeamPersonalMirror.busyTime(
            actorId = "m1",
            teamId = "team-1",
            today = today,
            horizonDays = 6,
            weeklyBlocks = listOf(course),
            dateOverrides = listOf(blocked),
            segments = listOf(personal, ownTeam),
            semesterFirstWeekMonday = null,
        )

        assertTrue(input.hasPersonalData)
        assertTrue("课程挡住时间", input.busy.any { it.startMinute == 8 * 60 && !it.countsTowardCapacity })
        assertTrue("固定事项挡住时间", input.busy.any { it.startMinute == 14 * 60 })
        assertTrue("个人已排任务占容量", input.busy.any { it.startMinute == 19 * 60 && it.countsTowardCapacity })
        assertFalse("本队团队块由团队域提供，不能算两遍", input.busy.any { it.startMinute == 20 * 60 })
        assertEquals(1.0, input.loadHours, 0.0001)
        assertTrue("所有个人忙碌都不带内容", input.busy.all { it.label == null && it.taskId == null && it.sourceTeamId == null })
        input.busy.forEach { assertEquals(BlockVisibility.PRIVATE_BUSY, it.visibility) }
        assertEquals(listOf(FreeWindow(8 * 60, 22 * 60)), input.availability.getValue(DayOfWeek.MONDAY))
    }

    @Test
    fun `sync note stays silent unless conflicts were skipped`() {
        assertNull(TeamPersonalSyncReport(mirroredTasks = 3, scheduledSegments = 2).note())
        assertNull(TeamPersonalSyncReport().note())
        assertTrue(TeamPersonalSyncReport(skippedConflicts = 2).note()!!.contains("2 段"))
    }
}
