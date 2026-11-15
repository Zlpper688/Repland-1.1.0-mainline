package com.swan1127.repland.domain.team

import com.swan1127.repland.domain.team.agents.UpdateKind
import com.swan1127.repland.domain.team.runtime.TeamAgentRuntime
import com.swan1127.repland.domain.team.runtime.TeamMemoryBank
import com.swan1127.repland.domain.team.runtime.TeamOutcomeReady
import com.swan1127.repland.domain.team.runtime.TeamRequestKind
import com.swan1127.repland.domain.team.runtime.TeamRunFailed
import com.swan1127.repland.domain.team.runtime.TeamSessionContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假队员数据离线跑通（§17 T1 验收）：
 * 建队 → 档案卡 → 基线 → 排期 → 说事 → 重排 → 转派 → 异议 → 台账 → 周报，全程确定性、无网络。
 */
class TeamCollabEndToEndTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9) // 周一
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 9, 9, 0)
    private val competitionDeadline: LocalDate = LocalDate.of(2026, 12, 20)

    private fun evenings() = mapOf(
        DayOfWeek.MONDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.TUESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.WEDNESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.THURSDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.FRIDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.SATURDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
        DayOfWeek.SUNDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
    )

    private fun newTeam(): TeamCollabState {
        val team = TeamCollabState.create(
            teamId = "t-demo",
            teamName = "互联网+校赛小队",
            competitionDeadline = competitionDeadline,
            captain = MemberCard("m-captain", "阿岚", roleTag = "队长"),
            now = { now },
        )
        team.addMember(
            MemberCard(
                "m-ke", "小柯", roleTag = "技术负责",
                responsibility = "负责原型开发与技术实现", weeklyAvailability = evenings(),
            ),
        )
        team.addMember(MemberCard("m-orange", "阿橙", roleTag = "文案", responsibility = "负责项目书初稿与文案"))
        team.addMember(
            MemberCard(
                "m-bai", "老白", roleTag = "数据",
                responsibility = "负责市场调研与数据整理", weeklyAvailability = evenings(),
            ),
        )
        team.addMember(
            MemberCard(
                "m-man", "小满", roleTag = "设计",
                responsibility = "负责路演PPT与视觉设计", weeklyAvailability = evenings(),
            ),
        )

        // 档案卡：六组槽位 + 补充区；关键槽位缺失 → 待补充引导
        val card = CompetitionProfileCard(
            competitionName = "中国国际大学生创新大赛校赛",
            fields = mapOf(
                ProfileSlotGroup.BASIC_INFO to listOf(
                    ProfileField("signup_deadline", "报名截止", "2026-11-20"),
                    ProfileField("member_limit", "人数上限", "5"),
                ),
                ProfileSlotGroup.TIMELINE to listOf(
                    ProfileField("school_final", "校赛终审", "2026-12-20"),
                ),
            ),
            extras = listOf(ExtraEntry("答辩时长", "8 分钟路演 + 5 分钟问答")),
        )
        team.adoptProfileCard(card)

        team.setBaseline(
            "m-captain",
            listOf(
                Phase("phase_early", "前期", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 14)),
                Phase("phase_mid", "中期", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 5)),
                Phase("phase_late", "后期", LocalDate.of(2026, 12, 6), LocalDate.of(2026, 12, 19)),
            ),
            listOf(
                TeamTask("market", "市场调研", TaskTier.MID_TERM, "phase_early", estimatedHours = 8.0, createdBy = "m-captain"),
                TeamTask("proposal", "项目书初稿", TaskTier.MID_TERM, "phase_mid", estimatedHours = 12.0, dependsOn = listOf("market"), createdBy = "m-captain"),
                TeamTask("prototype", "原型开发", TaskTier.MID_TERM, "phase_mid", estimatedHours = 16.0, dependsOn = listOf("market"), createdBy = "m-captain"),
                TeamTask("pitch", "路演PPT", TaskTier.MID_TERM, "phase_late", estimatedHours = 8.0, dependsOn = listOf("proposal", "prototype"), createdBy = "m-captain"),
                TeamTask("submit", "提交材料", TaskTier.MID_TERM, "phase_late", estimatedHours = 4.0, rank = TaskRank.MUST, dependsOn = listOf("pitch"), hardDeadline = LocalDate.of(2026, 12, 19), createdBy = "m-captain"),
                TeamTask("kickoff", "启动会", TaskTier.SHORT_TERM, estimatedHours = 1.0, createdBy = "m-captain"),
            ),
        )

        // 小满的跨队软块（他队可见）+ 私有时间：pitch 属于后期环节（12-06 起排）
        val softOtherTeam = (6..8).map { day ->
            PersonalBlock("x-other-$day", "m-man", LocalDate.of(2026, 12, day), 19 * 60, 20 * 60, sourceTeamId = "t-other", taskId = null, label = "另一队外出调研")
        }
        val privateBlock = PersonalBlock(
            "x-private", "m-man", LocalDate.of(2026, 12, 7), 12 * 60, 13 * 60,
            sourceTeamId = "t-other", taskId = null, visibility = BlockVisibility.PRIVATE_BUSY, label = "心理咨询",
        )
        team.blocks += softOtherTeam + privateBlock
        return team
    }

    private fun runtime(memory: TeamMemoryBank = TeamMemoryBank()) =
        TeamAgentRuntime(memoryBank = memory, today = { today })

    // ---------- 全链路 ----------

    @Test
    fun `fake team runs the full loop offline`() {
        val team = newTeam()
        val events = mutableListOf<String>()
        val rt = runtime()

        // 1) 整周排期（PLAN_WEEK：SCHEDULER + LEDGER，会议类任务不自动排）
        val planOutcome = rt.runSession(TeamSessionContext(TeamRequestKind.PLAN_WEEK, "m-captain", at = now), team) { events += it.summary }
        val draft = planOutcome.draft!!
        assertTrue("草案应覆盖 5 项可排任务", draft.placements.map { it.taskId }.distinct().size == 5)
        assertFalse("会议类任务不自动排", draft.placements.any { it.taskId == "kickoff" })
        team.commitDraft("m-captain", draft)
        val committed = team.blocks.filter { it.sourceTeamId == "t-demo" }
        assertEquals(draft.placements.size, committed.size)

        // 依赖顺序硬约束：上游市场调研先于项目书初稿/原型开发开工
        val firstDate = draft.placements.groupBy { it.taskId }.mapValues { (_, v) -> v.minOf { it.date } }
        assertTrue(firstDate.getValue("market") < firstDate.getValue("proposal"))
        assertTrue(firstDate.getValue("market") < firstDate.getValue("prototype"))
        // 阶段窗口硬约束：中期任务不提前占前期的日子
        assertTrue(firstDate.getValue("proposal") >= LocalDate.of(2026, 11, 15))
        assertTrue(firstDate.getValue("prototype") >= LocalDate.of(2026, 11, 15))
        assertTrue(firstDate.getValue("pitch") >= LocalDate.of(2026, 12, 6))

        // 负载定人：负责说明适配 + 低置信标记
        val assigned = draft.placements.associate { it.taskId to it.memberId }
        assertEquals("m-bai", assigned.getValue("market"))
        assertEquals("m-orange", assigned.getValue("proposal"))
        assertEquals("m-ke", assigned.getValue("prototype"))
        assertEquals("m-man", assigned.getValue("pitch"))
        assertTrue("阿橙未导入课表，照排但标低置信", draft.placements.filter { it.memberId == "m-orange" }.all { it.lowConfidence })

        // 跨队软块标红：小满的路演PPT与他队调研重叠
        val conflicted = draft.placements.filter { it.conflicts.isNotEmpty() }
        assertTrue("小满应出现跨队冲突标红", conflicted.isNotEmpty() && conflicted.all { it.memberId == "m-man" })

        // 两级验收事件齐全
        assertTrue(events.any { it.startsWith("两级验收通过") })
        assertTrue(events.any { it.startsWith("协作产出就绪") })

        // 2) 队员说事：阿橙周三有实验课 → 项目书初稿重排，11-11（周三）不被占用
        val busyOutcome = rt.runSession(
            TeamSessionContext(TeamRequestKind.MEMBER_UPDATE, "m-orange", rawText = "周三有实验课，项目书初稿没时间做了", at = now.plusHours(1)),
            team,
        )
        assertEquals(UpdateKind.BUSY, busyOutcome.structuredRequest!!.kind)
        assertEquals(listOf(LocalDate.of(2026, 11, 11)), busyOutcome.structuredRequest!!.busyDates)
        val rescheduled = busyOutcome.draft!!.placements.filter { it.taskId == "proposal" }
        assertFalse("周三 11-11 不应有阿橙的项目书安排", rescheduled.any { it.memberId == "m-orange" && it.date == LocalDate.of(2026, 11, 11) })
        val pendingTaskIds = team.tasks.filter { it.active }.map { it.taskId }.toSet()
        team.commitDraft("m-captain", busyOutcome.draft!!, replacesTaskIds = pendingTaskIds)

        // 3) 队员报告完成：老白「市场调研做完了」→ 口语留痕 + 状态流转，队长确认落库
        val doneOutcome = rt.runSession(
            TeamSessionContext(TeamRequestKind.STATUS_REPORT, "m-bai", rawText = "市场调研做完了，链接晚点发", at = now.plusHours(2)),
            team,
        )
        assertEquals(UpdateKind.DONE, doneOutcome.structuredRequest!!.kind)
        assertEquals(2, doneOutcome.proposedLedgerEntries.size)
        assertEquals(setOf(LedgerKind.AUTO_SAYING, LedgerKind.STATUS_CHANGE), doneOutcome.proposedLedgerEntries.map { it.kind }.toSet())
        team.updateTask(team.task("market").copy(status = TeamTaskStatus.DONE))
        team.ledger += doneOutcome.proposedLedgerEntries

        // 4) 越权请求直接 FAIL：老白没有排期与转派权限，请求转派被两级验收拦下
        val rejectEvents = mutableListOf<String>()
        val rejected = runCatching {
            rt.runSession(TeamSessionContext(TeamRequestKind.MEMBER_UPDATE, "m-bai", rawText = "把原型开发转给小满", at = now.plusHours(3)), team) { rejectEvents += it.summary }
        }
        assertTrue("越权转派应被拒绝", rejected.isFailure)
        assertTrue(rejectEvents.any { it.contains("权限拒绝") })
        assertTrue(rejectEvents.any { it.startsWith("协作中断") })

        // 5) 转派自动生效 + 一键异议 + 维持裁决（基线责任与执行流转双线留痕）
        val transfer = team.transfer("m-captain", "prototype", "m-man")
        assertEquals("m-man", team.task("prototype").assignedTo)
        assertTrue(team.ledger.any { it.kind == LedgerKind.TRANSFER && it.taskId == "prototype" })
        assertTrue(team.notifications.any { it.channel == NotificationChannel.PUSH_AND_FEISHU && it.audience == "m-man" })

        val objection = team.raiseObjection("m-man", "prototype", "我的路演PPT还没做完，接不动原型")
        assertTrue(team.openObjections().contains(objection))
        team.resolveObjection("m-captain", objection.objectionId, keepTransfer = true)
        assertEquals(ObjectionResolution.UPHELD, team.objections.last().resolution)
        assertEquals("m-man", team.task("prototype").assignedTo)
        assertEquals(transfer.transferId, team.transfers.last { it.objectionId == objection.objectionId }.transferId)

        // 6) 收回裁决：转派小满→老白，老白异议成立 → 回滚给小满
        team.transfer("m-captain", "prototype", "m-bai")
        val objection2 = team.raiseObjection("m-bai", "prototype", "我手头有数据整理，接不了")
        team.resolveObjection("m-captain", objection2.objectionId, keepTransfer = false)
        assertEquals("m-man", team.task("prototype").assignedTo)
        assertEquals(ObjectionResolution.WITHDRAWN, team.objections.last().resolution)

        // 7) 台账：外链与财务（权限门控）
        team.addExternalLink("m-captain", "market", "https://example.com/research-v1")
        team.recordFinance("m-captain", 30_000, "报名费")
        val financeDenied = runCatching { team.recordFinance("m-bai", 1_00, "零食") }
        assertTrue("无财务记账权限应被拒绝", financeDenied.isFailure)
        assertTrue(team.ledger.any { it.kind == LedgerKind.FINANCE && it.amountCents == 30_000L })

        // 8) MoSCoW：MUST 不可取消；队员无权改基线
        assertTrue(runCatching { team.cancelTask("m-captain", "submit") }.isFailure)
        assertTrue(runCatching { team.cancelTask("m-orange", "pitch") }.isFailure)

        // 9) 会议候选：全员空闲交集，避开已排块
        val sunday = LocalDate.of(2026, 11, 15)
        val meetings = TeamScheduler.meetingCandidates(team.members, team.blocks, sunday, 60, team.teamId)
        assertTrue(meetings.isNotEmpty())
        val sundayBlocks = team.blocks.filter { it.date == sunday }
        meetings.forEach { slot ->
            sundayBlocks.forEach { block ->
                assertTrue(
                    "会议候选必须避开每个人的已排块",
                    slot.endMinute <= block.startMinute || slot.startMinute >= block.endMinute,
                )
            }
        }

        // 10) 周报：只写项目进展，不对个人作评价；私有时间内容不外泄
        val report = team.weeklyReport(LocalDate.of(2026, 11, 9))
        assertTrue(report.progressSummary.first().contains("本周完成"))
        assertTrue(report.evidence.isNotEmpty())
        assertTrue(report.stats.getValue("ledgerEntries") > 0)
        val banned = listOf("评分", "排名", "效率低", "最差", "心理咨询")
        val allLines = report.progressSummary + report.riskRadar + report.nextWeekFocus
        assertTrue(allLines.none { line -> banned.any { it in line } })

        // 11) 事件流完整性：最后一次说事会话包含 接力完成的席位与验收
        val lastEvents = mutableListOf<String>()
        rt.runSession(
            TeamSessionContext(TeamRequestKind.WEEKLY_REPORT, "m-captain", weekStart = LocalDate.of(2026, 11, 9), at = now.plusHours(4)),
            team,
        ) { lastEvents += it.summary }
        assertTrue(lastEvents.any { it.contains("台账官") })
        assertTrue(lastEvents.any { it.startsWith("两级验收通过") })
        assertTrue(lastEvents.any { it.startsWith("协作产出就绪") && it.contains("周报草稿就绪") })
    }

    @Test
    fun `objection against a non-transferred task is rejected`() {
        val team = newTeam()
        val denied = runCatching { team.raiseObjection("m-orange", "market", "没参与过这个任务") }
        assertTrue("只能对转派给自己的任务提异议", denied.isFailure)
    }

    @Test
    fun `liaison agent keeps raw text verbatim in workspace brief`() {
        val team = newTeam()
        val rt = runtime()
        val outcome = rt.runSession(
            TeamSessionContext(TeamRequestKind.MEMBER_UPDATE, "m-ke", rawText = "原型开发做完了", at = now),
            team,
        )
        assertEquals("原型开发做完了", outcome.structuredRequest!!.rawText)
        assertTrue(outcome.structuredRequest!!.kind == UpdateKind.DONE || outcome.structuredRequest!!.kind == UpdateKind.EARLY_DONE)
    }

    @Test
    fun `reporting someone else's task fails the run before any outcome`() {
        val team = newTeam()
        val rt = runtime()
        // 先整周排期并确认，任务分配写回：原型开发 → 小柯
        val plan = rt.runSession(TeamSessionContext(TeamRequestKind.PLAN_WEEK, "m-captain", at = now), team)
        team.commitDraft("m-captain", plan.draft!!)
        assertEquals("m-ke", team.task("prototype").assignedTo)

        val events = mutableListOf<com.swan1127.repland.domain.team.runtime.TeamEvent>()
        val failure = runCatching {
            rt.runSession(TeamSessionContext(TeamRequestKind.STATUS_REPORT, "m-orange", rawText = "原型开发做完了", at = now), team) { events += it }
        }
        assertTrue("替他人报告完成应失败", failure.isFailure)
        assertTrue(events.last() is TeamRunFailed)
        assertTrue(events.none { it is TeamOutcomeReady })
    }
}
