package com.swan1127.repland.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.domain.planteam.PlanTeamResult
import com.swan1127.repland.domain.planteam.runtime.TeamEvent
import com.swan1127.repland.domain.ports.PlanRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlanUiState(
    val currentPlan: ConfirmedPlan? = null,
    val planHistory: List<ConfirmedPlan> = emptyList(),
    val draft: PlanDraft? = null,
    val errorMessage: String? = null,
    val isWorking: Boolean = false,
    val taskOrder: List<String> = emptyList(),
    val assistantReceipt: AssistantSaveReceipt? = null,
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val readError: String? = null,
    val readAttempt: Int = 0,
    val draftEditError: String? = null,
    val draftEditReceipt: String? = null,
    val pendingManualChange: PendingManualPlanChange? = null,
    val failedChange: ManualPlanChange? = null,
    val teamEvents: List<TeamEvent> = emptyList(),
    val teamResult: PlanTeamResult? = null,
    val isGenerating: Boolean = false,
) {
    val isTrusted get() = hasLoaded && !isLoading && readError == null
}

data class InteractionWorkspaceUiState(
    val isLoading: Boolean = true,
    val tracks: List<RhythmTrack> = RhythmTracks.defaults,
    val assistant: AssistantWorkspace? = null,
    val canUndoOrder: Boolean = false,
    val hasLoaded: Boolean = false,
    val readError: String? = null,
    val readAttempt: Int = 0,
) {
    val isTrusted get() = hasLoaded && !isLoading && readError == null
}

