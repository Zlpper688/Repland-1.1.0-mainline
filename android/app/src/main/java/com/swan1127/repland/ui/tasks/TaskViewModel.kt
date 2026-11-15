package com.swan1127.repland.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskDraftValidator
import com.swan1127.repland.domain.model.TaskExecutionLog
import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskLifecycleValidator
import com.swan1127.repland.domain.model.TaskInputSource
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.team.TeamPersonalBridge
import com.swan1127.repland.domain.team.reportMirrorCompletion
import com.swan1127.repland.ui.undo.UndoEntry
import java.time.LocalDate
import com.swan1127.repland.domain.ports.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.swan1127.repland.domain.model.ExecutionSession
import com.swan1127.repland.domain.model.ExecutionOutcome
import com.swan1127.repland.domain.ports.ExecutionSessionRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TaskUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val error: TaskError? = null,
    val hasLoaded: Boolean = false,
    val readError: String? = null,
) {
    val isTrusted: Boolean get() = hasLoaded && !isLoading && readError == null
}

enum class TaskError {
    INVALID_DRAFT,
    INVALID_LIFECYCLE_INPUT,
    SAVE_FAILED,
}

data class TaskEditorSaveState(
    val saving: Boolean = false,
    val error: String? = null,
    val receipt: String? = null,
)

enum class TaskMutationKind { START, COMPLETE, POSTPONE, CANCEL, RESTORE, PARTIAL, FEEDBACK, CORRECT, REPLACE }

data class TaskMutationState(
    val kind: TaskMutationKind? = null,
    val taskId: String? = null,
    val logId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val receipt: String? = null,
    val undoable: Boolean = false,
)

