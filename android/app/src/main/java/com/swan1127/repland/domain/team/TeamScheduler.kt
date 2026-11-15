package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 确定性调度内核（§5）：LLM 只做文秘，重排是内定固定流程。
 *
 * 安排 = 负载定谁 + 某天空闲定排哪段：
 * 1. 待排任务按「依赖上游先排 + 期限」的拓扑序推进；
 * 2. 未分配任务 → 负载最低、负责说明最适配的成员（队长也是劳动力，不特殊豁免）；
 * 3. 从成员空闲窗口里找最早能容纳的时段，逐日推进直到排完工时；
 *    中期任务不早于所属阶段的开始日——阶段窗口就是环节顺序；
 * 4. 落进个人日程（唯一真相源），与既有块重叠 → 标红由本人裁决；
 * 5. 课表未导入的成员照排但标低置信度；
 * 6. 顺带给出每个环节的负载体检，排不排得下让数据说话。
 */
object TeamScheduler {

    data class ScheduleInput(
        val teamId: String,
        val tasks: List<TeamTask>,
        val members: List<MemberCard>,
        val phases: List<Phase> = emptyList(),
        val existingBlocks: List<PersonalBlock> = emptyList(),
        val startDate: LocalDate,
        val competitionDeadline: LocalDate? = null,
        val bufferDays: Int = 1,
        val maxHorizonDays: Int = 60,
        /**
         * 本地成员的真实占用：课程、个人已确认安排、私事。只传时间和「在忙」，
         * 内容不进团队域；[personalLoadHours] 是这些个人承诺折算的负载，用来定人。
         */
        val personalBusy: List<PersonalBlock> = emptyList(),
        val personalLoadHours: Map<String, Double> = emptyMap(),
        /** 真实当下时间：当天已经过去的时段不再安排，避免「排到上午」却是深夜才生成的草案。 */
        val now: LocalDateTime? = null,
    )

    data class ScheduleOutput(
        val draft: TeamPlanDraft,
        val loadByMember: Map<String, Double>,
        val personalLoadByMember: Map<String, Double> = emptyMap(),
        val issues: List<DeadlineEngine.DeadlineIssue> = emptyList(),
        val stageLoads: List<StageLoad> = emptyList(),
        val gateReadiness: List<GateReadiness> = emptyList(),
        /** 结构化诊断（排序后可直接给人看）；notes 仍是面向 UI 的句子。 */
        val diagnostics: List<TeamDiagnostic> = emptyList(),
    ) {
        val scheduledHours: Double get() = draft.placements.sumOf { it.minutes / 60.0 }
    }

