package com.swan1127.repland.domain.team

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片确认记录并入本地主副本（T4c）：回调只等价一次显式确认操作——
 * apply 必须走与 UI 相同的 TeamCollabState 方法；本地无对应对象时只留提示。
 */
class ConfirmationApplierTest {

    private val now: () -> LocalDateTime = { LocalDateTime.of(2026, 11, 9, 12, 0) }

    private fun state(): TeamCollabState {
        val team = TeamCollabState.create(
            teamId = "t-1", teamName = "逐晓", competitionDeadline = LocalDate.of(2026, 12, 20),
            captain = MemberCard("m-captain", "阿岚", roleTag = "队长"),
            now = now,
        )
        team.addMember(MemberCard("m-alice", "Alice", roleTag = "技术"))
        team.setBaseline(
            "m-captain",
            listOf(Phase("p1", "前期", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 14))),
            listOf(TeamTask("k1", "调研", TaskTier.MID_TERM, "p1", estimatedHours = 8.0, createdBy = "m-captain")),
        )
        return team
    }

    private fun draftFor(memberId: String = "m-alice") = TeamPlanDraft(
        placements = listOf(Placement("k1", memberId, LocalDate.of(2026, 11, 10), 19 * 60, 21 * 60)),
    )

    private fun record(kind: String, refId: String, operator: String = "m-captain", revision: Int = 1) =
        ConfirmationApplier.Record(
            seq = 1, kind = kind, refId = refId, revision = revision,
            operatorMemberId = operator, operatorName = "阿岚", decidedAt = "2026-11-09T12:00",
        )

    @Test
    fun `confirm_draft applies through the same state method as UI`() {
        val team = state()
        val pending = mutableMapOf("d-1" to draftFor())
        val report = ConfirmationApplier.apply(team, listOf(record(ConfirmationApplier.KIND_CONFIRM_DRAFT, "d-1")), pending)

        assertTrue(report.changed)
        assertEquals("m-alice", team.tasks[0].assignedTo) // 定人写回（§5 确认排期即确认分工）
        assertTrue(team.blocks.any { it.taskId == "k1" && it.sourceTeamId == "t-1" })
        assertTrue(pending.isEmpty())
        assertTrue(team.notifications.any { it.message.contains("排期草案已生效") })
        assertTrue(report.notes.single().contains("在飞书确认草案生效"))
    }

    @Test
    fun `confirm without pending draft only leaves a note`() {
        val team = state()
        val report = ConfirmationApplier.apply(team, listOf(record(ConfirmationApplier.KIND_CONFIRM_DRAFT, "d-ghost")), mutableMapOf())

        assertFalse(report.changed)
        assertEquals(null, team.tasks[0].assignedTo) // 状态不动（任务未分配）
        assertTrue(team.blocks.none { it.taskId == "k1" })
        assertTrue(report.notes.single().contains("本地无待确认草案"))
    }

    @Test
    fun `abandon_draft drops the pending draft without touching state`() {
        val team = state()
        val pending = mutableMapOf("d-1" to draftFor())
        val report = ConfirmationApplier.apply(team, listOf(record(ConfirmationApplier.KIND_ABANDON_DRAFT, "d-1")), pending)

        assertFalse(report.changed)
        assertTrue(pending.isEmpty())
        assertTrue(team.blocks.none { it.taskId == "k1" })
        assertTrue(report.notes.single().contains("放弃草案"))
    }

    @Test
    fun `transfer adjudication records resolve pending objection`() {
        val team = state()
        team.transfer("m-captain", "k1", "m-alice")
        val objection = team.raiseObjection("m-alice", "k1", "我那周有比赛")

        val upheld = ConfirmationApplier.apply(
            team,
            listOf(record(ConfirmationApplier.KIND_KEEP_TRANSFER, objection.objectionId)),
            mutableMapOf(),
        )
        assertTrue(upheld.changed)
        assertEquals("m-alice", team.tasks[0].assignedTo)
        assertEquals(ObjectionResolution.UPHELD, team.objections[0].resolution)

        // 收回转派：assignedTo 回到原负责人
        val team2 = state()
        team2.transfer("m-captain", "k1", "m-alice")
        val objection2 = team2.raiseObjection("m-alice", "k1", "不熟悉这块")
        val reclaimed = ConfirmationApplier.apply(
            team2,
            listOf(record(ConfirmationApplier.KIND_RECLAIM_TRANSFER, objection2.objectionId)),
            mutableMapOf(),
        )
        assertTrue(reclaimed.changed)
        assertEquals("m-captain", team2.tasks[0].assignedTo)
        assertEquals(ObjectionResolution.WITHDRAWN, team2.objections[0].resolution)
    }

    @Test
    fun `resolved or missing objection only leaves a note`() {
        val team = state()
        team.transfer("m-captain", "k1", "m-alice")
        val objection = team.raiseObjection("m-alice", "k1", "有冲突")
        team.resolveObjection("m-captain", objection.objectionId, keepTransfer = true)

        val report = ConfirmationApplier.apply(
            team,
            listOf(record(ConfirmationApplier.KIND_KEEP_TRANSFER, objection.objectionId)),
            mutableMapOf(),
        )
        assertFalse(report.changed)
        assertTrue(report.notes.single().contains("本地异议不存在或已裁决"))
    }
}