class PlanViewModel(
    private val planRepository: PlanRepository,
    private val planDraftGenerator: PlanDraftGenerator,
    private val timeRepository: com.swan1127.repland.domain.ports.TimeRepository? = null,
    private val taskRepository: com.swan1127.repland.domain.ports.TaskRepository? = null,
    private val operations: PlanningOperationService? = null,
) : ViewModel() {
    private val operation = MutableStateFlow<Pair<String?, Boolean>>(null to false)
    private val mutex = Mutex()
    private val assistantReceipt = MutableStateFlow<AssistantSaveReceipt?>(null)
    private val draftEditFeedback = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val draftEditSaving = MutableStateFlow(false)
    private val pendingManualChange = MutableStateFlow<PendingManualPlanChange?>(null)
    private val failedManualChange = MutableStateFlow<ManualPlanChange?>(null)
    private var manualChangeSaved: (() -> Unit)? = null
    private var generationJob: Job? = null
    private val teamPreview = operations?.teamPreview ?: MutableStateFlow(PlanningTeamPreview())
    fun clearDraftEditFeedback() { if (!operation.value.second && !draftEditSaving.value) draftEditFeedback.value = null to null }
    fun dismissAssistantReceipt() { assistantReceipt.value = null }
    suspend fun queryTasks(scope: TaskQueryScope, date: LocalDate): List<Task> = requireNotNull(operations).query(scope, date)
    suspend fun explainOrder(taskId: String): LocalPriorityAssessment = requireNotNull(operations).explainOrder(taskId)
    private val readRetries = MutableStateFlow(0)
    val workspaceUiState = com.swan1127.repland.ui.state.recoverableRead(
        initial = InteractionWorkspaceUiState(), retries = readRetries, errorMessage = "轨道与助手草稿读取失败；原设置仍保留。本页输入暂不写入，请重试。",
    ) {
        val attempt = readRetries.value
        combine(planRepository.observeTracks(), planRepository.observeAssistantWorkspace(), planRepository.observeCanUndoTaskOrder()) { tracks, assistant, canUndo ->
            InteractionWorkspaceUiState(false, tracks, assistant, canUndo, hasLoaded = true, readAttempt = attempt)
        }
    }.map { read -> read.value.copy(isLoading = read.isLoading, hasLoaded = read.hasLoaded, readError = read.error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InteractionWorkspaceUiState())

    fun saveTracks(tracks: List<RhythmTrack>) = mutate { planRepository.saveTracks(tracks) }
    fun saveAssistantWorkspace(value: AssistantWorkspace) = mutate { planRepository.saveAssistantWorkspace(value) }
    fun undoTaskOrder() = mutate { planRepository.undoTaskOrder() }
    private val orderUndoOffer = MutableStateFlow(false)
    val canOfferOrderUndo: StateFlow<Boolean> = orderUndoOffer
    fun clearOrderUndoOffer() { orderUndoOffer.value = false }
    val uiState: StateFlow<PlanUiState> = com.swan1127.repland.ui.state.recoverableRead(
        initial = PlanUiState(), retries = readRetries, errorMessage = "计划与顺序读取失败；未清空现有计划或预览，请重试。",
    ) {
        val attempt = readRetries.value
        combine(planRepository.observeCurrentPlan(), planRepository.observePlanHistory(),
            planRepository.observeDraft(), planRepository.observeTaskOrder()) { current, history, draft, order ->
            PlanUiState(currentPlan = current, planHistory = history, draft = draft, taskOrder = order,
                isLoading = false, hasLoaded = true, readAttempt = attempt)
        }
    }.combine(operation) { read, operation -> read.value.copy(isLoading = read.isLoading, hasLoaded = read.hasLoaded,
        readError = read.error, errorMessage = operation.first, isWorking = operation.second) }
        .combine(assistantReceipt) { state, receipt -> state.copy(assistantReceipt = receipt) }
        .combine(draftEditFeedback) { state, feedback -> state.copy(draftEditError = feedback.first, draftEditReceipt = feedback.second) }
        .combine(draftEditSaving) { state, saving -> state.copy(isWorking = state.isWorking || saving) }
        .combine(pendingManualChange) { state, pending -> state.copy(pendingManualChange = pending) }
        .combine(failedManualChange) { state, failed -> state.copy(failedChange = failed) }
        .combine(teamPreview) { state, team ->
            val matches = team.draft == state.draft
            state.copy(
                teamEvents = if (team.isRunning || matches) team.events else emptyList(),
                teamResult = team.result.takeIf { matches },
                isGenerating = team.isRunning,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    fun retryRead() {
        if ((!uiState.value.isLoading && uiState.value.readError != null) ||
            (!workspaceUiState.value.isLoading && workspaceUiState.value.readError != null)) readRetries.value++
    }

    fun generateDraft(
        tasks: List<Task>,
        weeklyBlocks: List<WeeklyTimeBlock>,
        dateOverrides: List<DateOverride>,
        semesterFirstWeekMonday: LocalDate?,
        lockedSegments: List<PlannedSegment>,
        categoryPreferences: Map<TaskCategory, Int>,
        manualTaskOrder: List<String>,
        orderOnly: Boolean = false,
        todayOnly: LocalDate? = null,
        reorder: Boolean = false,
    ) {
        val input = PlanGenerationInput(tasks, weeklyBlocks, dateOverrides, semesterFirstWeekMonday,
            lockedSegments, categoryPreferences, manualTaskOrder)
        val revision = PlanningRevision.of(input, uiState.value.currentPlan, uiState.value.taskOrder)
        mutate(trackGeneration = true) {
            if (operations != null) {
                require(todayOnly == null || todayOnly == LocalDate.now()) { "安排今天只操作今天，请切换到今天后重试。" }
                operations.preview(when {
                    orderOnly -> PlanningPreviewKind.SORT_ONLY
                    todayOnly != null -> PlanningPreviewKind.ARRANGE_TODAY
                    reorder -> PlanningPreviewKind.FORMULATE
                    else -> PlanningPreviewKind.REPLAN_REMAINING
                })
                return@mutate
            }
            val result = if (todayOnly == null) planDraftGenerator.generateReplan(input, uiState.value.currentPlan) else {
                require(todayOnly == LocalDate.now()) { "安排今天只操作今天，请切换到今天后重试。" }
                PlanGenerator.generateToday(input, uiState.value.currentPlan)
            }
            planRepository.saveDraft(result.copy(
                sourceRevision = revision, orderOnly = orderOnly,
                segments = if (orderOnly) emptyList() else result.segments,
                unscheduledTasks = if (orderOnly) emptyList() else result.unscheduledTasks,
            ))
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    fun clearTeamMemory() {
        cancelGeneration()
        operations?.clearTeamMemory()
    }

    fun discardDraft() {
        cancelGeneration()
        mutate { planRepository.saveDraft(null) }
    }

    fun saveAvailabilityAndGenerate(value: QuickAvailability, categoryPreferences: Map<TaskCategory, Int>, onSaved: () -> Unit) {
        if (operation.value.second) return
        operation.value = null to true
        mutate(trackGeneration = true) {
            require(value.isValid()) { "请填写今天或未来的日期及有效起止时间。" }
            val time = requireNotNull(timeRepository)
            // 与助手那条 set_available_time 同一映射：free=false 是「班会、值班」这类占用，
            // 登记成 AVAILABLE 就等于当着用户的面把锁着的时段说成空的。
            val title = value.title?.trim()?.takeIf(String::isNotBlank) ?: when {
                value.free && value.repeatWeekly -> "每周可用时间"
                value.free -> "本次可用时间"
                value.repeatWeekly -> "每周固定安排"
                else -> "临时安排"
            }
            if (value.repeatWeekly) {
                val exists = time.observeWeeklyBlocks().first().any {
                    (it.kind == TimeBlockKind.AVAILABLE) == value.free && it.weekPattern == null &&
                    it.dayOfWeek == value.date.dayOfWeek && it.startMinute == value.startMinute && it.endMinute == value.endMinute }
                if (!exists) time.saveWeeklyBlock(WeeklyTimeBlockDraft(title = title, kind = if (value.free) TimeBlockKind.AVAILABLE else TimeBlockKind.OTHER,
                    dayOfWeek = value.date.dayOfWeek, startMinute = value.startMinute, endMinute = value.endMinute,
                    trackId = if (value.free) "focus" else "fixed"))
            } else {
                val type = if (value.free) DateOverrideType.AVAILABLE else DateOverrideType.BLOCKED
                val exists = time.observeDateOverrides().first().any { it.type == type && it.date == value.date &&
                    it.startMinute == value.startMinute && it.endMinute == value.endMinute }
                if (!exists) time.saveDateOverride(DateOverrideDraft(title = title, type = type,
                    date = value.date, startMinute = value.startMinute, endMinute = value.endMinute))
            }
            // Re-read after saving; generated revision uses persisted IDs, not temporary UI placeholders.
            val current = planRepository.observeCurrentPlan().first()
            val order = planRepository.observeTaskOrder().first()
            val input = PlanGenerationInput(requireNotNull(taskRepository).observeTasks().first(), time.observeWeeklyBlocks().first(),
                time.observeDateOverrides().first(), time.observeTimeConstraintSettings().first().semesterFirstWeekMonday,
                categoryPreferences = categoryPreferences)
            try {
                if (operations != null) operations.preview(PlanningPreviewKind.FORMULATE) else {
                    val result = planDraftGenerator.generateReplan(input, current)
                    planRepository.saveDraft(result.copy(sourceRevision = PlanningRevision.of(input, current, order)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalArgumentException("可用时间已保存，但预览未生成；请重试，原计划保持不变。", error)
            }
            onSaved()
        }
    }

    fun showAgentDraft(agentDraft: PlanDraft) = mutate {
        if (uiState.value.draft == null) planRepository.saveDraft(agentDraft)
    }

    fun updateDraft(updatedDraft: PlanDraft) = mutate {
        if (uiState.value.draft != null) planRepository.saveDraft(updatedDraft)
    }

    fun saveDraftEdit(expected: PlanDraft, updated: PlanDraft, requestId: String) {
        // Synchronous guard prevents duplicate taps before UI state has recomposed.
        if (operation.value.second || draftEditSaving.value) return
        if (readUnavailable()) {
            draftEditFeedback.value = "请先重新读取草案；本次未保存，输入仍保留。" to null
            return
        }
        draftEditSaving.value = true
        operation.value = null to true
        draftEditFeedback.value = null to null
        viewModelScope.launch {
            try {
                mutex.withLock {
                    require(!readUnavailable()) { "请先重新读取草案；本次未保存，输入仍保留。" }
                    planRepository.replaceDraftIfCurrent(expected, updated)
                    // Receipt stays in the ViewModel so a recreated editor can observe it.
                    draftEditFeedback.value = null to requestId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                draftEditFeedback.value = (if (error is IllegalArgumentException) error.message
                    ?: "请检查这次修改后重试。" else "分段未保存，输入仍保留；请重试保存。") to null
            } finally {
                draftEditSaving.value = false
                operation.value = operation.value.first to false
            }
        }
    }

    fun acceptDraft(onAccepted: (() -> Unit)? = null) {
        val accepted = uiState.value.draft ?: return
        mutate {
            // A queued second click must not apply an already confirmed draft again.
            if (uiState.value.draft != accepted) return@mutate
            val orderBefore = planRepository.observeTaskOrder().first()
            if (operations != null) operations.confirm(accepted) else planRepository.accept(accepted)
            // 顺序真的变了才提撤销。判断放在写入之后同一段协程里，界面不必猜何时读完。
            if (planRepository.observeTaskOrder().first() != orderBefore) orderUndoOffer.value = true
            onAccepted?.invoke()
        }
    }

    fun saveTasksAndPlace(tasks: List<TaskDraft>, segments: List<PlannedSegment>, onSaved: () -> Unit) =
        mutate {
            if (operations != null) operations.confirmChanges(tasks, segments, emptySet(), segments.firstOrNull()?.date ?: LocalDate.now())
            else planRepository.saveTasksAndPlace(tasks, segments)
            onSaved()
        }
    fun saveAssistantChanges(tasks: List<TaskDraft>, segments: List<PlannedSegment>, existingIds: Set<String>, date: LocalDate, onSaved: () -> Unit) =
        mutate {
            assistantReceipt.value = null
            val result = operations?.confirmChanges(tasks, segments, existingIds, date)
                ?: planRepository.saveAssistantChanges(tasks, segments, existingIds, date)
            assistantReceipt.value = AssistantSaveReceipt(java.util.UUID.randomUUID().toString(), result)
            onSaved()
        }

    fun restore(planId: String) = mutate { planRepository.restore(planId) }
    fun clearCurrentPlan() = requestManualChange(ManualPlanChange.Clear)
    fun setSegmentLocked(segmentId: String, isLocked: Boolean) = mutate { planRepository.setSegmentLocked(segmentId, isLocked) }
    fun setSegmentReminder(segmentId: String, enabled: Boolean, leadMinutes: Int? = null) =
        mutate { planRepository.setSegmentReminder(segmentId, enabled, leadMinutes) }
    fun placeTask(taskId: String, date: LocalDate, startMinute: Int, endMinute: Int, trackId: String,
        delayFollowing: Boolean = false, reminderEnabled: Boolean = false, leadMinutes: Int? = null) =
        requestManualChange(ManualPlanChange.Place(taskId, date, startMinute, endMinute, trackId,
            delayFollowing, reminderEnabled, leadMinutes))

    /**
     * Agent 写入走这里：卡片回执不能替仓储说话。手动路径把失败留在计划页横幅里，助手那边却会把
     * 「已放入」念得跟真的一样——一次假成功比一次失败难查得多。共用同一把写锁与读取可用性判断，
     * 重叠确认仍然会拦，理由原样带回给模型。
     */
    suspend fun placeTaskForAgent(
        taskId: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int,
        trackId: String,
        reminderEnabled: Boolean = false,
        leadMinutes: Int? = null,
    ): String? = applyForAgent(ManualPlanChange.Place(taskId, date, startMinute, endMinute, trackId,
        reminderEnabled = reminderEnabled, leadMinutes = leadMinutes))

    /** 挪到别的日子也走同一条回执通路：日期为空就是原来的同天挪动。 */
    suspend fun movePlacementForAgent(
        segmentId: String,
        date: LocalDate?,
        startMinute: Int,
        endMinute: Int,
        trackId: String,
    ): String? = applyForAgent(ManualPlanChange.Move(segmentId, startMinute, endMinute, trackId, date = date))

    private suspend fun applyForAgent(change: ManualPlanChange): String? = mutex.withLock {
        if (readUnavailable()) {
            val reason = "计划还在重新读取，这次没有写入。"
            operation.value = reason to false
            return@withLock reason
        }
        operation.value = null to true
        val failure = try {
            planRepository.applyManualChange(change)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is IllegalArgumentException) error.message ?: "请检查这次安排后重试。"
            else "安排没有保存，原计划保持不变。"
        }
        operation.value = failure to false
        failure
    }
    fun movePlacement(segmentId: String, startMinute: Int, endMinute: Int, trackId: String, onSaved: () -> Unit = {}, delayFollowing: Boolean = false, date: LocalDate? = null) =
        requestManualChange(ManualPlanChange.Move(segmentId, startMinute, endMinute, trackId, delayFollowing, date), onSaved)
    fun removePlacement(segmentId: String) = requestManualChange(ManualPlanChange.Remove(segmentId))
    private fun requestManualChange(change: ManualPlanChange, onSaved: () -> Unit = {}) = mutate {
        require(pendingManualChange.value == null) { "请先确认或取消上次调整。" }
        try {
            planRepository.applyManualChange(change)
            onSaved()
        } catch (confirmation: PlanChangeConfirmationRequired) {
            manualChangeSaved = onSaved
            pendingManualChange.value = PendingManualPlanChange(change, confirmation.revision, confirmation.message.orEmpty())
        } catch (blocked: IllegalArgumentException) {
            // 保留失败意图，冲突弹窗才有 [搁置 / 排进下一个空闲] 可给。
            failedManualChange.value = change
            throw blocked
        }
    }
    fun cancelManualChange() { if (!operation.value.second) { pendingManualChange.value = null; manualChangeSaved = null; dismissError() } }
    fun confirmManualChange() {
        val pending = pendingManualChange.value ?: return
        if (operation.value.second) return
        mutate {
            require(pendingManualChange.value == pending) { "本次调整已取消。" }
            planRepository.applyManualChange(pending.change, pending.revision)
            pendingManualChange.value = null
            val saved = manualChangeSaved; manualChangeSaved = null; saved?.invoke()
        }
    }
    fun dismissError() { operation.value = null to operation.value.second }

    /** 搁置：任务保留为未排入，只清掉错误与失败意图。 */
    fun dismissFailedChange() {
        failedManualChange.value = null
        dismissError()
    }

    /** 排进下一个空闲：同一意图改为"插入并让后续条目后延"重试；仍排不下会带着原因回到冲突弹窗。 */
    fun retryFailedChangeIntoNextFree() {
        val change = failedManualChange.value ?: return
        failedManualChange.value = null
        dismissError()
        when (change) {
            is ManualPlanChange.Place -> placeTask(change.taskId, change.date, change.start, change.end, change.track, delayFollowing = true)
            is ManualPlanChange.Move -> movePlacement(change.segmentId, change.start, change.end, change.track, delayFollowing = true, date = change.date)
            ManualPlanChange.Clear, is ManualPlanChange.Remove -> {}
        }
    }

    /** 预览确认失败后的重试出口：同一次调整改为"让后续条目后延"再确认。 */
    fun confirmManualChangeWithDelay() {
        val pending = pendingManualChange.value ?: return
        if (operation.value.second) return
        val delayed = when (val change = pending.change) {
            is ManualPlanChange.Place -> change.copy(delayFollowing = true)
            is ManualPlanChange.Move -> change.copy(delayFollowing = true)
            else -> change
        }
        mutate {
            require(pendingManualChange.value == pending) { "本次调整已取消。" }
            planRepository.applyManualChange(delayed, pending.revision)
            pendingManualChange.value = null
            failedManualChange.value = null
            val saved = manualChangeSaved; manualChangeSaved = null; saved?.invoke()
        }
    }

    private fun readUnavailable() = uiState.value.readError != null || workspaceUiState.value.readError != null ||
            (uiState.value.hasLoaded && uiState.value.isLoading) ||
            (workspaceUiState.value.hasLoaded && workspaceUiState.value.isLoading) ||
            uiState.value.readAttempt != readRetries.value || workspaceUiState.value.readAttempt != readRetries.value

    private fun mutate(trackGeneration: Boolean = false, action: suspend () -> Unit) {
        if (trackGeneration && generationJob?.isActive == true) return
        if (readUnavailable()) {
            operation.value = "请先重新读取计划与助手草稿；本次未保存，已有内容仍保留。" to false
            return
        }
        val job = viewModelScope.launch {
            mutex.withLock {
                if (readUnavailable()) {
                    operation.value = "请先重新读取计划与助手草稿；本次未保存，已有内容仍保留。" to false
                    return@withLock
                }
                operation.value = null to true
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.e("PlanWrite", "mutate failed", error)
                    operation.value = (if (error is IllegalArgumentException) {
                        error.message ?: "请检查这次安排后重试。"
                    } else "操作未保存，请重试；原计划保持不变。") to true
                } finally {
                    operation.value = operation.value.first to false
                }
            }
        }
        if (trackGeneration) generationJob = job
    }

    class Factory(private val repository: PlanRepository, private val generator: PlanDraftGenerator,
        private val timeRepository: com.swan1127.repland.domain.ports.TimeRepository? = null,
        private val taskRepository: com.swan1127.repland.domain.ports.TaskRepository? = null,
        private val operations: PlanningOperationService? = null) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(PlanViewModel::class.java))
            return PlanViewModel(repository, generator, timeRepository, taskRepository, operations) as T
        }
    }
}
