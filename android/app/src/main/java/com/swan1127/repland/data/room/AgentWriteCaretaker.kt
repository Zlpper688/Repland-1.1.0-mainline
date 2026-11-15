package com.swan1127.repland.data.room

import androidx.room.withTransaction
import com.swan1127.repland.domain.model.AgentWriteUndo

/**
 * Whole-batch undo for agent writes: one capture before the batch runs, one restore when the
 * user regrets it. The snapshot is the exact pre-batch rows (current plan + task table), so
 * the restore cannot drift the way per-operation inverse actions do — the same reasoning as
 * [com.swan1127.repland.ui.undo.UndoEntry].
 *
 * Restore leans on two storage invariants: plan rows and their segments are never deleted
 * (replaceCurrentPlan only flips isCurrent), and task inserts are REPLACE. So undoing the
 * plan is a flag flip, and undoing tasks is "delete rows born in the batch, reinsert the
 * pre-batch rows verbatim". Execution logs and usage summaries are history, not state, and
 * stay — matching what the manual batch undo in TaskViewModel already accepts.
 */
class AgentWriteCaretaker(private val database: ReplandDatabase, private val writeGate: PlanWriteGate = PlanWriteGate()) : AgentWriteUndo {

    class Snapshot(
        val plan: PlanWithSegments?,
        val tasks: List<TaskEntity>,
        /** 可用档也算批次写下的东西：登记完发现排错了，撤销要能把空闲/占用一起收回去。 */
        val weeklyBlocks: List<WeeklyTimeBlockEntity>,
        val overrides: List<DateOverrideEntity>,
    )

    override suspend fun capture(): Any? = database.withTransaction {
        Snapshot(
            plan = database.planDao().getCurrentPlanWithSegments(),
            tasks = database.taskDao().getAll(),
            weeklyBlocks = database.timeDao().getAllWeeklyBlocks(),
            overrides = database.timeDao().getAllDateOverrides(),
        )
    }

    override suspend fun restore(token: Any): Boolean {
        val snapshot = token as? Snapshot ?: return false
        writeGate.serialize {
            database.withTransaction {
            val planDao = database.planDao()
            planDao.archiveCurrentPlan()
            snapshot.plan?.let { planDao.setCurrentPlan(it.plan.id) }
            val preBatchIds = snapshot.tasks.map { it.id }.toSet()
            database.taskDao().getAll().filterNot { it.id in preBatchIds }
                .forEach { database.taskDao().deleteById(it.id) }
            snapshot.tasks.forEach { database.taskDao().insert(it) }
            val timeDao = database.timeDao()
            val preBatchBlocks = snapshot.weeklyBlocks.map { it.id }.toSet()
            timeDao.getAllWeeklyBlocks().filterNot { it.id in preBatchBlocks }.forEach { timeDao.deleteWeeklyBlock(it.id) }
            snapshot.weeklyBlocks.forEach { timeDao.insertWeeklyBlock(it) }
            val preBatchOverrides = snapshot.overrides.map { it.id }.toSet()
            timeDao.getAllDateOverrides().filterNot { it.id in preBatchOverrides }.forEach { timeDao.deleteDateOverride(it.id) }
            snapshot.overrides.forEach { timeDao.insertDateOverride(it) }
            }
        }
        return true
    }
}