class TaskViewModel(
    private val taskRepository: TaskRepository,
    private val sessions: ExecutionSessionRepository? = null,
    private val teamBridge: TeamPersonalBridge? = null,
) : ViewModel() {
    private val undoStore = com.swan1127.repland.ui.undo.UndoStore()

    fun undoLastBatch() {
        if (mutation.value.busy || mutation.value.receipt != null) return
        viewModelScope.launch {
            try {
                val label = undoStore.undo()
                // 空栈不能当成成功：没有回执的「撤销」在用户眼里就是按钮坏了。
                // 回执不再额外套一层书名号——标签自己带，套上会变成「「背单词」回到进行中」。
                mutation.value = TaskMutationState(
                    receipt = label?.let { "已撤销$it。" } ?: "没有可撤销的改动了，当前安排未变动。",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutation.value = TaskMutationState(error = "撤销未完成：任务状态已变化，请手动调整。")
            }
        }
    }

    private val actionError = MutableStateFlow<TaskError?>(null)
    private val editorState = MutableStateFlow(TaskEditorSaveState())
    val editorSaveState: StateFlow<TaskEditorSaveState> = editorState
    private val mutation = MutableStateFlow(TaskMutationState())
    val mutationState: StateFlow<TaskMutationState> = mutation
    private var retryMutation: (() -> Unit)? = null
    fun resetMutation() {
        if (!mutation.value.busy) { mutation.value = TaskMutationState(); retryMutation = null }
    }
    fun retryMutation() { if (!mutation.value.busy && mutation.value.receipt == null) retryMutation?.invoke() }
    fun resetEditorResult() { if (!editorState.value.saving) editorState.value = TaskEditorSaveState() }
    private val sessionMutex = Mutex()
    val sessionBusy = MutableStateFlow(false)
    val sessionError = MutableStateFlow<String?>(null)
    val sessionFinished = MutableStateFlow(false)
    fun dismissSessionResult() { sessionFinished.value = false }
    private val sessionReadRetries = MutableStateFlow(0)
    val sessionReadState = com.swan1127.repland.ui.state.recoverableRead<ExecutionSession?>(
        initial = null, retries = sessionReadRetries, errorMessage = "专注记录读取失败；原会话仍保留，请重新读取后操作。",
        source = { sessions?.observeActive() ?: flowOf(null) },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.swan1127.repland.ui.state.ReadSnapshot(null))
    fun retrySessionRead() {
        if (!sessionReadState.value.isLoading && sessionReadState.value.error != null) sessionReadRetries.value++
    }

    fun startSession(segmentId: String) = sessionAction { it.start(segmentId); sessionFinished.value = false }
    fun startSessionForTask(taskId: String) = sessionAction { it.startForTask(taskId); sessionFinished.value = false }
    fun pauseSession(id: String) = sessionAction { it.pause(id) }
    fun resumeSession(id: String) = sessionAction { it.resume(id) }
    fun finishSession(id: String, outcome: ExecutionOutcome, feedback: TaskFeedback = TaskFeedback()) =
        sessionAction { it.finish(id, outcome, feedback); sessionFinished.value = true }

    /** 盖楼成就：全部执行记录的专注分钟数，每 5 分钟一层。 */
    val totalFocusedMinutes: StateFlow<Int> = taskRepository.observeTotalFocusedMinutes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private fun sessionAction(action: suspend (ExecutionSessionRepository) -> Unit) {
        if (sessionBusy.value) return
        if (sessionReadState.value.error != null || (sessionReadState.value.hasLoaded && sessionReadState.value.isLoading) ||
            uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            sessionError.value = "请先重新读取专注与任务；本轮未改变。"
            return
        }
        sessionBusy.value = true
        viewModelScope.launch {
            sessionMutex.withLock {
                try {
                    check(sessionReadState.value.error == null && !(sessionReadState.value.hasLoaded && sessionReadState.value.isLoading)) {
                        "请先重新读取专注；本轮未改变。"
                    }
                    action(requireNotNull(sessions) { "当前版本未配置专注记录。" })
                    sessionError.value = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    sessionError.value = error.message ?: "本轮未保存，请重试；原记录仍保留。"
                } finally { sessionBusy.value = false }
            }
        }
    }

    private val readRetries = MutableStateFlow(0)
    val uiState: StateFlow<TaskUiState> = com.swan1127.repland.ui.state.recoverableRead(
        initial = emptyList<Task>(), retries = readRetries, errorMessage = "任务读取失败，请重试；未清空任务库。",
        source = taskRepository::observeTasks,
    ).combine(actionError) { read, error ->
        TaskUiState(read.value, read.isLoading, error, read.hasLoaded, read.error)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskUiState(),
        )

    fun retryRead() { if (!uiState.value.isLoading && uiState.value.readError != null) readRetries.value++ }

    fun saveTask(draft: TaskDraft) {
        if (editorState.value.saving || editorState.value.receipt != null) return
        if (uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            editorState.value = TaskEditorSaveState(error = "请先重新读取任务；输入仍保留，本次未保存。")
            return
        }
        if (!TaskDraftValidator.isValid(draft)) {
            actionError.value = TaskError.INVALID_DRAFT
            editorState.value = TaskEditorSaveState(error = "请检查名称、天数和时长，填写内容仍保留。")
            return
        }
        editorState.value = TaskEditorSaveState(saving = true)
        viewModelScope.launch {
            try {
                if (draft.id == null) taskRepository.save(draft) else taskRepository.updateExisting(draft)
                actionError.value = null
                editorState.value = TaskEditorSaveState(receipt = "已保存“${draft.displayName.trim().ifBlank { draft.description.trim() }}”的任务信息；现有时段未重新安排。")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                editorState.value = TaskEditorSaveState()
                throw cancelled
            } catch (error: Exception) {
                actionError.value = TaskError.SAVE_FAILED
                editorState.value = TaskEditorSaveState(error = "保存未成功，填写内容仍保留。请重试；若任务已被移除，请返回任务页。")
            }
        }
    }

    /**
     * Agent 会话建任务固定走 save：draft 自带的 id 会被保留，供随后的排期引用。
     * 在调用方协程里同步落库，紧跟着的 place 才读得到这条任务——批量"建完就排"靠这个顺序。
     * 返回是否真的落库，调用方据此决定还要不要往时间轴上排。
     */
    suspend fun saveAgentTasks(drafts: List<TaskDraft>): Boolean =
        try {
            drafts.forEach { taskRepository.save(it) }
            actionError.value = null
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            actionError.value = TaskError.SAVE_FAILED
            false
        }

    /**
     * 捕获确认面的「排入今天」：给已落库的任务补排期日，并把排期来源标成
     * 已接受建议（ACCEPTED_SUGGESTION）。其余字段的来源保持原样——只有排期这一项
     * 来自用户接受了建议，别的字段不冒充。时段本身由调用方走 placeTaskForAgent 写入。
     */
    suspend fun scheduleTaskForToday(taskId: String, date: LocalDate): Boolean =
        try {
            val task = taskRepository.observeTasks().first().firstOrNull { it.id == taskId } ?: return false
            val draft = TaskDraft(
                id = task.id,
                displayName = task.displayName,
                description = task.description,
                category = task.category,
                userPriority = task.userPriority,
                estimatedDays = task.estimatedDays,
                totalDurationMinutes = task.totalDurationMinutes,
                dueDate = task.dueDate,
                scheduledForDate = date,
            ).copy(inputSources = task.inputSources.copy(scheduledDate = TaskInputSource.ACCEPTED_SUGGESTION))
            taskRepository.updateExisting(draft)
            actionError.value = null
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            actionError.value = TaskError.SAVE_FAILED
            false
        }

    /**
     * 助手改任务走这里。编辑器那套 saving/receipt 是给表单用的，会话只想知道成没成；
     * 校验沿用同一份判据，失败理由要原样带回给模型——一次假成功比一次失败难查得多。
     */
    suspend fun updateAgentTask(draft: TaskDraft): String? =
        if (!TaskDraftValidator.isValid(draft)) "名称、时长或日期不对，这次没有修改。"
        else try {
            taskRepository.updateExisting(draft)
            actionError.value = null
            null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            actionError.value = TaskError.SAVE_FAILED
            "任务信息没有保存，原内容仍保留。"
        }

    fun observeExecutionLogs(taskId: String): kotlinx.coroutines.flow.Flow<List<TaskExecutionLog>> =
        taskRepository.observeExecutionLogs(taskId)

    fun observeExecutionLogsForDate(date: LocalDate): kotlinx.coroutines.flow.Flow<List<TaskExecutionLog>> =
        taskRepository.observeExecutionLogsForDate(date)

    fun startTask(taskId: String) = confirmStatus(taskId, TaskStatus.IN_PROGRESS)

    fun postponeTask(taskId: String, reason: String?) =
        confirmStatus(taskId, TaskStatus.POSTPONED, TaskFeedback(postponeReason = reason))

    fun cancelTask(taskId: String) = confirmStatus(taskId, TaskStatus.CANCELLED)

    fun restoreTask(taskId: String) = confirmStatus(taskId, TaskStatus.NOT_STARTED)

    fun completeTask(
        taskId: String,
        completedContent: String? = null,
        completionResult: String? = null,
        actualDurationMinutes: Int? = null,
        progressPercent: Int? = null,
    ) {
        confirmStatus(
            taskId = taskId,
            status = TaskStatus.COMPLETED,
            feedback = TaskFeedback(
                completedContent = completedContent,
                completionResult = completionResult,
                actualDurationMinutes = actualDurationMinutes,
                progressPercent = progressPercent,
            ),
        )
    }

    fun recordPartialCompletion(taskId: String, feedback: TaskFeedback) = lifecycleAction(TaskMutationKind.PARTIAL, taskId) {
        taskRepository.recordPartialCompletion(taskId, feedback)
    }

    fun recordFeedback(taskId: String, feedback: TaskFeedback) = lifecycleAction(TaskMutationKind.FEEDBACK, taskId) {
        taskRepository.recordFeedback(taskId, feedback)
    }

    fun correctExecutionLog(taskId: String, correctedLogId: String, feedback: TaskFeedback) = lifecycleAction(TaskMutationKind.CORRECT, taskId, correctedLogId) {
        taskRepository.correctExecutionLog(taskId, correctedLogId, feedback)
    }

    fun replaceTask(taskId: String, replacement: TaskDraft) = lifecycleAction(TaskMutationKind.REPLACE, taskId) {
        taskRepository.replaceTask(taskId, replacement)
    }

    private fun confirmStatus(
        taskId: String,
        status: TaskStatus,
        feedback: TaskFeedback = TaskFeedback(),
    ): Unit {
        val before = uiState.value.tasks.find { it.id == taskId }
        // 撤销只在逆向迁移真走得通时才武装。此前 `undoable` 恒为 true 而记录是有条件的：
        // 点不动的「撤销」不只是空操作，栈里若还留着上一件无关改动，它会把那件当成这次撤回。
        // 团队镜像的真相在团队域：个人侧「撤销完成」会被下一次同步按回已完成，
        // 不如不给这个按钮，免得看起来撤销成功、实际什么都没变。
        val rollbackTo = before?.status?.takeIf {
            it != status && !before.isTeamMirror && TaskLifecycleValidator.isValidStatusConfirmation(status, it)
        }
        lifecycleAction(when (status) {
            TaskStatus.IN_PROGRESS -> TaskMutationKind.START
            TaskStatus.COMPLETED -> TaskMutationKind.COMPLETE
            TaskStatus.POSTPONED -> TaskMutationKind.POSTPONE
            TaskStatus.CANCELLED -> TaskMutationKind.CANCEL
            TaskStatus.NOT_STARTED -> TaskMutationKind.RESTORE
            TaskStatus.REPLACED -> TaskMutationKind.REPLACE
        }, taskId, undoableOnSuccess = rollbackTo != null) {
            // 这里不按 uiState 判存在：列表可能还没读回来，仓库的 requireTask 才是权威校验。
            taskRepository.confirmStatus(taskId, status, feedback)
            if (status == TaskStatus.COMPLETED) reportTeamCompletion(taskId, feedback)
            // 单条状态改动此前只有批量链路能撤销，点错一次顺延就只能一路改回去；
            // 状态是唯一真相，退回原状态即可，时段可见性跟着状态一起回来。
            if (rollbackTo != null) {
                undoStore.record(
                    UndoEntry(
                        "「${before.displayName}」${statusWord(before.status)}",
                        applyBefore = { taskRepository.confirmStatus(taskId, rollbackTo) },
                        applyAfter = { taskRepository.confirmStatus(taskId, status, feedback) },
                    ),
                )
            }
        }
    }

    /** 个人侧完成团队镜像后回传团队域；回传失败不撤销个人记录，团队页仍保留原状态。 */
    private suspend fun reportTeamCompletion(taskId: String, feedback: TaskFeedback) {
        teamBridge.reportMirrorCompletion(taskId, feedback.completedContent ?: feedback.completionResult)
    }

    private fun statusWord(status: TaskStatus): String = when (status) {
        TaskStatus.NOT_STARTED -> "还是未开始"
        TaskStatus.IN_PROGRESS -> "回到进行中"
        TaskStatus.COMPLETED -> "回到已完成"
        TaskStatus.POSTPONED -> "回到已顺延"
        TaskStatus.CANCELLED -> "回到已取消"
        TaskStatus.REPLACED -> "已被替换"
    }

    fun confirmStatuses(action: BatchTaskAction, selection: TaskSelection) = batchLifecycle(action) {
        val targets = BatchTasks.targets(action, selection, uiState.value.tasks)
        val previous = targets.associate { it.id to it.status }
        targets.forEach { taskRepository.confirmStatus(it.id, action.status, TaskFeedback()) }
        if (action.status == TaskStatus.COMPLETED) {
            targets.forEach { reportTeamCompletion(it.id, TaskFeedback()) }
        }
        val undoLabel = when (action) {
            BatchTaskAction.COMPLETE -> "批量完成"
            BatchTaskAction.CANCEL -> "批量取消"
            BatchTaskAction.RESTORE -> "批量恢复"
        }
        // 团队镜像不参与批量撤销：团队状态不会跟着退回，撤销只会被同步覆盖。
        if (targets.any { it.isTeamMirror }) return@batchLifecycle targets.size
        undoStore.record(
            UndoEntry(
                "「$undoLabel ${targets.size} 项」",
                applyBefore = {
                    previous.forEach { (id, prev) ->
                        // The lifecycle gate only allows COMPLETED/CANCELLED -> NOT_STARTED, so a
                        // row that was in progress or postponed before the batch restores as
                        // not-started rather than throwing mid-pass.
                        val back = if (TaskLifecycleValidator.isValidStatusConfirmation(action.status, prev)) {
                            prev
                        } else {
                            TaskStatus.NOT_STARTED
                        }
                        taskRepository.confirmStatus(id, back, TaskFeedback())
                    }
                },
                applyAfter = {
                    previous.keys.forEach { id -> taskRepository.confirmStatus(id, action.status, TaskFeedback()) }
                },
            )
        )
        targets.size
    }

    private fun batchLifecycle(action: BatchTaskAction, writes: suspend () -> Int) {
        val kind = when (action.status) {
            TaskStatus.COMPLETED -> TaskMutationKind.COMPLETE
            TaskStatus.CANCELLED -> TaskMutationKind.CANCEL
            TaskStatus.NOT_STARTED -> TaskMutationKind.RESTORE
            else -> return
        }
        if (mutation.value.busy || mutation.value.receipt != null) return
        retryMutation = { batchLifecycle(action, writes) }
        if (uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            mutation.value = TaskMutationState(kind, error = "请先重新读取任务；输入和原记录仍保留。")
            return
        }
        mutation.value = TaskMutationState(kind, busy = true)
        viewModelScope.launch {
            try {
                val applied = writes()
                actionError.value = null
                val label = when (action) {
                    BatchTaskAction.COMPLETE -> "完成"
                    BatchTaskAction.CANCEL -> "取消"
                    BatchTaskAction.RESTORE -> "恢复"
                }
                mutation.value = TaskMutationState(kind, receipt = "已对 $applied 项任务执行「$label」。", undoable = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutation.value = TaskMutationState(kind)
                retryMutation = null
                throw cancelled
            } catch (error: Exception) {
                actionError.value = TaskError.INVALID_LIFECYCLE_INPUT
                mutation.value = TaskMutationState(kind,
                    error = "批量操作未完成，任务状态保持原样。请重试；若列表已变化，请重新选择后再试。")
            }
        }
    }

    private fun lifecycleAction(
        kind: TaskMutationKind,
        taskId: String,
        logId: String? = null,
        undoableOnSuccess: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (mutation.value.busy || mutation.value.receipt != null) return
        retryMutation = { lifecycleAction(kind, taskId, logId, undoableOnSuccess, action) }
        if (uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            mutation.value = TaskMutationState(kind, taskId, logId, error = "请先重新读取任务；输入和原记录仍保留。")
            return
        }
        mutation.value = TaskMutationState(kind, taskId, logId, busy = true)
        viewModelScope.launch {
            try {
                action()
                actionError.value = null
                val message = when (kind) {
                    TaskMutationKind.START -> "已记录开始；不会根据时间经过自动完成。"
                    TaskMutationKind.COMPLETE -> "已确认完成；不会以计划时长或计时推断实际耗时。"
                    TaskMutationKind.POSTPONE -> "已顺延；没有替你挑新的时段。"
                    TaskMutationKind.CANCEL -> "已取消任务，执行历史仍保留。"
                    TaskMutationKind.RESTORE -> "已恢复为未开始，执行历史仍保留。"
                    TaskMutationKind.PARTIAL -> "已保存部分进度；未将整个任务标记完成。"
                    TaskMutationKind.FEEDBACK -> "已保存反馈；任务状态未改变。"
                    TaskMutationKind.CORRECT -> "已追加纠正记录；原始历史仍保留。"
                    TaskMutationKind.REPLACE -> "已保存替换事项；原任务和历史仍保留。"
                }
                mutation.value = TaskMutationState(kind, taskId, logId, receipt = message, undoable = undoableOnSuccess)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutation.value = TaskMutationState(kind, taskId, logId)
                retryMutation = null
                throw cancelled
            } catch (error: Exception) {
                actionError.value = TaskError.INVALID_LIFECYCLE_INPUT
                mutation.value = TaskMutationState(kind, taskId, logId,
                    error = "本次记录未保存，输入仍保留。请重试；若原任务或记录已不存在，请关闭后重新选择。")
            }
        }
    }

    class Factory(
        private val taskRepository: TaskRepository,
        private val sessions: ExecutionSessionRepository? = null,
        private val teamBridge: TeamPersonalBridge? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(TaskViewModel::class.java))
            return TaskViewModel(taskRepository, sessions, teamBridge) as T
        }
    }
}
