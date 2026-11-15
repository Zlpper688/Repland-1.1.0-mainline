package com.swan1127.repland.data.room

import androidx.room.withTransaction
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.domain.ports.AutonomyRepository
import com.swan1127.repland.domain.ports.AutonomyState
import com.swan1127.repland.domain.team.TeamPersonalBridge
import com.swan1127.repland.domain.team.reportMirrorCompletion
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Local commands and their scheduling consequences commit together with a persistent undo journal. */
class RoomAutonomyRepository(
    private val database: ReplandDatabase,
    private val writeGate: PlanWriteGate = PlanWriteGate(),
    private val teamBridge: TeamPersonalBridge? = null,
) : AutonomyRepository {
    private val workspace = database.planningWorkspaceDao()
    private val tasks = RoomTaskRepository(database)
    private val plans = RoomPlanRepository(database, writeGate)

    override fun observe(): Flow<AutonomyState> = combine(
        workspace.observe(SETTINGS), workspace.observe(RECEIPT), workspace.observe(UNDO),
    ) { settings, receipt, undo ->
        AutonomyState(settings?.payload != "false", receipt?.payload, undo != null)
    }.distinctUntilChanged()

    override fun observeInputRevision(): Flow<String> {
        val taskAndTime = combine(database.taskDao().observeAll(), database.timeDao().observeWeeklyBlocks(),
            database.timeDao().observeDateOverrides(), database.timeDao().observeSemesterSettings(),
            database.categoryPreferenceDao().observeAll()) { taskRows, weekly, overrides, semester, preferences ->
            listOf(taskRows.sortedBy { it.id }, weekly, overrides, semester, preferences).toString()
        }
        // 只观察外部输入。计划本身和执行日志是写入的「结果」，放进来会让引擎被自己的
        // 写入和每一次手动改时间重新勾起——这正是手动时间被悄悄改回的触发链。
        val orderSessionAndWindows = combine(workspace.observe("order"),
            workspace.observe(RoomExecutionSessionRepository.KEY), workspace.observe(WINDOWS)) { order, session, windows ->
            listOf(order?.payload, session?.payload, windows?.payload).toString()
        }
        return combine(taskAndTime, orderSessionAndWindows, workspace.observe(HELD)) { first, second, held ->
            digest("${LocalDate.now()}\n$first\n$second\n${held?.payload}")
        }
            .distinctUntilChanged()
    }

    override suspend fun setEnabled(enabled: Boolean) = database.withTransaction {
        workspace.put(PlanningWorkspaceEntity(SETTINGS, enabled.toString()))
    }

    override suspend fun capture(candidates: List<ArrangementCandidate>, date: LocalDate, dateExplicit: Boolean,
        requestId: String, expectedRevision: String?, originalText: String?, trustProvided: Boolean): String = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(candidates.isNotEmpty() && candidates.size <= 8) { "一次请输入 1–8 件事项。" }
        require(candidates.all { it.existingTaskId == null && it.title.isNotBlank() &&
            (it.durationMinutes == null || it.durationMinutes in 1..720) }) { "新建事项内容无效，请重新输入。" }
        require(candidates.all { it.dueMinute == null || it.dueMinute in 0 until 1440 }) { "截止时间无效。" }
        val input = input()
        val current = current()
        require(expectedRevision == null || expectedRevision == PlanningRevision.of(input, current, order())) {
            "任务或时间设置已变化，请重新发送；本次没有写入。"
        }
        val before = snapshot()
        val captureOnly = AutonomousCaptureFacts.recordOnly(originalText)
        val newWindows = windows().toMutableList()
        val created = createItems(candidates, date, dateExplicit, captureOnly, originalText, trustProvided, newWindows)
        putWindows(newWindows)
        if (captureOnly) workspace.put(PlanningWorkspaceEntity(HELD, JSONArray((heldTasks() + created.ids).sorted()).toString()))
        val repair = if (enabled()) repair() else RepairResult(false, "自动安排已暂停，事项已保存到任务库。")
        val placedIds = current()?.segments.orEmpty().filter { it.taskId in created.ids }.map { it.taskId }.distinct()
        val dayItems = created.ids.filterNot { it in created.dueIds }
        val specifiedDayPending = if (dateExplicit && !captureOnly) dayItems.count { id ->
            current()?.segments.orEmpty().none { it.taskId == id && it.date == date }
        } else 0
        val missedWindows = newWindows.filter { it.taskId in created.ids && !captureOnly }.count { window ->
            val segments = current()?.segments.orEmpty().filter { it.taskId == window.taskId }.sortedBy { it.startMinute }
            segments.isEmpty() || segments.any { it.date != window.date || it.startMinute < window.startMinute || it.endMinute > window.endMinute } ||
                (window.exactStart && segments.first().startMinute != window.startMinute)
        }
        val summary = buildString {
            append("已保存 ${created.ids.size} 件事项")
            if (placedIds.isNotEmpty()) append("，已安排 ${placedIds.size} 件")
            if (created.estimates > 0) append("；${created.estimates} 件时长为规划估算，可随时修改")
            if (created.dueIds.isNotEmpty()) append("；${created.dueIds.size} 件按截止时间保留，不占用时段")
            if (specifiedDayPending > 0) append("；${specifiedDayPending} 件未能排入指定日期")
            if (missedWindows > 0) append("；${missedWindows} 件未能按指定时刻或时段安排，已保留待安排")
            append("。")
            if (captureOnly) append("已按要求暂不安排，可在需要时直接让我安排。")
            if (repair.note != null) append(repair.note)
        }
        finish(requestId, before, summary)
    }
    }

    override suspend fun review(name: String, taskId: String?, progressPercent: Int?, extra: List<ArrangementCandidate>,
        date: LocalDate, dateExplicit: Boolean, requestId: String, expectedRevision: String?, originalText: String?): String =
        writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(name.isNotBlank()) { "没有找到对应任务，请使用任务列表中的完整名称。" }
        val input = input()
        val current = current()
        require(expectedRevision == null || expectedRevision == PlanningRevision.of(input, current, order())) {
            "任务或时间设置已变化，请重新发送；本次没有写入。"
        }
        val task = taskId?.let { id -> input.tasks.singleOrNull { it.id == id } }
            ?: input.tasks.singleOrNull { normalized(it.displayName) == normalized(name) }
            ?: error("没有找到「$name」，请使用任务列表中的完整名称。")
        require(task.status.isActive) { "「${task.displayName}」已经结束，本次没有更新。" }
        val before = snapshot()
        val percent = progressPercent ?: 0
        when (percent) {
            100 -> tasks.confirmStatus(task.id, TaskStatus.COMPLETED,
                TaskFeedback(progressPercent = 100, completedContent = originalText))
            0 -> tasks.recordFeedback(task.id, TaskFeedback(progressPercent = 0, completedContent = originalText))
            else -> tasks.recordPartialCompletion(task.id,
                TaskFeedback(progressPercent = percent, completedContent = originalText))
        }
        val newWindows = windows().toMutableList()
        val created = createItems(extra, date, dateExplicit, recordOnly = false, originalText = originalText,
            trustProvided = true, newWindows = newWindows, pinDate = dateExplicit)
        if (created.ids.isNotEmpty()) putWindows(newWindows)
        val repair = if (enabled()) repair() else RepairResult(false, "自动安排已暂停，补做事项已保存到任务库。")
        val progressText = if (percent == 0) "记为未完成" else "进度更新为 $percent%"
        val summary = buildString {
            append("已保留「${task.displayName}」原来的安排，并$progressText")
            if (created.ids.isNotEmpty()) append("；额外补做的 ${created.ids.size} 件已记下")
            if (created.dueIds.isNotEmpty()) append("；${created.dueIds.size} 件按截止时间保留，不占用时段")
            append("。")
            if (repair.note != null) append(repair.note)
        }
        finish(requestId, before, summary)
    }
    }

    override suspend fun reschedule(taskId: String, targetDate: LocalDate, startMinute: Int?, durationMinutes: Int?,
        requestId: String, expectedRevision: String?): String = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(targetDate >= LocalDate.now()) { "不能把任务移到过去的日期。" }
        val input = input()
        val current = current()
        require(expectedRevision == null || expectedRevision == PlanningRevision.of(input, current, order())) {
            "任务或时间设置已变化，请重新发送；本次没有写入。"
        }
        val task = input.tasks.singleOrNull { it.id == taskId } ?: error("没有找到对应任务，请重新发送。")
        require(task.status.isActive) { "「${task.displayName}」已经结束，本次没有更新。" }
        require(startMinute == null || startMinute in 0 until 1440) { "改到的时间无效。" }
        val before = snapshot()
        val entity = requireNotNull(database.taskDao().getById(task.id)) { "没有找到对应任务，请重新发送。" }
        val sources = TaskInputSources.decode(entity.inputSources).copy(scheduledDate = TaskInputSource.USER_INPUT)
        database.taskDao().update(entity.copy(
            scheduledForEpochDay = targetDate.toEpochDay(),
            inputSources = sources.encode(),
            updatedAtEpochMillis = maxOf(System.currentTimeMillis(), entity.updatedAtEpochMillis + 1),
        ))
        val newWindows = windows().filterNot { it.taskId == task.id }.toMutableList()
        if (startMinute != null) {
            val end = minOf(24 * 60, startMinute + (durationMinutes?.takeIf { it in 1..1440 } ?: (24 * 60 - startMinute)))
            require(end > startMinute) { "改到的时间无效。" }
            newWindows += AutonomousTimeWindow(task.id, targetDate, startMinute, end, exactStart = true)
        }
        putWindows(newWindows)
        val held = heldTasks()
        if (task.id in held) {
            val remaining = held - task.id
            if (remaining.isEmpty()) workspace.remove(HELD)
            else workspace.put(PlanningWorkspaceEntity(HELD, JSONArray(remaining.sorted()).toString()))
        }
        val repair = if (enabled()) repair() else RepairResult(false, "自动安排已暂停，日期已改。")
        val clock = startMinute?.let { " ${"%02d:%02d".format(Locale.ROOT, it / 60, it % 60)}" }.orEmpty()
        val summary = "已把「${task.displayName}」改到 ${targetDate.monthValue}月${targetDate.dayOfMonth}日$clock。" +
            repair.note.orEmpty()
        finish(requestId, before, summary)
    }
    }

    override suspend fun saveRoutine(name: String, weekdays: List<Int>, startMinute: Int, endMinute: Int,
        requestId: String): String = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        val days = weekdays.distinct().sorted()
        require(name.isNotBlank()) { "固定安排需要名称。" }
        require(days.isNotEmpty() && days.all { it in 1..7 }) { "请说明每周哪几天。" }
        require(startMinute in 0 until 24 * 60 && endMinute in 1..24 * 60 && startMinute < endMinute) {
            "固定安排的时间无效。"
        }
        val before = snapshot()
        RoomTimeRepository(database.timeDao()).saveWeeklyBlocks(days.map { day ->
            WeeklyTimeBlockDraft(title = name.trim().take(40), kind = TimeBlockKind.OTHER,
                dayOfWeek = java.time.DayOfWeek.of(day), startMinute = startMinute, endMinute = endMinute,
                trackId = "fixed")
        })
        val repair = if (enabled()) repair() else RepairResult(false, null)
        val dayText = "每周" + days.joinToString("、") { "周" + "一二三四五六日"[it - 1] }
        val summary = "已把「${name.trim()}」设为$dayText ${clockText(startMinute)}–${clockText(endMinute)} 的固定安排。" +
            repair.note.orEmpty()
        finish(requestId, before, summary)
    }
    }

    override suspend fun saveBusy(name: String?, date: LocalDate, startMinute: Int, endMinute: Int,
        requestId: String): String = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(startMinute in 0 until 24 * 60 && endMinute in 1..24 * 60 && startMinute < endMinute) {
            "留空的时间无效。"
        }
        val before = snapshot()
        RoomTimeRepository(database.timeDao()).saveDateOverride(DateOverrideDraft(
            title = name?.trim()?.takeIf { it.isNotBlank() } ?: "别排事",
            type = DateOverrideType.BLOCKED, date = date, startMinute = startMinute, endMinute = endMinute,
        ))
        val repair = if (enabled()) repair() else RepairResult(false, null)
        val summary = "已在 ${date.monthValue}月${date.dayOfMonth}日 ${clockText(startMinute)}–${clockText(endMinute)} 留出不排事的时间。" +
            repair.note.orEmpty()
        finish(requestId, before, summary)
    }
    }

    override suspend fun updateStatus(commands: List<AutonomousStatusCommand>, requestId: String,
        contextTaskId: String?): String {
        val completedMirrors = mutableListOf<Pair<String, String?>>()
        val summary = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(commands.isNotEmpty() && commands.size <= 8) { "一次请输入 1–8 项状态更新。" }
        val allTasks = database.taskDao().getAll().map { it.toDomain() }
        val resolved = commands.map { command ->
            val resolution = AutonomousTaskIntentParser.resolveTarget(command.target, allTasks, contextTaskId)
            val found = when (resolution) {
                is AutonomousTargetResolution.Found -> resolution.task
                is AutonomousTargetResolution.Ambiguous -> error("存在多个同名任务，请在任务详情中明确选择后更新。")
                is AutonomousTargetResolution.Missing -> error("没有找到对应任务，请使用任务列表中的完整名称。")
                AutonomousTargetResolution.ContextRequired -> error("还不能确定“这个任务”是哪一项，请说出完整任务名称。")
            }
            require(found.status.isActive) { "“${found.displayName}”已经结束，本次没有更新。" }
            val progressOnly = command.change is AutonomousTaskChange.Progress && command.change.percent < 100
            require(found.id != runningTaskId() || progressOnly) { "“${found.displayName}”正在专注中，请先结束本轮专注，再完成或取消任务，以保存本轮计时。" }
            command to found
        }
        require(resolved.map { it.second.id }.distinct().size == resolved.size) { "同一任务出现了多次更新，请合并成一个明确指令。" }
        val before = snapshot()
        resolved.forEach { (command, task) ->
            when (val change = command.change) {
                AutonomousTaskChange.Complete -> {
                    tasks.confirmStatus(task.id, TaskStatus.COMPLETED,
                        TaskFeedback(completedContent = command.sourceText))
                    if (TeamTaskMirror.isMirror(task.id)) completedMirrors += task.id to command.sourceText
                }
                AutonomousTaskChange.Cancel -> tasks.confirmStatus(task.id, TaskStatus.CANCELLED,
                    TaskFeedback(completedContent = command.sourceText))
                is AutonomousTaskChange.Progress -> when (change.percent) {
                    100 -> {
                        tasks.confirmStatus(task.id, TaskStatus.COMPLETED,
                            TaskFeedback(progressPercent = 100, completedContent = command.sourceText))
                        if (TeamTaskMirror.isMirror(task.id)) completedMirrors += task.id to command.sourceText
                    }
                    0 -> tasks.recordFeedback(task.id, TaskFeedback(progressPercent = 0, completedContent = command.sourceText))
                    else -> tasks.recordPartialCompletion(task.id,
                        TaskFeedback(progressPercent = change.percent, completedContent = command.sourceText))
                }
            }
        }
        val repair = repair(scheduleNew = enabled())
        val summary = "已更新 ${resolved.size} 件任务，保留原有执行记录。" +
            if (repair.note != null) repair.note else if (repair.changed) "后续安排已同步调整。" else ""
        finish(requestId, before, summary)
    }
    }
        // 团队镜像的完成由团队域记账；回传在写闸门外进行，避免重入同一把锁。
        completedMirrors.forEach { (taskId, text) -> teamBridge.reportMirrorCompletion(taskId, text) }
        return summary
    }

    override suspend fun arrange(date: LocalDate, requestId: String, startMinute: Int, endMinute: Int): String = writeGate.serialize {
        database.withTransaction {
        duplicate(requestId)?.let { return@withTransaction it }
        require(date >= LocalDate.now()) { "不能自动重排过去的日期。" }
        val before = snapshot()
        require(startMinute in 0 until 1440 && endMinute in 1..1440 && startMinute < endMinute) { "安排时段无效。" }
        val requestedWindows = windows().groupBy { it.taskId }
        val eligibleIds = database.taskDao().getAll().map { it.toDomain() }.filter { task ->
            task.status.isActive && task.scheduledForDate?.isAfter(date) != true &&
                task.dueDate?.isBefore(date) != true &&
                requestedWindows[task.id].orEmpty().let { taskWindows ->
                    taskWindows.isEmpty() || taskWindows.any { window ->
                        window.date == date && window.startMinute < endMinute && window.endMinute > startMinute
                    }
                }
        }.mapTo(mutableSetOf()) { it.id }
        val remainingHeld = heldTasks() - eligibleIds
        if (remainingHeld.isEmpty()) workspace.remove(HELD)
        else workspace.put(PlanningWorkspaceEntity(HELD, JSONArray(remainingHeld.sorted()).toString()))
        val repair = repair(targetDate = date, targetStartMinute = startMinute, targetEndMinute = endMinute)
        val summary = if (repair.changed) "已在现有时间约束内调整后续安排。" + repair.note.orEmpty()
            else repair.note ?: "当前安排已经合适，没有需要移动的时段。"
        finish(requestId, before, summary)
    }
    }

    override suspend fun reconcile(): String? = writeGate.serialize {
        database.withTransaction {
        if (!enabled()) return@withTransaction null
        val revision = repairRevision()
        if (workspace.get(LAST_REVISION)?.payload == revision) return@withTransaction null
        val before = snapshot()
        val repair = repair()
        // 自动调整不再只是一句套话：说清楚动了什么，用户才能知道发生了什么、能不能撤销。
        val diff = listOfNotNull(
            if (repair.moved > 0) "移动 ${repair.moved} 个时段" else null,
            if (repair.added > 0) "新增 ${repair.added} 个" else null,
            if (repair.removed > 0) "撤下 ${repair.removed} 个" else null,
        ).joinToString("，")
        if (diff.isBlank()) {
            // 一个时段都没挪（只是任务顺序或计划行重写）时，「已调整后续安排」是在谎报工作量，
            // 也不该为没有动作的改动留一笔撤销账。这种轮次唯一能说的是那句常驻处境：它要是
            // 上一轮已经说过，就不要再弹一次——顶掉的正是用户刚按下的那个按钮的回执，「撤销」
            // 按下去先看见工具的提醒，自己的确认反而看不见。常驻状态该留在待安排列表里说。
            val news = repair.note?.takeIf { it != workspace.get(RECEIPT)?.payload }
            news?.let { workspace.put(PlanningWorkspaceEntity(RECEIPT, it)) }
            // 被固定冲突挡下的一轮什么都没改成，也就没把这次输入处理完。把印章盖上等于把用户
            // 刚做的手势（例如把任务改到某一天）永久放弃——引擎再也不会回头补排。留着印章不写，
            // 下一次输入变化、切前台或重开自动安排都会重试；文案已经说过就不再重复弹。
            if (!repair.blocked) workspace.put(PlanningWorkspaceEntity(LAST_REVISION, repairRevision()))
            return@withTransaction news
        }
        val summary = "自动调整：$diff。" + repair.note.orEmpty()
        finish("automatic:${UUID.randomUUID()}", before, summary)
    }
    }

    override suspend fun undo(): String = writeGate.serialize {
        database.withTransaction {
        val journal = workspace.get(UNDO)?.let { JSONObject(it.payload) }
            ?: error("没有可撤销的自动操作。")
        require(journal.getString("afterRevision") == guardRevision()) {
            "这次操作之后已有新的修改，整次撤销已停止，以保留你的后续修改。"
        }
        val beforeTasks = journal.getJSONArray("tasks").objects().map(::decodeTask)
        val beforeById = beforeTasks.associateBy { it.id }
        val afterIds = journal.getJSONArray("afterTaskIds").strings().toSet()
        val createdIds = afterIds - beforeById.keys
        createdIds.forEach { id ->
            require(database.executionLogDao().getForTask(id).isEmpty()) { "新任务已有执行记录，本次没有撤销。" }
        }
        val logIds = journal.getJSONArray("newLogIds").strings()
        createdIds.forEach { database.taskDao().deleteById(it) }
        beforeTasks.forEach { original ->
            if (database.taskDao().getById(original.id) != original) database.taskDao().insert(original)
        }
        // Corrections append to evidence; the original status/progress reports remain immutable.
        logIds.forEach { id ->
            val log = requireNotNull(database.executionLogDao().getById(id))
            val original = requireNotNull(beforeById[log.taskId])
            val latest = database.executionLogDao().latestCreatedAtForTask(log.taskId) ?: 0L
            database.executionLogDao().insert(ExecutionLogEntity(
                UUID.randomUUID().toString(), log.taskId, ExecutionLogEventType.CORRECTION.name, original.status,
                null, original.progressPercent, null, "用户撤销了本次状态更新，恢复操作前的任务状态。", null,
                id, null, maxOf(System.currentTimeMillis(), latest + 1),
            ))
        }
        // Drop time constraints this action added before restoring the old plan,
        // otherwise the old plan would be validated against the new blocks.
        journal.optJSONArray("weeklyIds")?.strings()?.toSet()?.let { beforeWeekly ->
            database.timeDao().getAllWeeklyBlocks().map { it.id }.filterNot { it in beforeWeekly }
                .forEach { database.timeDao().deleteWeeklyAndAdvance(it) }
        }
        journal.optJSONArray("overrideIds")?.strings()?.toSet()?.let { beforeOverrides ->
            database.timeDao().getAllDateOverrides().map { it.id }.filterNot { it in beforeOverrides }
                .forEach { database.timeDao().deleteOverrideAndAdvance(it) }
        }
        restoreWorkspace(WINDOWS, journal.nullableString("windows"))
        restoreWorkspace(HELD, journal.nullableString("held"))
        val beforePlanId = journal.nullableString("planId")
        val actualPlan = current()
        val actualPlanId = actualPlan?.id
        if (beforePlanId != actualPlanId) {
            if (beforePlanId == null) {
                val now = LocalDateTime.now()
                require(current()?.segments.orEmpty().none { it.date < now.toLocalDate() ||
                    (it.date == now.toLocalDate() && it.startMinute <= now.hour * 60 + now.minute) }) {
                    "已有安排开始或结束，不能撤销其历史；任务和计划均已保留。"
                }
                database.planDao().archiveCurrentPlan()
            } else {
                val previous = requireNotNull(database.planDao().getPlanWithSegments(beforePlanId)).toDomain()
                try {
                    // Only placements that are still current may bypass validation. A placement
                    // reintroduced by undo must respect the latest time constraints, too.
                    persistPlan(PlanDraft(LocalDateTime.now(), previous.segments, emptyList(), emptyList(),
                        previous.orderedTaskIds, hasManualTaskOrder = previous.hasManualTaskOrder), actualPlan?.segments.orEmpty())
                } catch (invalid: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "原安排无法恢复，本次未撤销；任务、当前安排和时间设置均已保留。${invalid.message.orEmpty()}可在日程或时间设置中处理冲突。",
                        invalid,
                    )
                }
            }
        }
        restoreWorkspace("order", journal.nullableString("order"))
        restoreWorkspace("order-undo", journal.nullableString("orderUndo"))
        restoreWorkspace("draft", journal.nullableString("draft"))
        workspace.remove(UNDO)
        val summary = "已撤销整次操作，任务和安排已恢复；原始执行记录仍然保留。"
        journal.nullableString("requestId")?.let { workspace.put(PlanningWorkspaceEntity("autonomy-request:$it", "该次操作已经撤销，没有重复执行。")) }
        workspace.put(PlanningWorkspaceEntity(RECEIPT, summary))
        workspace.put(PlanningWorkspaceEntity(LAST_REVISION, repairRevision()))
        summary
    }
    }

    private data class RepairResult(val changed: Boolean, val note: String? = null,
        val moved: Int = 0, val added: Int = 0, val removed: Int = 0, val blocked: Boolean = false)

    private suspend fun repair(scheduleNew: Boolean = true, targetDate: LocalDate? = null,
        targetStartMinute: Int = 0, targetEndMinute: Int = 1440): RepairResult {
        val input = input()
        val current = current()
        val now = LocalDateTime.now()
        val draft = AutonomousPlanRepair.repair(input, current, now, runningTaskId(), windows(), scheduleNew, targetDate,
            targetStartMinute, targetEndMinute, excludedFromNewTaskIds = heldTasks())
        val oldSegments = current?.segments.orEmpty()
        val changed = signature(draft.segments) != signature(oldSegments) ||
            draft.orderedTaskIds != current?.orderedTaskIds.orEmpty()
        // 同一段跨重写仍认得出：沿用 stableKey，空则退回段 id。挪动与撤下靠它区分。
        fun lineageKey(segment: PlannedSegment) = segment.stableKey.ifBlank { segment.id }
        val oldByKey = oldSegments.associateBy(::lineageKey)
        val newByKey = draft.segments.associateBy(::lineageKey)
        val moved = oldByKey.count { (key, old) ->
            val replacement = newByKey[key]
            replacement != null && (replacement.date != old.date || replacement.startMinute != old.startMinute || replacement.endMinute != old.endMinute)
        }
        val added = newByKey.keys.count { it !in oldByKey }
        val removed = oldByKey.keys.count { it !in newByKey }
        if (changed) {
            // This local entry point grandfathers only identical confirmed placements. New/changed work is fully checked.
            try {
                validatePlan(draft, oldSegments)
            } catch (invalid: IllegalArgumentException) {
                return RepairResult(false, "任务已保留；当前计划包含需要处理的固定冲突，未覆盖原计划。${invalid.message.orEmpty()}",
                    blocked = true)
            }
            persistPlan(draft, oldSegments)
        }
        val conflicts = AutonomousPlanRepair.conflicts(input, draft, now, windows()).map { it.taskId }.distinct().size
        val pending = draft.pendingTaskIds.distinct().size
        val note = buildString {
            if (!PlanningConstraintValidator.hasExplicitAvailability(input)) append("尚未设置可用时间，事项已保留待安排；可用时间设置后会自动安排。")
            else if (pending > 0) append("${pending} 件任务尚未排完，已保留待安排，不会挤占课程或休息。")
            if (conflicts > 0) append("${conflicts} 件受保护安排存在时间冲突，请在日程中处理。")
        }.ifBlank { null }
        return RepairResult(changed, note, moved, added, removed)
    }

    private suspend fun snapshot(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("tasks", JSONArray(database.taskDao().getAll().map(::encodeTask)))
        put("planId", current()?.id ?: JSONObject.NULL)
        put("order", workspace.get("order")?.payload ?: JSONObject.NULL)
        put("orderUndo", workspace.get("order-undo")?.payload ?: JSONObject.NULL)
        put("draft", workspace.get("draft")?.payload ?: JSONObject.NULL)
        put("windows", workspace.get(WINDOWS)?.payload ?: JSONObject.NULL)
        put("held", workspace.get(HELD)?.payload ?: JSONObject.NULL)
        put("weeklyIds", JSONArray(database.timeDao().getAllWeeklyBlocks().map { it.id }))
        put("overrideIds", JSONArray(database.timeDao().getAllDateOverrides().map { it.id }))
        put("beforeLogIds", JSONArray(database.executionLogDao().getAll().map { it.id }))
    }

    private suspend fun finish(requestId: String, before: JSONObject, summary: String): String {
        val beforeIds = before.getJSONArray("beforeLogIds").strings().toSet()
        val newLogIds = database.executionLogDao().getAll().map { it.id }.filterNot { it in beforeIds }
        val changed = before.getJSONArray("tasks").toString() != JSONArray(database.taskDao().getAll().map(::encodeTask)).toString() ||
            before.nullableString("planId") != current()?.id || before.nullableString("order") != workspace.get("order")?.payload ||
            before.nullableString("windows") != workspace.get(WINDOWS)?.payload || before.nullableString("held") != workspace.get(HELD)?.payload ||
            before.optJSONArray("weeklyIds")?.toString() != JSONArray(database.timeDao().getAllWeeklyBlocks().map { it.id }).toString() ||
            before.optJSONArray("overrideIds")?.toString() != JSONArray(database.timeDao().getAllDateOverrides().map { it.id }).toString() ||
            newLogIds.isNotEmpty()
        before.put("newLogIds", JSONArray(newLogIds))
        before.put("afterTaskIds", JSONArray(database.taskDao().getAll().map { it.id }))
        before.put("requestId", requestId)
        before.put("afterRevision", guardRevision())
        if (changed) workspace.put(PlanningWorkspaceEntity(UNDO, before.toString()))
        workspace.put(PlanningWorkspaceEntity(RECEIPT, summary))
        workspace.put(PlanningWorkspaceEntity("autonomy-request:$requestId", summary))
        workspace.put(PlanningWorkspaceEntity(LAST_REVISION, repairRevision()))
        return summary
    }

    private suspend fun duplicate(requestId: String): String? {
        require(requestId.isNotBlank() && requestId.length <= 200) { "操作标识无效。" }
        return workspace.get("autonomy-request:$requestId")?.payload
    }

    private suspend fun input(): PlanGenerationInput = PlanGenerationInput(
        database.taskDao().getAll().map { it.toDomain() },
        database.timeDao().getAllWeeklyBlocks().map { it.toDomain() },
        database.timeDao().getAllDateOverrides().map { it.toDomain() },
        database.timeDao().getSemesterSettings()?.firstWeekMondayEpochDay?.let(LocalDate::ofEpochDay),
        categoryPreferences = CategoryPreferences.normalized(database.categoryPreferenceDao().getAll().associate { it.toDomainPair() }),
        manualTaskOrder = order(),
    )
    private suspend fun current() = database.planDao().getCurrentPlanWithSegments()?.toDomain()
    private suspend fun order(): List<String> = workspace.get("order")?.let { JSONArray(it.payload).strings() }.orEmpty()
    private suspend fun enabled() = workspace.get(SETTINGS)?.payload != "false"
    private suspend fun runningTaskId(): String? = workspace.get(RoomExecutionSessionRepository.KEY)?.let { ExecutionSessionCodec.decode(it.payload).taskId }

    private suspend fun guardRevision(): String {
        return digest(listOf(PlanningRevision.of(input(), current(), order()),
            database.timeDao().getSemesterSettings(), database.executionLogDao().getAll(),
            workspace.get(RoomExecutionSessionRepository.KEY)?.payload, workspace.get(WINDOWS)?.payload, workspace.get(HELD)?.payload,
            workspace.get("draft")?.payload, workspace.get("assistant")?.payload, workspace.get("tracks")?.payload,
        ).joinToString("\n"))
    }

    /** 引擎要不要重跑的印章只盖在外部输入上；计划本身的变化是结果，不是诱因。 */
    private suspend fun repairRevision(): String = digest(listOf(
        PlanningRevision.of(input(), null, order()),
        workspace.get(RoomExecutionSessionRepository.KEY)?.payload,
        workspace.get(WINDOWS)?.payload, workspace.get(HELD)?.payload,
        LocalDate.now(),
    ).joinToString("\n"))

    private suspend fun validatePlan(draft: PlanDraft, grandfathered: List<PlannedSegment>) {
        val input = input()
        val active = input.tasks.filter { it.status.isActive }.associateBy { it.id }
        val required = input.tasks.filter { it.userPriority == TaskPriority.REQUIRED }.map { it.id }.toSet()
        val running = runningTaskId()
        val requestedWindows = windows()
        val now = LocalDateTime.now()
        val minute = now.hour * 60 + now.minute
        fun same(first: PlannedSegment, second: PlannedSegment) = first.taskId == second.taskId && first.date == second.date &&
            first.startMinute == second.startMinute && first.endMinute == second.endMinute &&
            first.trackId == second.trackId && first.isLocked == second.isLocked
        current()?.segments.orEmpty().forEach { old ->
            val begun = old.date < now.toLocalDate() || (old.date == now.toLocalDate() && old.startMinute <= minute)
            val task = active[old.taskId]
            // 与编排层同一条判据：用户后来把任务日期改到这天之后，留在旧日子的固定时段不再算
            // 「用户拍板」，允许它跟着搬走；其余固定安排必须原样保留。
            val protected = begun || (task != null && ((old.authority == SegmentAuthority.USER_PINNED &&
                !AutonomousPlanRepair.isLeftBehind(task, old)) ||
                old.isLocked || old.taskId in required || old.taskId == running))
            require(!protected || draft.segments.any { same(old, it) }) { "已开始、锁定、用户确认、不可避免或正在专注的安排必须保留。" }
        }
        draft.segments.forEach { segment ->
            if (grandfathered.any { same(it, segment) }) return@forEach
            require(segment.taskId in active) { "不能为已经结束或不存在的任务新增安排。" }
            val task = active.getValue(segment.taskId)
            require(task.scheduledForDate?.isAfter(segment.date) != true && task.dueDate?.isBefore(segment.date) != true) {
                "新增安排不能越过用户指定的日期或截止日期。"
            }
            val taskWindows = requestedWindows.filter { it.taskId == segment.taskId }
            require(taskWindows.isEmpty() || taskWindows.any { it.date == segment.date &&
                segment.startMinute >= it.startMinute && segment.endMinute <= it.endMinute }) { "新增安排不能越过用户指定的时段。" }
            require(segment.date > now.toLocalDate() || (segment.date == now.toLocalDate() && segment.startMinute >= minute)) { "不能新增过去的安排。" }
            PlacementValidator.requireValid(segment, draft.segments, input.weeklyBlocks, input.dateOverrides,
                input.semesterFirstWeekMonday, required)
            require((segment.startMinute until segment.endMinute).all { minuteInSegment ->
                PlanningConstraintValidator.isAvailable(segment.date, minuteInSegment, minuteInSegment + 1,
                    input.weeklyBlocks, input.dateOverrides, input.semesterFirstWeekMonday)
            }) { "新增安排必须位于已设置的可用时间内。" }
        }
    }

    private suspend fun persistPlan(draft: PlanDraft, grandfathered: List<PlannedSegment>) {
        validatePlan(draft, grandfathered)
        val id = UUID.randomUUID().toString()
        val createdAt = maxOf(System.currentTimeMillis(), (database.planDao().latestCreatedAt() ?: 0L) + 1)
        database.planDao().replaceCurrentPlan(PlanEntity(id, createdAt, true),
            draft.segments.map { segment -> segment.toSegmentEntity(id) },
            draft.orderedTaskIds.distinct().mapIndexed { index, taskId -> PlanTaskOrderEntity(id, taskId, index, draft.hasManualTaskOrder) })
        plans.saveTaskOrder(draft.orderedTaskIds)
    }

    private suspend fun restoreWorkspace(key: String, value: String?) {
        if (value == null) workspace.remove(key) else workspace.put(PlanningWorkspaceEntity(key, value))
    }
    private suspend fun windows(): List<AutonomousTimeWindow> = workspace.get(WINDOWS)?.let { row ->
        JSONArray(row.payload).objects().map { j -> AutonomousTimeWindow(j.getString("task"), LocalDate.parse(j.getString("date")),
            j.getInt("start"), j.getInt("end"), j.optBoolean("exact")) }
    }.orEmpty()
    private suspend fun heldTasks(): Set<String> = workspace.get(HELD)?.let { JSONArray(it.payload).strings().toSet() }.orEmpty()
    private suspend fun putWindows(values: List<AutonomousTimeWindow>) = workspace.put(PlanningWorkspaceEntity(WINDOWS,
        JSONArray(values.map { window -> JSONObject().apply {
            put("task", window.taskId); put("date", window.date.toString()); put("start", window.startMinute)
            put("end", window.endMinute); put("exact", window.exactStart)
        } }).toString()))

    private data class CreatedItems(val ids: List<String>, val estimates: Int, val dueIds: Set<String>)

    /**
     * [trustProvided] means the caller already read the sentence with the rule layer, so the
     * weaker keyword interpreter must not overwrite its title or explicit duration.
     */
    private suspend fun createItems(candidates: List<ArrangementCandidate>, date: LocalDate, dateExplicit: Boolean,
        recordOnly: Boolean, originalText: String?, trustProvided: Boolean,
        newWindows: MutableList<AutonomousTimeWindow>, pinDate: Boolean = true): CreatedItems {
        if (candidates.isEmpty()) return CreatedItems(emptyList(), 0, emptySet())
        val localCandidates = if (trustProvided) emptyList()
            else originalText?.let { ArrangementAssistantInterpreter.interpret(it).candidates }.orEmpty()
        val effectiveCandidates = AutonomousCaptureFacts.reconcile(localCandidates, candidates)
        val ids = mutableListOf<String>()
        val dueIds = mutableSetOf<String>()
        var estimates = 0
        effectiveCandidates.forEach { candidate ->
            val id = UUID.randomUUID().toString()
            val local = localCandidates.singleOrNull { normalized(cleanTitle(it.title)) == normalized(cleanTitle(candidate.title)) }
            val explicitDuration = if (trustProvided) candidate.durationMinutes != null && !candidate.durationEstimated
                else local?.durationMinutes != null && local.durationMinutes == candidate.durationMinutes
            val duration = candidate.durationMinutes ?: 30
            if (!explicitDuration) estimates++
            val dueOnly = candidate.dueDate != null
            tasks.save(TaskDraft(
                id = id, displayName = cleanTitle(candidate.title),
                description = originalText?.trim()?.takeIf { it.isNotBlank() } ?: candidate.title.trim(),
                category = candidate.category, totalDurationMinutes = duration,
                dueDate = candidate.dueDate,
                scheduledForDate = when {
                    dueOnly -> null
                    !pinDate -> null
                    else -> date.takeUnless { recordOnly && !dateExplicit }
                },
                inputSources = TaskInputSources(
                    category = if (candidate.category == TaskCategory.UNSPECIFIED) TaskInputSource.UNKNOWN else TaskInputSource.PLANNING_ESTIMATE,
                    priority = TaskInputSource.UNKNOWN, days = TaskInputSource.UNKNOWN,
                    duration = if (explicitDuration) TaskInputSource.USER_INPUT else TaskInputSource.PLANNING_ESTIMATE,
                    dueDate = if (candidate.dueDate != null) TaskInputSource.USER_INPUT else TaskInputSource.UNKNOWN,
                    scheduledDate = when {
                        dueOnly || !pinDate -> TaskInputSource.UNKNOWN
                        dateExplicit -> TaskInputSource.USER_INPUT
                        recordOnly -> TaskInputSource.UNKNOWN
                        else -> TaskInputSource.PLANNING_ESTIMATE
                    },
                ),
            ))
            ids += id
            if (dueOnly) {
                dueIds += id
            } else if (pinDate) {
                val hintSource = local ?: candidate.takeIf { trustProvided }
                timeWindow(id, date, hintSource, originalText, dateExplicit)?.let(newWindows::add)
            }
        }
        return CreatedItems(ids, estimates, dueIds)
    }

    private fun clockText(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)

    private fun timeWindow(id: String, date: LocalDate, local: ArrangementCandidate?, text: String?, dateExplicit: Boolean): AutonomousTimeWindow? {
        val hint = local?.timeHint
        val explicit = hint?.explicitStartMinute
        if (explicit != null) {
            val rangeEnd = hint.windowLabel?.let { label ->
                Regex("(?:到|至|-)\\s*(\\d{1,2})\\s*(?:点|:|：)\\s*(?:(\\d{1,2})\\s*分?)?").find(label)?.let { match ->
                    var hour = match.groupValues[1].toInt()
                    if (explicit >= 12 * 60 && hour in 1..11) hour += 12
                    hour * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
                }
            }
            val end = hint?.explicitEndMinute ?: rangeEnd ?: 1440
            require(end in (explicit + 1)..1440) { "请检查明确时段的起止时间。" }
            return AutonomousTimeWindow(id, date, explicit, end, true)
        }
        val label = hint?.windowLabel ?: text.orEmpty()
        val range = when {
            label.contains("今晚") || label.contains("晚上") -> 18 * 60 to 1440
            label.contains("下午") -> 12 * 60 to 18 * 60
            label.contains("上午") || label.contains("早上") -> 8 * 60 to 12 * 60
            label.contains("中午") -> 11 * 60 to 14 * 60
            dateExplicit -> 0 to 1440
            else -> return null
        }
        return AutonomousTimeWindow(id, date, range.first, range.second)
    }

    companion object {
        const val SETTINGS = "autonomy-settings"
        const val UNDO = "autonomy-undo"
        const val RECEIPT = "autonomy-receipt"
        const val LAST_REVISION = "autonomy-last-revision"
        const val WINDOWS = "autonomy-time-windows"
        const val HELD = "autonomy-held-tasks"
        private fun signature(segments: List<PlannedSegment>) = segments.map {
            listOf(it.taskId, it.date, it.startMinute, it.endMinute, it.isLocked, it.trackId).joinToString("|")
        }.sorted()
        private fun normalized(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        private fun cleanTitle(text: String): String {
            var title = text.trim().replace(Regex("^(?:只记着|只记下|只记录|仅记录|只新增|不要安排|暂不安排|先不安排)[：:]?\\s*"), "")
            val prefix = Regex("^(?:(?:请)?(?:帮我)?(?:新增|新建|添加|记下|记录)(?:一个|一项)?(?:任务|事项)?[:：]?|今天|明天|后天|今晚|下午|上午|早上|晚上|中午|(?:下周|本周|这周|周|星期)[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]?|\\d{4}-\\d{1,2}-\\d{1,2})\\s*")
            repeat(5) { title = title.replaceFirst(prefix, "").trim() }
            return title.ifBlank { text.trim() }
        }
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun JSONArray.strings() = (0 until length()).map(::getString)
        private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
        private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
        private fun JSONObject.nullableInt(key: String) = if (isNull(key)) null else getInt(key)
        private fun JSONObject.nullableLong(key: String) = if (isNull(key)) null else getLong(key)
        private fun encodeTask(t: TaskEntity) = JSONObject().apply {
            put("id", t.id); put("description", t.description); put("displayName", t.displayName); put("category", t.category)
            put("priority", t.userPriority); put("days", t.estimatedDays ?: JSONObject.NULL)
            put("duration", t.totalDurationMinutes ?: JSONObject.NULL); put("due", t.dueDateEpochDay ?: JSONObject.NULL)
            put("status", t.status); put("summary", t.completionSummary ?: JSONObject.NULL)
            put("actual", t.actualDurationMinutes ?: JSONObject.NULL); put("progress", t.progressPercent ?: JSONObject.NULL)
            put("postpone", t.postponeCount); put("created", t.createdAtEpochMillis); put("updated", t.updatedAtEpochMillis)
            put("result", t.completionResult ?: JSONObject.NULL); put("scheduled", t.scheduledForEpochDay ?: JSONObject.NULL)
            put("sources", t.inputSources)
        }
        private fun decodeTask(j: JSONObject) = TaskEntity(j.getString("id"), j.getString("description"),
            j.getString("displayName"), j.getString("category"), j.getString("priority"), j.nullableInt("days"),
            j.nullableInt("duration"), j.nullableLong("due"), j.getString("status"), j.nullableString("summary"),
            j.nullableInt("actual"), j.nullableInt("progress"), j.getInt("postpone"), j.getLong("created"),
            j.getLong("updated"), j.nullableString("result"), j.nullableLong("scheduled"), j.getString("sources"))
    }
}
