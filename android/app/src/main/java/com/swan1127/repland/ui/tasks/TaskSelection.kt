package com.swan1127.repland.ui.tasks

import androidx.annotation.StringRes
import com.swan1127.repland.R
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskLifecycleValidator
import com.swan1127.repland.domain.model.TaskStatus

/**
 * The rows the user has ticked in the task list.
 *
 * A set of ids rather than a set of [Task]s: the repository re-emits the list on every write, so
 * holding objects would pin stale copies. The invariant `selection ⊆ visible` holds by
 * construction — [allOf] takes the visible list, [toggle] adds a row the user just tapped, and
 * [retained] drops anything that has left the view — which is what makes "batch-act on something
 * you cannot see" impossible rather than merely unlikely.
 */
@JvmInline
value class TaskSelection(val ids: Set<String>) {
    val size: Int get() = ids.size
    val isEmpty: Boolean get() = ids.isEmpty()

    fun contains(id: String): Boolean = id in ids

    fun toggle(id: String): TaskSelection =
        TaskSelection(if (id in ids) ids - id else ids + id)

    /** Drag-select paints rows it passes over; it never unticks what the user already chose. */
    fun added(id: String): TaskSelection = TaskSelection(ids + id)

    fun allOf(visible: List<Task>): TaskSelection =
        TaskSelection(visible.mapTo(mutableSetOf(), Task::id))

    /** 反选只在当前看得见的行上取反，不会把筛选掉的行悄悄拉进批量操作。 */
    fun inverted(visible: List<Task>): TaskSelection {
        val visibleIds = visible.mapTo(mutableSetOf(), Task::id)
        return TaskSelection(visibleIds - ids)
    }

    /** Drops ids that have left the current filter, so the action bar count stays honest. */
    fun retained(visible: List<Task>): TaskSelection {
        val visibleIds = visible.mapTo(mutableSetOf(), Task::id)
        return TaskSelection(ids intersect visibleIds)
    }

    companion object {
        val EMPTY: TaskSelection = TaskSelection(emptySet())
    }
}

/**
 * A lifecycle change the user can apply to a whole selection.
 *
 * [status] is the confirmation to write and [labelRes] is its button text: the eligibility rule
 * and the wording have to describe the same thing.
 */
enum class BatchTaskAction(
    val status: TaskStatus,
    @param:StringRes val labelRes: Int,
) {
    COMPLETE(TaskStatus.COMPLETED, R.string.batch_action_complete),
    CANCEL(TaskStatus.CANCELLED, R.string.batch_action_cancel),
    RESTORE(TaskStatus.NOT_STARTED, R.string.batch_action_restore),
}

/**
 * Which rows a batch action would touch, and which actions are worth offering.
 *
 * Eligibility is read from [TaskLifecycleValidator.isValidStatusConfirmation] — the same rule
 * [com.swan1127.repland.data.room.RoomTaskRepository.confirmStatus] enforces — so a batch write
 * cannot be rejected halfway. REPLACED rows are the case that matters: they cannot be restored,
 * so the target count can be smaller than the selection and the confirm dialog names both.
 */
object BatchTasks {
    fun targets(
        action: BatchTaskAction,
        selection: TaskSelection,
        visible: List<Task>,
    ): List<Task> = visible.filter { task ->
        selection.contains(task.id) &&
            TaskLifecycleValidator.isValidStatusConfirmation(task.status, action.status)
    }

    /** Only actions that would change something — never a button whose confirm says "0 tasks". */
    fun actionsFor(selection: TaskSelection, visible: List<Task>): List<BatchTaskAction> =
        BatchTaskAction.entries.filter { action -> targets(action, selection, visible).isNotEmpty() }
}
