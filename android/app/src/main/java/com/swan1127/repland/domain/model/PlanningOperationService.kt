package com.swan1127.repland.domain.model

import com.swan1127.repland.domain.planteam.ExecutionPulse
import com.swan1127.repland.domain.planteam.PlanRequestKind
import com.swan1127.repland.domain.planteam.PlanTeamContext
import com.swan1127.repland.domain.planteam.PlanTeamProfileFacts
import com.swan1127.repland.domain.planteam.PlanTeamResult
import com.swan1127.repland.domain.planteam.PlanningTeamCoordinator
import com.swan1127.repland.domain.planteam.runtime.TeamEvent
import com.swan1127.repland.domain.ports.AiSettingsRepository
import com.swan1127.repland.domain.ports.ExecutionSessionRepository
import com.swan1127.repland.domain.ports.PlanRepository
import com.swan1127.repland.domain.ports.ProfileEvidenceRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PlanningPreviewKind { FORMULATE, REPLAN_REMAINING, SORT_ONLY, ARRANGE_TODAY }
enum class TaskQueryScope { ALL_ACTIVE, TODAY, INBOX, OVERDUE }

data class PlanningTeamPreview(
    val events: List<TeamEvent> = emptyList(),
    val result: PlanTeamResult? = null,
    val draft: PlanDraft? = null,
    val isRunning: Boolean = false,
)

/** Finite business operations shared by page actions and assistant routing.
 * Query/explanation are read-only; preview only stores a draft; confirmation uses repository transactions.
 */