    fun schedule(input: ScheduleInput): ScheduleOutput {
        val deadline = DeadlineEngine.recompute(input.tasks, input.phases, input.competitionDeadline, input.bufferDays)
        val stageLoads = StagePlanner.analyze(deadline.tasks, input.phases, input.members, input.startDate)
        val gates = GatePlanner.evaluate(
            deadline.tasks, input.phases, input.members, input.startDate, input.bufferDays,
        )
        val personalLoad = input.members.associate {
            it.memberId to input.personalLoadHours.getOrDefault(it.memberId, 0.0)
        }

        val pending = deadline.tasks
            .filter { it.active && it.rank != TaskRank.WONT && it.tier != TaskTier.SHORT_TERM }
        val ordered = scheduleOrder(pending, input.phases, input.competitionDeadline)

        // 「今天」从下一个半小时边界起步：已经过去的时段不再往里塞任务。
        val nowDate = input.now?.toLocalDate()
        val todayFloor = input.now?.let { ceilToStep(it.hour * 60 + it.minute) } ?: 0

        // 已占用块分三类（§5 个人日程唯一真相源）：
        // 刚性（课表/个人/私有/本队已排）必须避开；他队可见块允许重叠 → 冲突标红由本人裁决；
        // 本地个人真实占用（课程、已确认个人安排）同样刚性，但只以「在忙」参与，不泄露内容。
        val rigidBlocks = input.existingBlocks
            .filter {
                it.sourceTeamId == null || it.sourceTeamId == input.teamId ||
                    it.visibility == BlockVisibility.PRIVATE_BUSY
            }
            .toMutableList()
        rigidBlocks += input.personalBusy
        val softBlocks = input.existingBlocks.filter {
            it.sourceTeamId != null && it.sourceTeamId != input.teamId && it.visibility == BlockVisibility.TEAM_VISIBLE
        }
        val placements = mutableListOf<Placement>()
        val unassigned = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val unassignedReasons = LinkedHashMap<String, TeamDiagnostic>()

        val earliestReady = HashMap<String, LocalDate>() // 上游完成日 + 缓冲
        val dayLoad = HashMap<Pair<String, LocalDate>, Int>() // 每人每日已排分钟（容量硬约束）
        // 个人已确认的任务/专注同样消耗当天容量；课程只挡时间，不占“还能做多少”的额度。
        input.personalBusy.filter { it.countsTowardCapacity }.forEach { busy ->
            dayLoad[busy.memberId to busy.date] = dayLoad.getOrDefault(busy.memberId to busy.date, 0) + busy.asWindow().minutes
        }

        // 已完成/已取消的上游不再挡路；仍在办但没排进日程的上游，下游必须先等着。
        val satisfied = deadline.tasks
            .filter { !it.active || it.rank == TaskRank.WONT }
            .map { it.taskId }
            .toSet()

        for (task in ordered) {
            val unresolved = task.dependsOn.filter { it !in satisfied && it !in earliestReady }
            if (unresolved.isNotEmpty()) {
                unassigned += task.taskId
                notes += "「${task.title}」的上游还没排进日程，先不动它"
                unassignedReasons[task.taskId] = TeamDiagnostics.unassigned(
                    task.taskId, task.title, TeamDiagnostics.TASK_WAITING_UPSTREAM,
                    "的上游还没排进日程，先不动它", "先把上游排进日程，它才会被解锁",
                )
                continue
            }
            val blockedUntil = task.dependsOn
                .mapNotNull { earliestReady[it] }
                .maxOrNull() ?: input.startDate

            val candidateId = task.assignedTo ?: pickMember(task, input, placements, rigidBlocks)
            if (candidateId == null) {
                unassigned += task.taskId
                notes += "「${task.title}」没有可排的成员"
                unassignedReasons[task.taskId] = TeamDiagnostics.unassigned(
                    task.taskId, task.title, TeamDiagnostics.TASK_NO_MEMBER,
                    "没有可排的成员", "加队员，或先给现有成员导入空闲",
                )
                continue
            }
            val member = input.members.first { it.memberId == candidateId }
            var remainingMinutes = (task.estimatedHours * 60).toInt()
            // 阶段窗口就是环节顺序：中期任务不提前占它所属环节之前的时间。
            val phaseStart = task.phaseId
                ?.let { id -> input.phases.firstOrNull { it.phaseId == id }?.start }
                ?: input.startDate
            var cursor = maxOf(input.startDate, blockedUntil, phaseStart)
            if (nowDate != null && cursor.isBefore(nowDate)) cursor = nowDate
            val horizon = cursor.plusDays(input.maxHorizonDays.toLong())
            var placedAny = false

            while (remainingMinutes > 0 && !cursor.isAfter(horizon)) {
                val deadlineDate = task.effectiveDeadline(input.phases, input.competitionDeadline)
                if (deadlineDate != null && cursor > deadlineDate) {
                    notes += "「${task.title}」在期限 $deadlineDate 前排不完，进入待安排"
                    unassignedReasons[task.taskId] = TeamDiagnostics.unassigned(
                        task.taskId, task.title, TeamDiagnostics.TASK_PAST_DEADLINE,
                        "在期限 $deadlineDate 前排不完，进入待安排", "前移截止、缩小范围，或改派他人",
                    )
                    break
                }
                val capacityLeft = (member.dailyCapacityHours * 60).toInt() - dayLoad.getOrDefault(candidateId to cursor, 0)
                val earliestStart = if (nowDate != null && cursor == nowDate) todayFloor else 0
                val chunk = if (capacityLeft < 30) null
                else firstFit(member, cursor, minOf(remainingMinutes, capacityLeft), rigidBlocks, earliestStart)
                if (chunk != null) {
                    val placement = Placement(
                        taskId = task.taskId,
                        memberId = candidateId,
                        date = cursor,
                        startMinute = chunk.startMinute,
                        endMinute = chunk.endMinute,
                        lowConfidence = member.lowConfidence,
                        conflicts = conflictsFor(member.memberId, cursor, chunk, softBlocks),
                    )
                    placements += placement
                    rigidBlocks += placement.toBlock(input.teamId)
                    dayLoad[candidateId to cursor] = dayLoad.getOrDefault(candidateId to cursor, 0) + chunk.minutes
                    remainingMinutes -= chunk.minutes
                    placedAny = true
                }
                cursor = cursor.plusDays(1)
            }

            // 只有真的排进了日程，下游才算解锁；一点没排上的上游会继续挡住下游。
            if (placedAny) {
                earliestReady[task.taskId] =
                    placements.last { it.taskId == task.taskId }.date.plusDays(input.bufferDays.toLong())
            }
            if (!placedAny || remainingMinutes > 0) {
                unassigned += task.taskId
                if (placedAny) {
                    val left = StagePlanner.hours(remainingMinutes / 60.0)
                    notes += "「${task.title}」还差 $left 小时没排进去（后面没有可用的空闲或容量）"
                    if (task.taskId !in unassignedReasons) {
                        unassignedReasons[task.taskId] = TeamDiagnostics.unassigned(
                            task.taskId, task.title, TeamDiagnostics.TASK_UNASSIGNED,
                            "还差 $left 小时没排进日程", "延长截止、缩小范围，或改派他人",
                        )
                    }
                }
            }
        }

        val lowConfidenceNames = placements
            .filter { it.lowConfidence }
            .map { it.memberId }
            .distinct()
            .map { id -> input.members.firstOrNull { it.memberId == id }?.name ?: id }
        val diagnostics = buildList {
            addAll(TeamDiagnostics.fromDeadline(deadline.issues))
            addAll(TeamDiagnostics.fromStageLoads(stageLoads))
            addAll(TeamDiagnostics.fromGates(gates))
            addAll(unassignedReasons.values)
            placements.filter { it.conflicts.isNotEmpty() }
                .groupBy { it.memberId }
                .forEach { (memberId, list) -> TeamDiagnostics.conflicts(list.size, memberId)?.let { add(it) } }
            TeamDiagnostics.lowConfidence(lowConfidenceNames)?.let { add(it) }
        }
        deadline.issues.filter { it.kind == DeadlineEngine.IssueKind.CYCLE }.forEach { notes += it.message }

        val load = loadByMember(
            input.members, placements,
            input.existingBlocks.filter { it.sourceTeamId == input.teamId },
            input.teamId,
        )
        return ScheduleOutput(
            draft = TeamPlanDraft(placements.toList(), unassigned.distinct(), notes = notes),
            loadByMember = load,
            personalLoadByMember = personalLoad,
            issues = deadline.issues,
            stageLoads = stageLoads,
            gateReadiness = gates,
            diagnostics = diagnostics,
        )
    }

