package com.swan1127.repland

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swan1127.repland.data.room.PlanWriteGate
import com.swan1127.repland.data.room.ReplandDatabase
import com.swan1127.repland.data.room.RoomAutonomyRepository
import com.swan1127.repland.data.room.RoomExecutionSessionRepository
import com.swan1127.repland.data.room.RoomPlanRepository
import com.swan1127.repland.data.room.RoomTaskRepository
import com.swan1127.repland.data.room.RoomTeamPersonalBridge
import com.swan1127.repland.data.room.RoomTimeRepository
import com.swan1127.repland.data.team.TeamSessionStore
import com.swan1127.repland.domain.model.AutonomousStatusCommand
import com.swan1127.repland.domain.model.AutonomousTaskChange
import com.swan1127.repland.domain.model.AutonomousTaskTarget
import com.swan1127.repland.domain.model.ExecutionOutcome
import com.swan1127.repland.domain.model.SegmentAuthority
import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskOrigin
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.domain.team.TaskTier
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 团队 → 个人的落库行为：镜像任务、锁定时段、冲突跳过、完成回传。 */
class RoomTeamPersonalBridgeTest {

    private lateinit var database: ReplandDatabase
    private lateinit var store: TeamSessionStore
    private lateinit var bridge: RoomTeamPersonalBridge
    private val today = LocalDate.now()
    private val blockDay = today.plusDays(1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReplandDatabase::class.java).build()
        store = TeamSessionStore(database.teamSessionDao(), context)
        bridge = RoomTeamPersonalBridge(database, PlanWriteGate(), store)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun state(taskStatus: TeamTaskStatus = TeamTaskStatus.NOT_STARTED): TeamCollabState {
        val state = TeamCollabState.create(
            "team-1", "星火队", today.plusDays(30), MemberCard("m1", "小柯", isCaptain = true),
        )
        state.addMember(MemberCard("m2", "阿橙"))
        state.setBaseline(
            "m1", emptyList(),
            listOf(TeamTask("t1", "市场调研", TaskTier.MID_TERM, estimatedHours = 8.0,
                status = taskStatus, assignedTo = "m1")),
        )
        state.blocks += PersonalBlock(
            "b1", "m1", blockDay, 18 * 60, 20 * 60, sourceTeamId = "team-1", taskId = "t1",
        )
        return state
    }

    @Test
    fun sync_writes_mirror_task_locked_segment_and_order() = runBlocking {
        val team = state()
        store.save(team)

        val report = bridge.sync(team, "m1", today)

        assertEquals(1, report.mirroredTasks)
        assertEquals(1, report.scheduledSegments)
        val mirrorId = "team:team-1:t1"
        val mirror = RoomTaskRepository(database).observeTasks().first().single { it.id == mirrorId }
        assertEquals(TaskOrigin.TEAM, mirror.origin)
        assertEquals("team-1", mirror.originRef)
        assertEquals("星火队", mirror.originLabel)
        assertEquals(TaskStatus.NOT_STARTED, mirror.status)

        val plan = RoomPlanRepository(database).observeCurrentPlan().first()
        assertNotNull(plan)
        val segment = plan!!.segments.single()
        assertEquals(mirrorId, segment.taskId)
        assertEquals(blockDay, segment.date)
        assertTrue(segment.isLocked)
        assertEquals(SegmentAuthority.USER_PINNED, segment.authority)
        assertEquals("team", segment.trackId)
        assertTrue(plan.orderedTaskIds.contains(mirrorId))

        // 再同步一次是幂等的：不新增任务，也不重复落段。
        val again = bridge.sync(team, "m1", today)
        assertEquals(0, again.mirroredTasks)
        assertEquals(1, again.scheduledSegments)
        assertEquals(1, RoomPlanRepository(database).observeCurrentPlan().first()!!.segments.size)
    }

