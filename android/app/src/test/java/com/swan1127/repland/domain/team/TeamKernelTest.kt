package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 确定性内核单测：档案卡 / 期限引擎 / 调度器 / 台账周报。 */
class TeamKernelTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9) // 周一

    private fun window(start: Int, end: Int) = FreeWindow(start, end)

    private fun weekdayEvenings() = mapOf(
        DayOfWeek.MONDAY to listOf(window(18 * 60, 22 * 60)),
        DayOfWeek.TUESDAY to listOf(window(18 * 60, 22 * 60)),
        DayOfWeek.WEDNESDAY to listOf(window(18 * 60, 22 * 60)),
        DayOfWeek.THURSDAY to listOf(window(18 * 60, 22 * 60)),
        DayOfWeek.FRIDAY to listOf(window(18 * 60, 22 * 60)),
        DayOfWeek.SATURDAY to listOf(window(8 * 60, 22 * 60)),
        DayOfWeek.SUNDAY to listOf(window(8 * 60, 22 * 60)),
    )

    // ---------- 档案卡 ----------

    @Test
    fun `profile card folds unknown fields and flags missing critical slots`() {
        val card = CompetitionProfileCard(
            competitionName = "互联网+校赛",
            fields = mapOf(
                ProfileSlotGroup.BASIC_INFO to listOf(
                    ProfileField("signup_deadline", "报名截止"),
                    ProfileField("member_limit", "人数上限", "5"),
                ),
            ),
            extras = listOf(ExtraEntry("器件清单", "传感器套件 x2")),
        )
        assertTrue(card.field("signup_deadline").isNullOrBlank())
        assertEquals(listOf("报名截止"), card.missingCritical())
        assertEquals(0, card.visibleFields(ProfileSlotGroup.BASIC_INFO).filter { it.key == "signup_deadline" }.size)

        val promoted = card.promoteExtraToSlot(ProfileSlotGroup.RESOURCES, "器件清单", "器件清单")
        assertEquals("传感器套件 x2", promoted.field("器件清单"))
        assertTrue(promoted.extras.isEmpty())
    }

    // ---------- 期限引擎 ----------

    private fun baselineTasks(): List<TeamTask> = listOf(
        TeamTask("market", "市场调研", TaskTier.MID_TERM, "phase_early", estimatedHours = 8.0),
        TeamTask("proposal", "项目书初稿", TaskTier.MID_TERM, "phase_mid", estimatedHours = 12.0, dependsOn = listOf("market")),
        TeamTask("prototype", "原型开发", TaskTier.MID_TERM, "phase_mid", estimatedHours = 16.0, dependsOn = listOf("market")),
        TeamTask("pitch", "路演PPT", TaskTier.MID_TERM, "phase_late", estimatedHours = 8.0, dependsOn = listOf("proposal", "prototype")),
        TeamTask("submit", "提交材料", TaskTier.MID_TERM, "phase_late", estimatedHours = 4.0, rank = TaskRank.MUST, dependsOn = listOf("pitch"), hardDeadline = LocalDate.of(2026, 12, 19)),
    )

    private fun baselinePhases() = listOf(
        Phase("phase_early", "前期", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 14)),
        Phase("phase_mid", "中期", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 5)),
        Phase("phase_late", "后期", LocalDate.of(2026, 12, 6), LocalDate.of(2026, 12, 19)),
    )

    @Test
    fun `deadline engine back-propagates through dependencies`() {
        val deadline = LocalDate.of(2026, 12, 20)
        val result = DeadlineEngine.recompute(baselineTasks(), baselinePhases(), deadline)
        assertTrue(result.issues.isEmpty())
        val byId = result.tasks.associateBy { it.taskId }
        assertEquals(LocalDate.of(2026, 11, 14), byId.getValue("market").computedDeadline)
        assertEquals(LocalDate.of(2026, 12, 5), byId.getValue("proposal").computedDeadline)
        assertEquals(LocalDate.of(2026, 12, 5), byId.getValue("prototype").computedDeadline)
        // 下游提交材料最晚开工 12-17，上游路演 PPT 自动提早到 12-16
        assertEquals(LocalDate.of(2026, 12, 16), byId.getValue("pitch").computedDeadline)
    }

    @Test
    fun `deadline engine flags cycles and deadlines beyond competition`() {
        val cyclic = listOf(
            TeamTask("a", "任务甲", TaskTier.MID_TERM, "p", estimatedHours = 1.0, dependsOn = listOf("b")),
            TeamTask("b", "任务乙", TaskTier.MID_TERM, "p", estimatedHours = 1.0, dependsOn = listOf("a")),
        )
        val phases = listOf(Phase("p", "阶段", today, today.plusDays(7)))
        val cycle = DeadlineEngine.recompute(cyclic, phases, LocalDate.of(2026, 12, 20))
        assertTrue(cycle.hasBlockingIssue)

        val late = listOf(TeamTask("x", "任务丙", TaskTier.LONG_TERM, estimatedHours = 1.0, hardDeadline = LocalDate.of(2027, 1, 1)))
        val flagged = DeadlineEngine.recompute(late, emptyList(), LocalDate.of(2026, 12, 20))
        assertTrue(flagged.issues.any { it.kind == DeadlineEngine.IssueKind.OVER_COMPETITION_DEADLINE })

        val guarded = DeadlineEngine.validateDeadlineSetting("x", "任务丙", LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 20))
        assertNotNull(guarded)
    }

    @Test
    fun `scheduler respects daily capacity`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 2.0)
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 8.0, assignedTo = "m1")
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", listOf(task), listOf(member), startDate = today),
        )
        val placements = output.draft.placements
        assertEquals(4, placements.size)
        placements.forEach { assertTrue("单日不超过容量：${it.date}", it.minutes <= 120) }
        assertTrue(placements.zipWithNext().all { (a, b) -> a.date < b.date })
    }

    @Test
    fun `scheduler marks cross-team soft conflicts red and keeps private blocks invisible`() {
        val member = MemberCard("m1", "小满", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val softBlock = PersonalBlock(
            blockId = "b1", memberId = "m1", date = today, startMinute = 19 * 60, endMinute = 20 * 60,
            sourceTeamId = "other-team", taskId = null, label = "另一队的外出调研",
        )
        val privateBlock = PersonalBlock(
            blockId = "b2", memberId = "m1", date = today, startMinute = 12 * 60, endMinute = 13 * 60,
            sourceTeamId = "other-team", taskId = null, visibility = BlockVisibility.PRIVATE_BUSY, label = "心理咨询",
        )
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 3.0, assignedTo = "m1")
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", listOf(task), listOf(member), existingBlocks = listOf(softBlock, privateBlock), startDate = today),
        )
        val conflicted = output.draft.placements.filter { it.conflicts.isNotEmpty() }
        assertTrue("跨队软块应标红待本人裁决", conflicted.isNotEmpty())
        assertTrue(conflicted.all { it.conflicts.all { b -> b.sourceTeamId != "team" } })
        // 刚性私有块必须避开：正午时段不被占用
        assertTrue(output.draft.placements.none { it.startMinute < 13 * 60 && it.endMinute > 12 * 60 })
    }

    @Test
    fun `personal courses block time and personal confirmed work consumes daily capacity`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val course = PersonalBlock(
            "pc", "m1", today, 18 * 60, 20 * 60, sourceTeamId = null, taskId = null,
            visibility = BlockVisibility.PRIVATE_BUSY,
        )
        val personalWork = PersonalBlock(
            "pw", "m1", today, 20 * 60, 21 * 60, sourceTeamId = null, taskId = null,
            visibility = BlockVisibility.PRIVATE_BUSY, countsTowardCapacity = true,
        )
        val task = TeamTask("t1", "项目书初稿", TaskTier.MID_TERM, estimatedHours = 2.0, assignedTo = "m1")
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput(
                "team", listOf(task), listOf(member),
                startDate = today, personalBusy = listOf(course, personalWork),
            ),
        )
        val dayOne = output.draft.placements.filter { it.date == today }
        assertTrue("个人占用是刚性块：当天只能落在 21:00 以后", dayOne.all { it.startMinute >= 21 * 60 })
        assertTrue("个人已排 1 小时要占掉当天容量", dayOne.sumOf { it.minutes } <= 60)
        assertEquals(2.0, output.scheduledHours, 0.0001)
    }

    @Test
    fun `personal load decides who gets an unassigned team task`() {
        val a = MemberCard("a", "甲", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 4.0)
        val b = MemberCard("b", "乙", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 4.0)
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 2.0)
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput(
                "team", listOf(task), listOf(a, b),
                startDate = today, personalLoadHours = mapOf("a" to 6.0, "b" to 1.0),
            ),
        )
        assertTrue("个人负载更轻的人接活", output.draft.placements.all { it.memberId == "b" })
        assertEquals(6.0, output.personalLoadByMember.getValue("a"), 0.0001)
        assertEquals(1.0, output.personalLoadByMember.getValue("b"), 0.0001)
    }

    @Test
    fun `meeting candidates treat local personal busy as occupied`() {
        val a = MemberCard("a", "小柯", weeklyAvailability = mapOf(DayOfWeek.SUNDAY to listOf(window(17 * 60, 22 * 60))))
        val b = MemberCard("b", "阿橙")
        val sunday = LocalDate.of(2026, 11, 15)
        val busy = PersonalBlock(
            "p1", "a", sunday, 18 * 60, 21 * 60, sourceTeamId = null, taskId = null,
            visibility = BlockVisibility.PRIVATE_BUSY,
        )
        val slots = TeamScheduler.meetingCandidates(
            listOf(a, b), emptyList(), sunday, 60, "team", personalBusy = listOf(busy),
        )
        assertTrue(slots.isNotEmpty())
        slots.forEach { slot -> assertTrue("不得压到个人占用：$slot", slot.endMinute <= 18 * 60 || slot.startMinute >= 21 * 60) }
    }

    @Test
    fun `meeting candidates come from all-member free intersection`() {
        val a = MemberCard("a", "小柯", weeklyAvailability = weekdayEvenings())
        val b = MemberCard("b", "阿橙") // 未导入课表：保守 8-22 全天
        val sunday = LocalDate.of(2026, 11, 15)
        val busy = PersonalBlock("b1", "a", sunday, 18 * 60, 21 * 60, sourceTeamId = "team", taskId = "t1")
        val slots = TeamScheduler.meetingCandidates(listOf(a, b), listOf(busy), sunday, 60, "team")
        assertTrue("全员空闲交集应给出候选", slots.isNotEmpty())
        slots.forEach { slot ->
            assertEquals(listOf("a", "b"), slot.attendeeIds)
            assertTrue(slot.endMinute <= 18 * 60 || slot.startMinute >= 21 * 60)
        }
    }

    @Test
    fun `mid-term tasks never start before their phase window`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val task = TeamTask("proposal", "项目书初稿", TaskTier.MID_TERM, "mid", estimatedHours = 6.0, assignedTo = "m1")
        val phaseStart = today.plusDays(5)
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput(
                "team", listOf(task), listOf(member),
                phases = listOf(Phase("mid", "中期", phaseStart, phaseStart.plusDays(7), kind = StageKind.BUILD)),
                startDate = today,
            ),
        )
        val placements = output.draft.placements
        assertEquals(2, placements.size)
        assertTrue("排期不得早于阶段开始：$placements", placements.all { !it.date.isBefore(phaseStart) })
        assertEquals(phaseStart, placements.first().date)
    }

    @Test
    fun `captain takes work when others are already loaded`() {
        val captain = MemberCard("c", "阿岚", isCaptain = true, weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val member = MemberCard("m", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 2.0)
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput(
                "team", listOf(task), listOf(captain, member),
                startDate = today, personalLoadHours = mapOf("m" to 12.0),
            ),
        )
        assertTrue(output.draft.placements.isNotEmpty())
        assertTrue("队长也是劳动力，不该被无条件排除", output.draft.placements.all { it.memberId == "c" })
    }

    @Test
    fun `downstream waits when upstream has no room`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 2.0)
        val upstream = TeamTask(
            "up", "原型开发", TaskTier.MID_TERM, estimatedHours = 10.0,
            assignedTo = "m1", hardDeadline = today.minusDays(1),
        )
        val downstream = TeamTask(
            "down", "路演PPT", TaskTier.MID_TERM, estimatedHours = 2.0,
            assignedTo = "m1", dependsOn = listOf("up"),
        )
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", listOf(upstream, downstream), listOf(member), startDate = today),
        )
        assertTrue("上游没排进日程，下游不能抢先开工", output.draft.placements.none { it.taskId == "down" })
        assertTrue(output.draft.unassigned.containsAll(listOf("up", "down")))
        assertTrue(output.draft.notes.any { it.contains("上游") })
    }

    @Test
    fun `stage load flags an over-capacity phase`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 3.0)
        val phase = Phase("p", "集中解题", today, today.plusDays(1), kind = StageKind.SOLVE)
        val tasks = listOf(
            TeamTask("a", "建模", TaskTier.MID_TERM, "p", estimatedHours = 4.0, assignedTo = "m1"),
            TeamTask("b", "编程", TaskTier.MID_TERM, "p", estimatedHours = 4.0, assignedTo = "m1"),
        )
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", tasks, listOf(member), phases = listOf(phase), startDate = today),
        )
        val load = output.stageLoads.single()
        assertEquals(8.0, load.demandHours, 0.0001)
        assertEquals(6.0, load.capacityHours, 0.0001)
        assertEquals(StageRisk.OVER, load.risk)
        assertTrue(StagePlanner.notes(output.stageLoads).first().contains("集中解题"))
    }

    @Test
    fun `scheduler never places work into hours that already passed`() {
        val member = MemberCard(
            "m1", "小柯",
            weeklyAvailability = mapOf(
                DayOfWeek.MONDAY to listOf(window(8 * 60, 22 * 60)),
                DayOfWeek.TUESDAY to listOf(window(8 * 60, 22 * 60)),
            ),
            dailyCapacityHours = 4.0,
        )
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 4.0, assignedTo = "m1")
        val now = LocalDateTime.of(today, LocalTime.of(19, 52))
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", listOf(task), listOf(member), startDate = today, now = now),
        )
        val dayOne = output.draft.placements.filter { it.date == today }
        assertTrue(dayOne.isNotEmpty())
        assertTrue("不能排到已经过去的时段：$dayOne", dayOne.all { it.startMinute >= 20 * 60 })
        val nextDay = output.draft.placements.filter { it.date == today.plusDays(1) }
        assertTrue(nextDay.isNotEmpty())
        assertEquals("次日不受今天的起步线影响", 8 * 60, nextDay.first().startMinute)
    }

    @Test
    fun `meeting suggestions rank afternoons across days and skip past times`() {
        val availability = mapOf(
            DayOfWeek.MONDAY to listOf(window(9 * 60, 12 * 60), window(14 * 60, 18 * 60)),
            DayOfWeek.TUESDAY to listOf(window(9 * 60, 12 * 60), window(14 * 60, 18 * 60)),
            DayOfWeek.WEDNESDAY to listOf(window(9 * 60, 12 * 60), window(14 * 60, 18 * 60)),
        )
        val members = listOf(
            MemberCard("a", "小柯", weeklyAvailability = availability),
            MemberCard("b", "阿橙", weeklyAvailability = availability),
        )
        val suggestions = TeamScheduler.meetingSuggestions(
            members = members,
            existingBlocks = emptyList(),
            fromDate = today,
            days = 3,
            durationMinutes = 60,
        )
        assertEquals(6, suggestions.size)
        assertEquals(
            listOf(today, today, today.plusDays(1), today.plusDays(1), today.plusDays(2), today.plusDays(2)),
            suggestions.map { it.slot.date },
        )
        assertTrue(suggestions.all { it.slot.attendeeIds == listOf("a", "b") })
        assertEquals("下午最优先", 3, suggestions.first().score)
        assertEquals(14 * 60, suggestions.first().slot.startMinute)

        val now = LocalDateTime.of(today, LocalTime.of(15, 10))
        val todayOnly = TeamScheduler.meetingSuggestions(
            members = members,
            existingBlocks = emptyList(),
            fromDate = today,
            days = 1,
            durationMinutes = 60,
            now = now,
        )
        assertEquals(16 * 60, todayOnly.single().slot.startMinute)
    }

    @Test
    fun `gate readiness compares pending work with remaining capacity`() {
        val member = MemberCard("m1", "小柯", dailyCapacityHours = 3.0) // 未导入课表：按每日容量保守估
        val phases = listOf(
            Phase("build", "攻关制作", today, today.plusDays(1), kind = StageKind.BUILD),
            Phase("submit", "成文提交", today.plusDays(2), today.plusDays(2), kind = StageKind.WRITE, isGate = true),
        )
        val tasks = listOf(
            TeamTask("a", "论文初稿", TaskTier.MID_TERM, "build", estimatedHours = 2.0, assignedTo = "m1"),
            TeamTask("b", "论文定稿", TaskTier.MID_TERM, "submit", estimatedHours = 5.0, rank = TaskRank.MUST, assignedTo = "m1"),
        )
        val atRisk = GatePlanner.evaluate(tasks, phases, listOf(member), today)
        assertEquals(1, atRisk.size)
        // 3 天 × 3 小时 = 9 小时：够 7 小时，但不到 1.3 倍余量 → 有风险
        assertEquals(GateState.AT_RISK, atRisk.single().state)
        assertEquals(listOf("论文初稿", "论文定稿"), atRisk.single().pendingTasks)
        assertTrue(GatePlanner.notes(atRisk).single().contains("成文提交"))

        val done = GatePlanner.evaluate(
            tasks.map { it.copy(status = TeamTaskStatus.DONE) }, phases, listOf(member), today,
        )
        assertEquals(GateState.READY, done.single().state)

        val overdue = GatePlanner.evaluate(tasks, phases, listOf(member), today.plusDays(3))
        assertEquals(GateState.BLOCKED, overdue.single().state)
    }

    @Test
    fun `applying a stage template seeds phases and baseline tasks together`() {        val team = TeamCollabState.create(
            teamId = "t-tpl", teamName = "模板队", competitionDeadline = LocalDate.of(2026, 12, 20),
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        val plan = CompetitionStages.build(CompetitionStages.mathModeling, today, createdBy = "m-1")
        team.applyStagePlan("m-1", "数模赛", plan.phases, plan.tasks)
        assertEquals(plan.phases.size, team.phases.size)
        assertEquals(plan.tasks.size, team.tasks.size)
        assertTrue(team.phases.any { it.isGate })
        assertTrue(team.phases.any { phase -> phase.moments.any { it.hard } })
        assertEquals(1, team.notifications.count { "套用" in it.message })

        team.addMember(MemberCard("m-2", "小柯"))
        try {
            team.applyStagePlan("m-2", "重复套用", plan.phases, emptyList())
            fail("队员不应能套用环节模板")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("权限"))
        }
    }

    @Test
    fun `scheduler repairs a cyclic dependency graph and reports it`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings())
        val tasks = listOf(
            TeamTask("a", "任务甲", TaskTier.MID_TERM, estimatedHours = 1.0, dependsOn = listOf("b")),
            TeamTask("b", "任务乙", TaskTier.MID_TERM, estimatedHours = 1.0, dependsOn = listOf("a")),
        )
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", tasks, listOf(member), startDate = today),
        )
        // 成环不再整单拒绝：忽略环内依赖，先把能排的排出去，问题交给诊断层。
        assertEquals(setOf("a", "b"), output.draft.placements.map { it.taskId }.toSet())
        assertTrue(output.draft.notes.any { "成环" in it })
        val cycle = output.diagnostics.single { it.code == TeamDiagnostics.DEPENDENCY_CYCLE }
        assertEquals(DiagnosticSeverity.ERROR, cycle.severity)
        assertNotNull(cycle.advice)
    }

    @Test
    fun `template application skips duplicates instead of rejecting the batch`() {
        val team = TeamCollabState.create(
            teamId = "t-skip", teamName = "跳过队", competitionDeadline = null,
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        val plan = CompetitionStages.build(CompetitionStages.mathModeling, today, createdBy = "m-1")
        team.applyStagePlan("m-1", "第一次", plan.phases, plan.tasks)
        val again = team.applyStagePlan("m-1", "第二次", plan.phases, plan.tasks)
        assertEquals(0, again.addedPhases.size)
        assertEquals(0, again.addedTasks.size)
        assertEquals(plan.phases.size + plan.tasks.size, again.skipped.size)
        assertEquals(plan.phases.size, team.phases.size)

        // 坏日期 + 未知阶段：只跳过坏的那几条，好的照常写入
        val ok = Phase("p-ok", "补办环节", today, today.plusDays(5))
        val bad = Phase("p-bad", "倒挂环节", today.plusDays(5), today)
        val dangling = TeamTask("k-dangling", "挂空阶段", TaskTier.MID_TERM, phaseId = "p-none", estimatedHours = 1.0)
        val mixed = team.applyStagePlan("m-1", "混合", listOf(ok, bad), listOf(dangling))
        assertEquals(listOf(ok), mixed.addedPhases)
        assertEquals(2, mixed.skipped.size)
        assertTrue(team.phases.contains(ok))
        assertTrue(team.phases.none { it.phaseId == "p-bad" })
    }

    @Test
    fun `unassigned tasks carry structured diagnostics with advice`() {
        val member = MemberCard("m1", "小柯", weeklyAvailability = weekdayEvenings(), dailyCapacityHours = 1.0)
        val task = TeamTask(
            "t1", "写报告", TaskTier.MID_TERM,
            estimatedHours = 12.0,
            phaseId = null,
            hardDeadline = today.plusDays(1),
        )
        val output = TeamScheduler.schedule(
            TeamScheduler.ScheduleInput("team", listOf(task), listOf(member), startDate = today),
        )
        val diagnostic = output.diagnostics.first { it.targetId == "t1" }
        assertEquals(DiagnosticSeverity.WARN, diagnostic.severity)
        assertNotNull(diagnostic.advice)
        assertTrue(TeamDiagnostics.digest(output.diagnostics).isNotBlank())
    }

    // ---------- 台账与周报 ----------

    @Test
    fun `weekly report is objective and aggregated`() {
        val task = TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 8.0)
        val at = LocalDateTime.of(2026, 11, 10, 10, 0)
        val entries = listOf(
            TeamLedger.recordExecution({ "e1" }, task, "m1", 120, at),
            TeamLedger.recordStatusChange({ "e2" }, task, "m1", TeamTaskStatus.NOT_STARTED, TeamTaskStatus.IN_PROGRESS, at.plusMinutes(30)),
            TeamLedger.recordSaying({ "e3" }, task, "m1", "市场调研做完了", at.plusHours(2)),
        )
        val tasks = listOf(task.copy(status = TeamTaskStatus.IN_PROGRESS))
        val report = TeamLedger.weeklyReport(tasks, entries, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15))
        assertEquals(3, report.evidence.size)
        assertEquals(1, report.stats.getValue("executionBlocks"))
        assertEquals(1, report.stats.getValue("statusChanges"))
        assertTrue(report.progressSummary.first().contains("进行中 1 项"))
        val banned = listOf("评分", "排名", "效率低", "最差")
        assertTrue((report.progressSummary + report.riskRadar + report.nextWeekFocus).none { line -> banned.any { it in line } })
        assertFalse(report.evidence.any { it.evidence.isBlank() })
    }
}