    /** 会议排期看全员空闲交集（§5）：发起会议才显示全员可排时段列表。 */
    fun meetingCandidates(
        members: List<MemberCard>,
        existingBlocks: List<PersonalBlock>,
        date: LocalDate,
        durationMinutes: Int,
        teamId: String,
        maxCandidates: Int = 5,
        personalBusy: List<PersonalBlock> = emptyList(),
        now: LocalDateTime? = null,
    ): List<MeetingSlot> {
        if (members.isEmpty()) return emptyList()
        // 会议要全员到齐：一切既有块（含本队、他队、本地个人占用）都视为占用。
        val occupied = existingBlocks + personalBusy
        val floor = if (now != null && now.toLocalDate() == date) {
            ceilToStep(now.hour * 60 + now.minute + 30)
        } else {
            0
        }
        val windows = members.map { member -> freeWindows(member, date, occupied, excludeTeamId = null) }
        val intersected = windows.reduce { acc, next -> acc.flatMap { a -> next.mapNotNull { b -> intersect(a, b) } } }
        val slots = mutableListOf<MeetingSlot>()
        for (window in intersected.sortedBy { it.startMinute }) {
            var start = maxOf(window.startMinute, floor)
            while (start + durationMinutes <= window.endMinute && slots.size < maxCandidates) {
                slots += MeetingSlot(date, start, start + durationMinutes, members.map { it.memberId })
                start += 30
            }
            if (slots.size >= maxCandidates) break
        }
        return slots
    }