    @Test
    fun sync_skips_segments_that_hit_personal_course_time() = runBlocking {
        val team = state()
        store.save(team)
        bridge.sync(team, "m1", today)
        RoomTimeRepository(database.timeDao()).saveWeeklyBlock(
            WeeklyTimeBlockDraft(
                title = "高数", kind = TimeBlockKind.COURSE, dayOfWeek = blockDay.dayOfWeek,
                startMinute = 18 * 60, endMinute = 20 * 60,
            ),
        )

        val report = bridge.sync(team, "m1", today)

        assertEquals(1, report.skippedConflicts)
        assertEquals(0, report.scheduledSegments)
        assertNotNull(report.note())
        assertTrue(RoomPlanRepository(database).observeCurrentPlan().first()!!.segments.isEmpty())
    }

    @Test
    fun personal_completion_reports_back_to_team_with_ledger_evidence() = runBlocking {
        val team = state()
        store.save(team)
        bridge.sync(team, "m1", today)

        assertTrue(bridge.reportCompletion("team-1", "t1", "调研报告已交给队长"))

        val updated = store.read("team-1")!!
        assertEquals(TeamTaskStatus.DONE, updated.tasks.single().status)
        assertTrue(updated.ledger.any { it.taskId == "t1" && it.evidence.contains("已完成") })
        assertTrue(updated.ledger.any { it.taskId == "t1" && it.evidence.contains("调研报告已交给队长") })
        val mirror = RoomTaskRepository(database).observeTasks().first().single { it.id == "team:team-1:t1" }
        assertEquals(TaskStatus.COMPLETED, mirror.status)
    }

    @Test
    fun tasks_leaving_the_baseline_become_cancelled_mirrors() = runBlocking {
        val team = state()
        store.save(team)
        bridge.sync(team, "m1", today)
        team.updateTask(team.tasks.single().copy(status = TeamTaskStatus.WONT))
        store.save(team)

        val report = bridge.sync(team, "m1", today)

        assertEquals(1, report.cancelledTasks)
        val mirror = RoomTaskRepository(database).observeTasks().first().single { it.id == "team:team-1:t1" }
        assertEquals(TaskStatus.CANCELLED, mirror.status)
    }

    @Test
    fun assistant_completion_reports_back_to_team() = runBlocking {
        val team = state()
        store.save(team)
        bridge.sync(team, "m1", today)

        val autonomy = RoomAutonomyRepository(database, PlanWriteGate(), bridge)
        autonomy.updateStatus(
            listOf(
                AutonomousStatusCommand(
                    AutonomousTaskTarget.Named("市场调研"),
                    AutonomousTaskChange.Complete,
                    "调研报告交给了队长",
                ),
            ),
            "assistant-complete",
        )

        val updated = store.read("team-1")!!
        assertEquals(TeamTaskStatus.DONE, updated.tasks.single().status)
        assertTrue(updated.ledger.any { it.taskId == "t1" && it.evidence.contains("调研报告交给了队长") })
    }

    @Test
    fun focus_session_completion_reports_back_to_team() = runBlocking {
        val team = state()
        store.save(team)
        bridge.sync(team, "m1", today)
        val segment = RoomPlanRepository(database).observeCurrentPlan().first()!!.segments.single()

        val sessions = RoomExecutionSessionRepository(database, bridge)
        sessions.start(segment.id)
        val active = sessions.observeActive().first()!!
        sessions.finish(active.id, ExecutionOutcome.COMPLETED, TaskFeedback(completedContent = "专注做完了调研"))

        val updated = store.read("team-1")!!
        assertEquals(TeamTaskStatus.DONE, updated.tasks.single().status)
        assertTrue(updated.ledger.any { it.taskId == "t1" && it.evidence.contains("专注做完了调研") })
        val mirror = RoomTaskRepository(database).observeTasks().first().single { it.id == "team:team-1:t1" }
        assertEquals(TaskStatus.COMPLETED, mirror.status)
    }
}