class PlanningOperationService(
    private val reads: PlanningReadService,
    private val plans: PlanRepository,
    private val generator: PlanDraftGenerator,
    private val sessions: ExecutionSessionRepository? = null,
    private val numericProfile: com.swan1127.repland.domain.ports.NumericProfileRepository? = null,
    private val planningTeam: PlanningTeamCoordinator = PlanningTeamCoordinator(),
    private val aiAdvisor: AiAdvisor = NoOpAiAdvisor,
    /** Optional consent gate; when null or not consented the team stays deterministic/offline. */
    private val aiSettings: AiSettingsRepository? = null,
    /**
     * Only user-edited conclusions are read as profile facts. Generated summaries describe the
     * log, not the person, and must not be dressed up as a preference.
     */
    private val profileEvidence: ProfileEvidenceRepository? = null,
    private val memoryReference: (LocalDate) -> MemoryReference? = { null },
    private val executionLogs: suspend (LocalDate) -> List<TaskExecutionLog>? = { null },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val planningDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val previewMutex = Mutex()
    private val mutableTeamPreview = MutableStateFlow(PlanningTeamPreview())
    val teamPreview: StateFlow<PlanningTeamPreview> = mutableTeamPreview.asStateFlow()
    private val generationEpoch = java.util.concurrent.atomic.AtomicLong()

    fun clearTeamMemory() {
        generationEpoch.incrementAndGet()
        planningTeam.memoryBank().clear()
        mutableTeamPreview.value = PlanningTeamPreview()
    }

    suspend fun query(scope: TaskQueryScope, date: LocalDate = LocalDate.now()): List<Task> {
        val source = reads.snapshot()
        val segments = source.current?.segments.orEmpty()
        val active = source.input.tasks.filter { it.status.isActive }
        val manualOrder = source.order.ifEmpty { source.current?.takeIf { it.hasManualTaskOrder }?.orderedTaskIds.orEmpty() }
        val order = LocalPriorityRanker.rank(active, source.input.categoryPreferences, LocalDate.now(), manualOrder).map { it.taskId }
        val byId = active.associateBy { it.id }
        return order.mapNotNull(byId::get).filter {
            when (scope) {
                TaskQueryScope.ALL_ACTIVE -> true
                TaskQueryScope.TODAY -> TaskPlanMembership.isToday(it, segments, date)
                TaskQueryScope.INBOX -> TaskPlanMembership.isInbox(it, segments, date)
                TaskQueryScope.OVERDUE -> it.dueDate?.isBefore(date) == true
            }
        }
    }

    suspend fun explainOrder(taskId: String): LocalPriorityAssessment {
        val source = reads.snapshot()
        val task = source.input.tasks.singleOrNull { it.id == taskId && it.status.isActive }
        requireNotNull(task) { "任务已结束或不存在，请刷新后重试。" }
        return LocalPriorityRanker.rank(listOf(task), source.input.categoryPreferences, LocalDate.now()).single()
    }

    suspend fun preview(kind: PlanningPreviewKind): PlanDraft = previewMutex.withLock {
        val epoch = generationEpoch.get()
        mutableTeamPreview.value = PlanningTeamPreview(isRunning = kind != PlanningPreviewKind.SORT_ONLY)
        try {
        val source = reads.snapshot()
        val previousDraft = plans.observeDraft().first()
        val sessionTask = sessions?.observeActive()?.first()?.taskId
        val now = LocalDateTime.now(clock)
        val memory = if (kind == PlanningPreviewKind.SORT_ONLY) null else memoryReference(now.toLocalDate())
        val input = source.input.copy(
            lockedSegments = source.current?.segments.orEmpty().filter { it.isLocked || it.taskId == sessionTask },
            manualTaskOrder = if (kind in setOf(PlanningPreviewKind.FORMULATE, PlanningPreviewKind.SORT_ONLY)) emptyList()
                else source.order.ifEmpty { source.current?.takeIf { it.hasManualTaskOrder }?.orderedTaskIds.orEmpty() },
        )
        var teamResult: PlanTeamResult? = null
        val result = withContext(planningDispatcher) {
            if (kind == PlanningPreviewKind.SORT_ONLY) generator.generateReplan(input, source.current)
            else {
                val taskIds = input.tasks.mapTo(HashSet(), Task::id)
                val logs = executionLogs(now.toLocalDate())?.filter { log ->
                    log.taskId in taskIds && Instant.ofEpochMilli(log.createdAtEpochMillis).atZone(clock.zone).toLocalDate() == now.toLocalDate()
                }
                require(generationEpoch.get() == epoch) { "规划记录已清除，本次生成已取消。" }
                // The team's AI tools stay offline unless the user both enabled AI and consented
                // to sending data; the runtime keys availability off NoOpAiAdvisor identity.
                val advisor = if (aiSettings != null) {
                    val prefs = aiSettings.observe().first()
                    if (prefs.isEnabled && prefs.hasExplicitConsent) aiAdvisor else NoOpAiAdvisor
                } else {
                    NoOpAiAdvisor
                }
                planningTeam.run(
                    PlanTeamContext(
                        requestKind = PlanRequestKind.FULL_REPLAN,
                        input = input,
                        current = source.current,
                        targetDate = now.toLocalDate(),
                        profileFacts = PlanTeamProfileFacts(isConfigured = false, preferMorningForDeepWork = false, memory = memory, notes = userEditedProfileNotes()),
                        now = now,
                        runningTaskId = sessionTask,
                        execution = logs?.let { ExecutionPulse.from(now.toLocalDate(), it) },
                        generator = generator,
                        todayOnly = kind == PlanningPreviewKind.ARRANGE_TODAY,
                    ),
                    advisor,
                ) { event -> if (generationEpoch.get() == epoch) mutableTeamPreview.update { it.copy(events = it.events + event) } }
                    .also { teamResult = it }.draft
            }
        }.copy(sourceRevision = source.revision, orderOnly = kind == PlanningPreviewKind.SORT_ONLY,
            memoryReferenceRevision = if (kind == PlanningPreviewKind.SORT_ONLY) null else memoryRevision(memory))
        var draft = if (result.orderOnly) result.copy(segments = emptyList(), unscheduledTasks = emptyList()) else result
        // Optional read-only advice. A failed/disabled profile cannot prevent basic local planning.
        if (!draft.orderOnly && numericProfile != null) {
            val profile = try { numericProfile.refresh() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
            if (profile?.enabled == true) {
                // One pass, carrying the task beside its estimate: naming the task from a second
                // scan of the list made this quadratic in the number of active tasks for no gain.
                val estimates = source.input.tasks.filter { it.status.isActive }.mapNotNull { task ->
                    val advice = try { numericProfile.durationAdvice(task.id) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
                    advice?.let { task to it }
                }.filter { it.second.profileVersion == profile.version }
                val round = profile.parameters.singleOrNull { it.id == "rounds" && it.availability == NumericAvailability.USABLE }
                val used = estimates.map { it.second.parameterId }.distinct() + listOfNotNull(round?.id)
                if (used.isNotEmpty()) draft = draft.copy(
                    numericProfileVersion = profile.version,
                    numericParameters = (draft.numericParameters + used).distinct(),
                    numericAdvice = draft.numericAdvice + estimates.map { (task, advice) ->
                        // Named, not identified: a task UUID is not information the user can act on.
                        "「${task.displayName}」：按你的历史用时，${advice.originalMinutes} 分钟更接近 ${advice.suggestedMinutes} 分钟。只是参考，这份安排仍用你确认的时长。"
                    } +
                        listOfNotNull(round?.let { "可选拆分参考：实际执行段中位 ${it.value} 分钟，${it.count} 个样本；不是偏好，不自动改变时段。" }))
            }
        }
        require(reads.snapshot().revision == source.revision) { "生成期间任务、时间或偏好已变化，请重新预览。" }
        require(kind == PlanningPreviewKind.SORT_ONLY || memoryReference(LocalDate.now(clock)) == memory) { "记忆记录或记忆参考设置刚变化，请重新预览；原草案和计划保留。" }
        require(plans.observeDraft().first() == previousDraft) { "生成期间草案已被修改，请先查看当前预览；本次未覆盖。" }
        require(generationEpoch.get() == epoch) { "规划记录已清除，本次生成已取消。" }
        plans.saveGeneratedDraftIfCurrent(previousDraft, draft)
        mutableTeamPreview.update { it.copy(result = teamResult?.copy(draft = draft), draft = draft, isRunning = false) }
        draft
        } finally {
            if (generationEpoch.get() != epoch) {
                planningTeam.memoryBank().clear()
                mutableTeamPreview.value = PlanningTeamPreview()
            } else mutableTeamPreview.update { it.copy(isRunning = false) }
        }
    }

    suspend fun confirm(draft: PlanDraft) {
        // No fallback empty context at the write boundary. Repository transactions
        // still revalidate revisions and hard constraints immediately before commit.
        reads.snapshot()
        require(draft.sourceRevision != null) { "这份旧草案缺少来源版本，请重新生成预览；原草案和计划仍保留。" }
        draft.memoryReferenceRevision?.let { revision ->
            require(memoryRevision(memoryReference(LocalDate.now(clock))) == revision) { "记忆记录或记忆参考设置刚变化，请重新预览；当前计划保留。" }
        }
        plans.confirmDraftIfCurrent(draft)
    }
    suspend fun confirmChanges(tasks: List<TaskDraft>, segments: List<PlannedSegment>, existingIds: Set<String>, date: LocalDate): AssistantSaveResult =
        run {
            reads.snapshot()
            plans.saveAssistantChanges(tasks, segments, existingIds, date)
        }

    private fun memoryRevision(reference: MemoryReference?): String = java.security.MessageDigest
        .getInstance("SHA-256").digest((reference?.toString() ?: "reference-disabled").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * These are descriptions the user typed about themselves, so the team may see them. Generated
     * summaries describe the log, not the person, and presenting them as preferences would make a
     * derived statistic look like something the user said.
     */
    private suspend fun userEditedProfileNotes(): List<String> {
        val repository = profileEvidence ?: return emptyList()
        val evidence = try {
            repository.observe().first()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        return evidence.asSequence()
            .filter { it.isUserEdited && it.conclusion.isNotBlank() }
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(3)
            .map { "你编辑过的画像：${it.conclusion.trim().take(120)}" }
            .toList()
    }
}