    /**
     * 多天会议建议（§5）：每天先取共同空闲最宽的两段，再按「下午 14–18 > 上午 9–12 >
     * 晚上 18–21」打分，最后按日期排——一次给出一周里真正约得上的时段，而不是只列当天。
     */
    fun meetingSuggestions(
        members: List<MemberCard>,
        existingBlocks: List<PersonalBlock>,
        fromDate: LocalDate,
        days: Int = 8,
        durationMinutes: Int = 60,
        personalBusy: List<PersonalBlock> = emptyList(),
        now: LocalDateTime? = null,
        maxPerDay: Int = 2,
        limit: Int = 8,
    ): List<MeetingSuggestion> {
        if (members.isEmpty() || days <= 0) return emptyList()
        val occupied = existingBlocks + personalBusy
        val attendeeIds = members.map { it.memberId }
        val today = now?.toLocalDate()
        val picked = mutableListOf<MeetingSuggestion>()
        for (offset in 0 until days) {
            val date = fromDate.plusDays(offset.toLong())
            val floor = if (now != null && date == today) {
                ceilToStep(now.hour * 60 + now.minute + 30)
            } else {
                0
            }
            val windows = members
                .map { member -> freeWindows(member, date, occupied, excludeTeamId = null) }
                .reduce { acc, next -> acc.flatMap { a -> next.mapNotNull { b -> intersect(a, b) } } }
            picked += windows.mapNotNull { window ->
                val start = maxOf(window.startMinute, floor)
                if (window.endMinute - start < durationMinutes) return@mapNotNull null
                MeetingSuggestion(
                    slot = MeetingSlot(date, start, start + durationMinutes, attendeeIds),
                    score = slotScore(start),
                    freeMinutes = window.minutes,
                )
            }
                .sortedWith(
                    compareByDescending<MeetingSuggestion> { it.freeMinutes }
                        .thenByDescending { it.score }
                        .thenBy { it.slot.startMinute },
                )
                .take(maxPerDay)
        }
        return picked
            .sortedWith(
                compareBy<MeetingSuggestion> { it.slot.date }
                    .thenByDescending { it.score }
                    .thenBy { it.slot.startMinute },
            )
            .take(limit)
    }

    private fun ceilToStep(minute: Int, step: Int = 30): Int = ((minute + step - 1) / step) * step

    /** 下午 14–18 最易接受，其次上午 9–12、晚上 18–21；午饭和深夜分数低。 */
    private fun slotScore(startMinute: Int): Int = when {
        startMinute >= 14 * 60 && startMinute < 18 * 60 -> 3
        startMinute >= 9 * 60 && startMinute < 12 * 60 -> 2
        startMinute >= 18 * 60 && startMinute < 21 * 60 -> 1
        startMinute >= 12 * 60 && startMinute < 14 * 60 -> 0
        else -> 1
    }

    // ---------- 内部实现 ----------

    private fun pickMember(
        task: TeamTask,
        input: ScheduleInput,
        placements: List<Placement>,
        occupied: List<PersonalBlock>,
    ): String? {
        // 队长也是劳动力：只是权限全，不是不干活；真要留出协调时间，应由个人排期或负载体现。
        val candidates = input.members
        if (candidates.isEmpty()) return null
        return candidates
            .map { member ->
                val load = loadOf(member.memberId, placements, occupied, input.teamId) +
                    input.personalLoadHours.getOrDefault(member.memberId, 0.0)
                member to (member.affinityWith(task.title) to load)
            }
            .sortedWith(
                compareByDescending<Pair<MemberCard, Pair<Int, Double>>> { it.second.first }
                    .thenComparingDouble { it.second.second }
                    .thenBy { it.first.memberId },
            )
            .first().first.memberId
    }

    /**
     * 拓扑序 + 期限优先：上游一定先排；就绪的任务里期限近的先排，同期限 MUST 优先。
     * Kahn 用优先队列实现，顺序完全确定（同样的任务图永远同样的结果）。
     */
    private fun scheduleOrder(
        tasks: List<TeamTask>,
        phases: List<Phase>,
        competitionDeadline: LocalDate?,
    ): List<TeamTask> {
        val byId = tasks.associateBy { it.taskId }
        val indegree = tasks.associate { it.taskId to it.dependsOn.count { dep -> dep in byId } }.toMutableMap()
        val dependents = HashMap<String, MutableList<String>>()
        tasks.forEach { task ->
            task.dependsOn.forEach { dep ->
                if (dep in byId) dependents.getOrPut(dep) { mutableListOf() } += task.taskId
            }
        }
        val comparator = compareBy<TeamTask>(
            { it.effectiveDeadline(phases, competitionDeadline) ?: LocalDate.MAX },
            { rankWeight(it.rank) },
            { it.taskId },
        )
        val ready = java.util.PriorityQueue(comparator)
        tasks.filter { indegree.getValue(it.taskId) == 0 }.forEach { ready += it }

        val order = mutableListOf<TeamTask>()
        while (ready.isNotEmpty()) {
            val task = ready.poll()
            order += task
            dependents[task.taskId].orEmpty().forEach { dependentId ->
                val left = indegree.getValue(dependentId) - 1
                indegree[dependentId] = left
                if (left == 0) byId[dependentId]?.let { ready += it }
            }
        }
        if (order.size < tasks.size) {
            val seen = order.mapTo(HashSet()) { it.taskId }
            order += tasks.filter { it.taskId !in seen }.sortedWith(comparator)
        }
        return order
    }

