package com.swan1127.repland.domain.team.runtime

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.FreeWindow
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.Phase
import com.swan1127.repland.domain.team.TaskRank
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 团队工具面的机制验收：权限先判、写入选确认、排期走确定性调度、
 * 派生动作（草案）二次确认、私有时间只露「在忙」。
 */
class TeamToolboxTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9) // 周一
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 9, 9, 0)

    private fun evenings() = mapOf(
        DayOfWeek.MONDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.TUESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.WEDNESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.THURSDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.FRIDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.SATURDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
        DayOfWeek.SUNDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
    )

    private fun fixture(): TeamCollabState {
        val team = TeamCollabState.create(
            teamId = "t1",
            teamName = "演示队",
            competitionDeadline = LocalDate.of(2026, 12, 20),
            captain = MemberCard("m-cap", "阿岚", roleTag = "队长"),
            now = { now },
        )
        team.addMember(
            MemberCard(
                "m-ke", "小柯", roleTag = "技术",
                weeklyAvailability = evenings(),
                permissions = setOf(TeamPermission.MEMORY_WRITE),
            ),
        )
        team.addMember(MemberCard("m-new", "小新", roleTag = "文案", weeklyAvailability = evenings()))
        team.setBaseline(
            "m-cap",
            listOf(Phase("p1", "前期", today, today.plusDays(13))),
            listOf(
                TeamTask("market", "市场调研", TaskTier.MID_TERM, phaseId = "p1", estimatedHours = 8.0,
                    assignedTo = "m-ke", createdBy = "m-cap"),
                TeamTask("proposal", "项目书", TaskTier.MID_TERM, phaseId = "p1", estimatedHours = 6.0,
                    assignedTo = "m-cap", createdBy = "m-cap"),
                TeamTask("prototype", "原型", TaskTier.MID_TERM, phaseId = "p1", estimatedHours = 6.0,
                    createdBy = "m-cap"),
                TeamTask("submit", "提交材料", TaskTier.MID_TERM, phaseId = "p1", estimatedHours = 2.0,
                    rank = TaskRank.MUST, assignedTo = "m-ke", createdBy = "m-cap"),
            ),
        )
        return team
    }

    private fun TeamToolbox.validate(tool: String, args: JSONObject): String? =
        specs().first { it.name == tool }.validate!!(args)

    @Test
    fun memberWithoutPermissionIsRejectedBeforeAndDuringWrite() {
        val team = fixture()
        val toolbox = TeamToolbox(team, "m-new", today)

        val rejection = toolbox.validate("create_team_task", JSONObject().put("title", "路演稿"))
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("创建任务"))

        val action = AgentAction("a1", "create_team_task", "新建任务", "新建任务「路演稿」", """{"title":"路演稿"}""")
        val result = runBlocking { toolbox.runWrite(action) }
        assertFalse(result.ok)
        assertTrue(result.summary.contains("创建任务"))
        assertTrue(team.tasks.none { it.title == "路演稿" })
    }

    @Test
    fun createTaskAcceptsEditBaselineOrCreateTaskPermission() {
        val team = fixture()
        team.members[team.members.indexOfFirst { it.memberId == "m-ke" }] =
            team.members.first { it.memberId == "m-ke" }.copy(permissions = setOf(TeamPermission.CREATE_TASK))
        val memberToolbox = TeamToolbox(team, "m-ke", today)
        assertNull(memberToolbox.validate("create_team_task", JSONObject().put("title", "路演稿")))
        assertTrue(runBlocking {
            memberToolbox.runWrite(
                AgentAction("a2", "create_team_task", "新建任务", "新建任务「路演稿」", """{"title":"路演稿"}"""),
            )
        }.ok)
        assertEquals(5, team.tasks.size)
    }

    @Test
    fun reportDoneOnlyForOwnTaskUnlessCaptainOrScheduler() {
        val team = fixture()
        val own = TeamToolbox(team, "m-ke", today)
        assertNull(own.validate("report_task_done", JSONObject().put("taskId", "market")))

        val other = TeamToolbox(team, "m-new", today)
        val rejection = other.validate("report_task_done", JSONObject().put("taskId", "market"))
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("不在你名下"))
        assertFalse(runBlocking {
            other.runWrite(
                AgentAction("a3", "report_task_done", "报告完成", "报告「market」完成", """{"taskId":"market"}"""),
            )
        }.ok)
        assertFalse(team.task("market").status == TeamTaskStatus.DONE)

        assertTrue(runBlocking {
            own.runWrite(
                AgentAction("a4", "report_task_done", "报告完成", "报告「market」完成",
                    """{"taskId":"market","note":"做完了"}"""),
            )
        }.ok)
        assertEquals(TeamTaskStatus.DONE, team.task("market").status)
        assertTrue(team.ledger.any { it.evidence.contains("做完了") })
    }

    @Test
    fun planWeekNeedsSchedulerAndFinishDraftOnlyAfterSecondConfirmation() {
        val team = fixture()
        val member = TeamToolbox(team, "m-new", today)
        val rejected = member.validate("plan_team_week", JSONObject())
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("排期与转派"))

        val captain = TeamToolbox(team, "m-cap", today)
        assertNull(captain.validate("plan_team_week", JSONObject()))
        val planned = runBlocking {
            captain.runWrite(AgentAction("p1", "plan_team_week", "整周排期", "生成整周排期草案", "{}"))
        }
        assertTrue(planned.summary, planned.ok)
        assertTrue("草案阶段不写日程", team.blocks.isEmpty())
        val apply = planned.followUpActions.single()
        assertEquals("apply_team_plan", apply.toolName)
        assertTrue(JSONObject(apply.argumentsJson).getJSONArray("placements").length() > 0)

        val applied = runBlocking { captain.runWrite(apply) }
        assertTrue(applied.summary, applied.ok)
        assertTrue(team.blocks.isNotEmpty())
        assertTrue(team.blocks.all { it.sourceTeamId == "t1" })
        assertTrue(team.blocks.all { it.visibility == BlockVisibility.TEAM_VISIBLE })
    }

    @Test
    fun privateBusyIsMaskedWhileTeamArrangementIsVisible() {
        val team = fixture()
        team.blocks += PersonalBlock(
            "b-private", "m-ke", today, 9 * 60, 10 * 60,
            sourceTeamId = "t1", taskId = null,
            visibility = BlockVisibility.PRIVATE_BUSY, label = "医院复查",
        )
        team.blocks += PersonalBlock(
            "b-team", "m-ke", today, 18 * 60, 19 * 60,
            sourceTeamId = "t1", taskId = "market", label = "市场调研",
        )
        val toolbox = TeamToolbox(team, "m-ke", today)

        val schedule = runBlocking { toolbox.runRead("team_schedule", JSONObject().put("days", 1)) }.summary
        assertTrue(schedule.contains("在忙"))
        assertTrue(schedule.contains("市场调研"))
        assertFalse("私有时间内容绝不外露", schedule.contains("医院复查"))

        val briefing = TeamPrompt.briefing(team, "m-ke", today)
        assertTrue(briefing.contains("【当前身份】小柯"))
        assertTrue(briefing.contains("在忙"))
        assertFalse(briefing.contains("医院复查"))
    }

    @Test
    fun memberManagementChecksPermissionDuplicateNameAndCaptainSelf() {
        val team = fixture()
        val member = TeamToolbox(team, "m-ke", today)
        val rejected = member.validate("add_team_member", JSONObject().put("name", "小明"))
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("邀请移除成员"))

        val captain = TeamToolbox(team, "m-cap", today)
        val duplicate = captain.validate("add_team_member", JSONObject().put("name", "小柯"))
        assertNotNull(duplicate)
        assertTrue(duplicate!!.contains("同名"))
        assertNull(captain.validate("add_team_member", JSONObject().put("name", "小明")))
        assertTrue(runBlocking {
            captain.runWrite(
                AgentAction("m1", "add_team_member", "加队员", "邀请 小明 入队", """{"name":"小明","roleTag":"答辩"}"""),
            )
        }.ok)
        assertEquals(4, team.members.size)

        val self = captain.validate("remove_team_member", JSONObject().put("member", "阿岚"))
        assertNotNull(self)
        assertTrue(self!!.contains("队长不能移除自己"))
    }

    @Test
    fun archivedTeamRejectsEveryWrite() {
        val team = fixture()
        team.archiveTeam("m-cap", "校赛一等奖")
        val toolbox = TeamToolbox(team, "m-cap", today)
        val rejection = toolbox.validate("create_team_task", JSONObject().put("title", "赛后复盘"))
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("只读"))
    }

    @Test
    fun promptStatesMechanismAndCurrentIdentity() {
        val prompt = TeamPrompt.system("m-cap", "阿岚", isCaptain = true)
        assertTrue(prompt.contains("当前身份：阿岚（队长）"))
        assertTrue(prompt.contains("待确认动作"))
        assertTrue(prompt.contains("plan_team_week"))
        assertTrue(prompt.contains("权限"))
        assertTrue(prompt.contains("不评分") || prompt.contains("不打分"))
    }
}
