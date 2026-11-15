package com.swan1127.repland.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.domain.ports.AutonomyRepository
import com.swan1127.repland.domain.ports.AutonomyState
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutonomyUiState(
    val ready: Boolean = false,
    val delegation: AutonomyState = AutonomyState(),
    val busy: Boolean = false,
    val error: String? = null,
    val response: String? = null,
    val queryResults: List<Task>? = null,
    val submission: Long = 0,
    val completedAttempts: Long = 0,
    /** 每次发布回执都自增：同文案的连续回执也要各自弹一次提示条。 */
    val responseSerial: Long = 0,
)

/** Remote understanding is optional; local execution and its guards always remain available. */
@OptIn(FlowPreview::class)
class AutonomyViewModel(
    private val repository: AutonomyRepository,
    private val reads: PlanningReadService,
    private val operations: PlanningOperationService,
) : ViewModel() {
    private val mutable = MutableStateFlow(AutonomyUiState())
    val state: StateFlow<AutonomyUiState> = mutable.asStateFlow()
    private val work = Mutex()

    init {
        viewModelScope.launch {
            repository.observe().catch { fail("无法读取自动安排设置，请重新打开 App。") }
                .collect { setting -> mutable.update { it.copy(ready = true, delegation = setting) } }
        }
        viewModelScope.launch {
            repository.observeInputRevision().distinctUntilChanged().debounce(650)
                .catch { fail("暂时无法读取计划变化，现有数据保留。") }
                .collect { reconcile() }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setEnabled(enabled)
                mutable.update { it.copy(error = null) }
                if (enabled) reconcile()
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { fail("自动安排设置未保存，请重试。") }
        }
    }

    fun onForeground() { viewModelScope.launch { reconcile() } }

    private suspend fun reconcile() = work.withLock {
        try {
            val result = repository.reconcile()
            // Publish the receipt at the same serialized boundary as explicit commands.
            // The repository observer may arrive later, so it only refreshes delegation;
            // it must not overwrite a newer query or the capture's understanding prefix.
            if (result != null) mutable.update { it.copy(response = result,
                responseSerial = it.responseSerial + 1,
                delegation = repository.observe().first(),
                queryResults = null, error = null) }
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { fail(e.message ?: "本次自动调整未完成，原数据保留。") }
    }

    fun submit(text: String, canUseRemote: Boolean,
               refine: suspend (ArrangementAssistantAdviceRequest) -> ArrangementAssistantAdviceResult) {
        if (text.isBlank() || mutable.value.busy || !mutable.value.ready) return
        mutable.update { it.copy(busy = true, error = null, queryResults = null) }
        viewModelScope.launch {
            work.withLock {
                try {
                    val today = LocalDate.now()
                    val operationId = UUID.randomUUID().toString()
                    val source = reads.snapshot()
                    val legacy = AutonomousTaskIntentParser.parse(text)
                    val ruleTasks = source.input.tasks.filter { it.status.isActive }.map { task ->
                        RuleTask(task.id, task.displayName, task.totalDurationMinutes,
                            source.current?.segments.orEmpty().firstOrNull { it.taskId == task.id }?.date
                                ?: task.scheduledForDate)
                    }
                    val rule = RuleIntentParser.parse(text, today, ruleTasks)
                    // English-only wording stops at the AI boundary; every other hard trigger
                    // is a question the model could not answer more safely than the user.
                    val askNow = rule.mustAsk && !(canUseRemote && rule.reasons.contains("english-intent"))
                    val response = when {
                        askNow && legacy !is AutonomousTaskIntent.Arrange && legacy !is AutonomousTaskIntent.Query ->
                            throw IllegalArgumentException(RuleIntentParser.clarifyReply(rule.reasons))

                        rule.review -> {
                            val first = rule.draftItems.first()
                            val extras = rule.draftItems.drop(1).filter { it.action == RuleAction.CREATE }
                            val extraDate = extras.firstOrNull()?.date
                            repository.review(
                                name = rule.contextTaskName ?: first.name,
                                taskId = rule.contextTaskId,
                                progressPercent = first.progress,
                                extra = extras.map(::candidate),
                                date = extraDate ?: today,
                                dateExplicit = extraDate != null,
                                requestId = operationId,
                                expectedRevision = source.revision,
                                originalText = text,
                            )
                        }

                        rule.action == RuleAction.POSTPONE || rule.action == RuleAction.SCHEDULE -> {
                            val item = rule.draftItems.first()
                            // 「改到下午三点」 names only a clock; that keeps the day it was said on.
                            val targetDate = item.date ?: item.startMinute?.let { today }
                                ?: throw IllegalArgumentException("要改到哪一天？告诉我日期后我再改。")
                            val task = rule.contextTaskId?.let { id -> ruleTasks.firstOrNull { it.id == id } }
                                ?: ruleTasks.singleOrNull { it.name == item.name }
                                ?: throw IllegalArgumentException("没有找到「${item.name}」，请使用任务列表中的完整名称。")
                            repository.reschedule(task.id, targetDate, item.startMinute,
                                item.durationMinutes, operationId, source.revision)
                        }

                        legacy is AutonomousTaskIntent.StatusUpdates -> repository.updateStatus(legacy.commands, operationId)
                        legacy is AutonomousTaskIntent.Arrange -> repository.arrange(day(legacy.day, today), operationId,
                            if (legacy.day == AutonomousDay.TONIGHT) 18 * 60 else 0)
                        legacy is AutonomousTaskIntent.Query -> {
                            val scope = when (legacy.scope) {
                                AutonomousQueryScope.ACTIVE -> TaskQueryScope.ALL_ACTIVE
                                AutonomousQueryScope.SCHEDULED -> TaskQueryScope.TODAY
                                AutonomousQueryScope.INBOX -> TaskQueryScope.INBOX
                                AutonomousQueryScope.OVERDUE -> TaskQueryScope.OVERDUE
                            }
                            var tasks = operations.query(scope, day(legacy.day ?: AutonomousDay.TODAY, today))
                            if (legacy.day == AutonomousDay.TONIGHT) {
                                val eveningIds = source.current?.segments.orEmpty()
                                    .filter { it.date == today && it.endMinute > 18 * 60 }.map { it.taskId }.toSet()
                                tasks = tasks.filter { it.id in eveningIds }
                            }
                            mutable.update { it.copy(queryResults = tasks) }
                            if (tasks.isEmpty()) "没有符合条件的任务。" else "找到 ${tasks.size} 件任务，点开可查看详情。"
                        }

                        rule.action == RuleAction.ROUTINE -> {
                            val item = rule.draftItems.first()
                            val weekdays = item.weekdays.orEmpty()
                            val start = item.startMinute
                            val end = item.endMinute ?: start?.plus(item.durationMinutes ?: 0)
                            require(weekdays.isNotEmpty() && start != null && end != null && end > start) {
                                "我需要知道每周哪几天、几点到几点；例如「每周一三五晚上八点练琴一小时」。"
                            }
                            repository.saveRoutine(item.name, weekdays, start, end, operationId)
                        }

                        rule.action == RuleAction.BUSY -> {
                            val item = rule.draftItems.first()
                            val start = item.startMinute
                            val end = item.endMinute
                            require(start != null && end != null && end > start) { "要留出哪个时段？例如「明天下午三点到五点别排事」。" }
                            if (!item.weekdays.isNullOrEmpty()) {
                                repository.saveRoutine(item.name.ifBlank { "别排事" }, item.weekdays, start, end, operationId)
                            } else {
                                repository.saveBusy(item.name, item.date ?: today, start, end, operationId)
                            }
                        }

                        rule.intent == RuleIntentKind.QUERY -> {
                            val tasks = operations.query(TaskQueryScope.ALL_ACTIVE, today)
                            mutable.update { it.copy(queryResults = tasks) }
                            when (rule.queryKind) {
                                RuleQueryKind.SPREAD ->
                                    "想排松一点，可以在时间设置里多留空档；要按现有空档重排，点「重新安排」就行。"
                                RuleQueryKind.ADVICE -> if (tasks.isEmpty()) "现在没有待办事项。"
                                    else "还有 ${tasks.size} 件待办，点开可以看先后和时长；需要我按空档重排就说「重新安排今天」。"
                                RuleQueryKind.TASKS -> if (tasks.isEmpty()) "没有符合条件的任务。"
                                    else "找到 ${tasks.size} 件任务，点开可查看详情。"
                            }
                        }

                        rule.action == RuleAction.PROGRESS || rule.action == RuleAction.COMPLETE ||
                            rule.action == RuleAction.CANCEL -> {
                            val item = rule.draftItems.first()
                            val targetName = rule.contextTaskId
                                ?.let { id -> source.input.tasks.firstOrNull { it.id == id }?.displayName }
                                ?: item.name
                            val change = when (rule.action) {
                                RuleAction.PROGRESS -> AutonomousTaskChange.Progress((item.progress ?: 0).coerceIn(0, 100))
                                RuleAction.COMPLETE -> AutonomousTaskChange.Complete
                                else -> AutonomousTaskChange.Cancel
                            }
                            repository.updateStatus(listOf(AutonomousStatusCommand(
                                AutonomousTaskTarget.Named(targetName), change, text)), operationId)
                        }

                        else -> {
                            val item = rule.draftItems.firstOrNull()
                                ?: throw IllegalArgumentException(RuleIntentParser.clarifyReply(rule.reasons))
                            val date = item.date ?: item.dueDate ?: rule.preferredDate
                                ?: AutonomousCaptureDate.resolve(text, today).date
                            val dateExplicit = item.date != null || item.dueDate != null || rule.dateExplicit
                            val local = listOf(candidate(item))
                            var candidates = local
                            var usedRemote = false
                            if (canUseRemote) {
                                val request = ArrangementAssistantAdviceRequest(text, date, emptyList(),
                                    existingTasks = source.input.tasks.filter { it.status.isActive }.take(50).map {
                                        ArrangementExistingTask(it.id, it.displayName, it.category, it.totalDurationMinutes)
                                    }, sourceRevision = source.revision)
                                val result = refine(request)
                                if (result is ArrangementAssistantAdviceResult.Advice &&
                                    result.advice.candidates.isNotEmpty() && result.advice.candidates.none { it.existingTaskId != null }) {
                                    candidates = AutonomousCaptureFacts.reconcile(local, result.advice.candidates)
                                    usedRemote = true
                                } else if (result is ArrangementAssistantAdviceResult.Failed &&
                                    result.reason == AiAdvisorFailureReason.SOURCE_CHANGED) {
                                    throw IllegalStateException("理解期间任务或时间发生变化，请重新发送。")
                                }
                            }
                            val receipt = repository.capture(candidates, date, dateExplicit,
                                operationId, source.revision, text, trustProvided = true)
                            (if (usedRemote) "AI 已理解。" else "已用本地规则处理。") + receipt
                        }
                    }
                    mutable.update { it.copy(response = response, responseSerial = it.responseSerial + 1,
                        submission = it.submission + 1, delegation = repository.observe().first(), error = null) }
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { fail(e.message ?: "本次未完成，请重试。") }
                finally { mutable.update { it.copy(busy = false, completedAttempts = it.completedAttempts + 1) } }
            }
        }
    }

    fun undo() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            work.withLock {
                try {
                    val result = repository.undo()
                    mutable.update { it.copy(response = result, responseSerial = it.responseSerial + 1,
                        delegation = repository.observe().first(), queryResults = null) }
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) { fail(e.message ?: "撤销未完成，当前数据保留。") }
                finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }

    private fun candidate(item: RuleItem) = ArrangementCandidate(
        title = item.name,
        category = RuleIntentParser.categoryFor(item.name),
        durationMinutes = item.durationMinutes,
        timeHint = ArrangementTimeHint(item.startMinute, item.period, item.endMinute),
        needsClarification = emptySet(),
        placementSource = if (item.startMinute != null) ArrangementPlacementSource.USER_EXPLICIT
            else ArrangementPlacementSource.UNSCHEDULED,
        durationEstimated = !item.durationExplicit,
        dueDate = item.dueDate,
        dueMinute = item.dueMinute,
    )

    private fun fail(message: String) { mutable.update { it.copy(error = message) } }
    private fun day(day: AutonomousDay, today: LocalDate): LocalDate =
        if (day == AutonomousDay.TOMORROW) today.plusDays(1) else today

    class Factory(private val repository: AutonomyRepository, private val reads: PlanningReadService,
                  private val operations: PlanningOperationService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AutonomyViewModel::class.java))
            return AutonomyViewModel(repository, reads, operations) as T
        }
    }
}