    private fun rankWeight(rank: TaskRank): Int = when (rank) {
        TaskRank.MUST -> 0
        TaskRank.SHOULD -> 1
        TaskRank.COULD -> 2
        TaskRank.WONT -> 3
    }

    private fun firstFit(
        member: MemberCard,
        date: LocalDate,
        remainingMinutes: Int,
        occupied: List<PersonalBlock>,
        earliestStartMinute: Int = 0,
    ): FreeWindow? {
        val dayWindows = freeWindows(member, date, occupied, null)
        for (window in dayWindows.sortedBy { it.startMinute }) {
            var start = maxOf(window.startMinute, earliestStartMinute)
            while (start < window.endMinute) {
                val end = minOf(start + remainingMinutes, window.endMinute)
                val candidate = FreeWindow(start, end)
                if (occupied.none { it.memberId == member.memberId && it.date == date && candidate.overlaps(it.asWindow()) }) {
                    return candidate
                }
                start += 30
            }
        }
        return null
    }

    private fun freeWindows(member: MemberCard, date: LocalDate, occupied: List<PersonalBlock>, excludeTeamId: String?): List<FreeWindow> {
        val base = member.weeklyAvailability[date.dayOfWeek].orEmpty()
        val blocking = occupied.filter {
            it.memberId == member.memberId && it.date == date && (excludeTeamId == null || it.sourceTeamId != excludeTeamId)
        }
        if (base.isEmpty()) {
            // 未导入课表 → 保守给一大段空闲（§5：照排但标低置信度），仍要减去已占用块。
            return subtractWindows(listOf(FreeWindow(8 * 60, 22 * 60)), blocking.map { it.asWindow() })
        }
        return subtractWindows(base, blocking.map { it.asWindow() })
    }

    private fun subtractWindows(base: List<FreeWindow>, cuts: List<FreeWindow>): List<FreeWindow> {
        var current = base.sortedBy { it.startMinute }
        for (cut in cuts) {
            current = current.flatMap { window ->
                when {
                    cut.endMinute <= window.startMinute || cut.startMinute >= window.endMinute -> listOf(window)
                    cut.startMinute <= window.startMinute && cut.endMinute >= window.endMinute -> emptyList()
                    cut.startMinute <= window.startMinute -> listOf(FreeWindow(cut.endMinute, window.endMinute))
                    cut.endMinute >= window.endMinute -> listOf(FreeWindow(window.startMinute, cut.startMinute))
                    else -> listOf(FreeWindow(window.startMinute, cut.startMinute), FreeWindow(cut.endMinute, window.endMinute))
                }
            }
        }
        return current.filter { it.minutes >= 30 }
    }

    private fun intersect(a: FreeWindow, b: FreeWindow): FreeWindow? {
        val start = maxOf(a.startMinute, b.startMinute)
        val end = minOf(a.endMinute, b.endMinute)
        return if (end > start) FreeWindow(start, end) else null
    }

    private fun conflictsFor(
        memberId: String,
        date: LocalDate,
        chunk: FreeWindow,
        occupied: List<PersonalBlock>,
    ): List<PersonalBlock> = occupied.filter {
        it.memberId == memberId && it.date == date && chunk.overlaps(it.asWindow())
    }

    private fun Placement.toBlock(teamId: String): PersonalBlock = PersonalBlock(
        blockId = "place-$taskId-$memberId-$date-$startMinute",
        memberId = memberId,
        date = date,
        startMinute = startMinute,
        endMinute = endMinute,
        sourceTeamId = teamId,
        taskId = taskId,
    )

    fun loadOf(memberId: String, placements: List<Placement>, blocks: List<PersonalBlock>, teamId: String): Double {
        val placed = placements.filter { it.memberId == memberId }.sumOf { it.minutes }
        val existing = blocks.filter { it.memberId == memberId && it.sourceTeamId == teamId }.sumOf { it.endMinute - it.startMinute }
        return (placed + existing) / 60.0
    }

    fun loadByMember(
        members: List<MemberCard>,
        placements: List<Placement>,
        blocks: List<PersonalBlock>,
        teamId: String,
    ): Map<String, Double> = members.associate { it.memberId to loadOf(it.memberId, placements, blocks, teamId) }
}
