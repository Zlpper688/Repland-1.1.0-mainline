package com.swan1127.repland.domain.team

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竞赛环节模板：连续窗口、关卡标记、基线任务与阶段内依赖。 */
class CompetitionStagesTest {

    private val start: LocalDate = LocalDate.of(2026, 11, 9)

    @Test
    fun `math modeling template instantiates contiguous gated stages`() {
        val template = CompetitionStages.mathModeling
        val phases = CompetitionStages.instantiate(template, start)
        assertEquals(template.stages.size, phases.size)
        phases.zipWithNext().forEach { (left, right) ->
            assertEquals("环节之间不能有空档或重叠", left.end.plusDays(1), right.start)
        }
        phases.forEachIndexed { index, phase ->
            assertEquals(template.stages[index].kind, phase.kind)
            assertEquals(template.stages[index].isGate, phase.isGate)
            assertEquals(template.stages[index].deliverables, phase.deliverables)
        }
        assertTrue("提交环节是关卡", phases.first { it.kind == StageKind.WRITE }.isGate)
        assertEquals(start, phases.first().start)
        assertEquals(start.plusDays((CompetitionStages.totalDays(template) - 1).toLong()), phases.last().end)
        assertTrue(phases.any { it.deliverables.isNotEmpty() })
    }

    @Test
    fun `baseline tasks stay inside their stage and respect blueprints`() {
        val plan = CompetitionStages.build(CompetitionStages.mathModeling, start, createdBy = "m-captain")
        val phaseIds = plan.phases.map { it.phaseId }.toSet()
        assertTrue(plan.tasks.all { it.phaseId in phaseIds })
        assertEquals(plan.template.stages.sumOf { it.tasks.size }, plan.tasks.size)
        val byId = plan.tasks.associateBy { it.taskId }
        plan.tasks.forEach { task ->
            task.dependsOn.forEach { dep -> assertTrue("依赖必须存在：$dep", dep in byId) }
        }

        // 提交动作要等集中解题的交付全部齐了再开工
        val submit = plan.tasks.first { it.title == "提交与回执确认" }
        val solvePhase = plan.phases.first { it.kind == StageKind.SOLVE }
        val solveTasks = plan.tasks.filter { it.phaseId == solvePhase.phaseId }.map { it.taskId }.toSet()
        assertTrue(submit.dependsOn.containsAll(solveTasks))
        assertEquals(TaskRank.MUST, submit.rank)

        // 普通依赖只落在阶段内，跨环节靠阶段窗口推进
        val draft = plan.tasks.first { it.title == "论文初稿" }
        assertTrue(draft.dependsOn.all { dep -> byId.getValue(dep).phaseId == draft.phaseId })
    }

    @Test
    fun `moments anchor hard deadlines inside their stage windows`() {
        val plan = CompetitionStages.build(CompetitionStages.mathModeling, start)
        val moments = plan.moments
        assertTrue(moments.isNotEmpty())
        assertEquals(moments.sortedBy { it.date }, moments)

        val signupPhase = plan.phases.first { it.kind == StageKind.SIGNUP }
        val signup = moments.first { it.label == "报名截止" }
        assertEquals(signupPhase.end, signup.date)
        assertTrue(signup.hard)

        val solvePhase = plan.phases.first { it.kind == StageKind.SOLVE }
        val solveMoments = moments.filter { it.phaseId == solvePhase.phaseId }
        assertEquals(listOf("定题截止", "模型与结果对齐", "初稿齐"), solveMoments.map { it.label })
        assertEquals(solvePhase.start, solveMoments.first().date)
        assertTrue(solveMoments.last().hard)

        val submit = moments.first { it.label == "提交截止" }
        val writePhase = plan.phases.first { it.kind == StageKind.WRITE }
        assertTrue(submit.date in writePhase.start..writePhase.end)
        assertTrue(submit.hard)
    }

    @Test
    fun `every template is look-up-able and has at least one gate`() {
        CompetitionStages.all.forEach { template ->
            assertEquals(template, CompetitionStages.byId(template.id))
            val phases = CompetitionStages.instantiate(template, start)
            assertTrue(phases.all { !it.end.isBefore(it.start) })
            assertTrue("${template.label} 应有提交/答辩关卡", phases.any { it.isGate })
        }
        assertFalse(CompetitionStages.instantiate(CompetitionStages.generic, start).isEmpty())
    }
}
