package com.swan1127.repland.domain.model

/**
 * agent 排这一批时的行事风格：只改"两件之间留多少"，一次点换掉整套方案。
 *
 * 两个选项都会把每一件事尽量排进去，差别只在密度不在排不排得上——用户在卡上换风格时
 * 看到列表凭空少几项，比没有风格更糟。
 */
enum class SchedulingStyle(
    val key: String,
    val label: String,
    val gapMinutes: Int,
    /** 点之前就得说清它会怎么排。 */
    val note: String,
) {
    PACKED("packed", "排满", 5, "一件接一件，醒着的时间都排上（夜里留给睡眠）"),
    CALM("calm", "宽松", 30, "两件之间留半小时，一天铺得开一些"),
    ;

    companion object {
        fun fromKey(raw: String?): SchedulingStyle =
            entries.firstOrNull { it.key == raw?.trim()?.lowercase() } ?: PACKED
    }
}

/**
 * Places the chat's items onto real clock times.
 *
 * The model reads "下午三点复习高数，另外写实验报告" well and knows nothing about the class
 * that already occupies 15:00. So it is not asked for a slot at all: it reports a duration,
 * an optional explicit time and a priority, and this object solves the packing against the
 * day that actually exists.
 *
 * Four behaviours are deliberate, and each exists because the obvious alternative is wrong:
 *
 *  - **Priority decides who gets the room.** Items are placed in priority order, so a
 *    REQUIRED task claims the morning and a LOW one lands wherever is left. If priority did
 *    not change the output, the user's setting would be decoration.
 *  - **The day gets filled.** An item without an explicit time is packed after the cursor
 *    with a small gap, so "把今天排满" produces a schedule rather than a list of TODOs.
 *  - **Parallel only on purpose.** Placing every item at its own earliest free slot would
 *    stack the whole list on 08:00 across parallel lanes. Sequential work therefore shares
 *    one cursor; a second lane is used only when the user pinned two things to the same
 *    time. That is the one case where "同一时间做多件事" is true.
 *  - **A full day nests instead of refusing.** When there is no free window left, an item is
 *    put wholly inside an existing task block as its 下属事件 — the model the day timeline
 *    already draws and the write path already accepts without a second consent. Only saying
 *    "放不下" would hand a crowded day back to the user.
 *  - **The breathing room is the user's call, not ours.** [SchedulingStyle] is one number
 *    (the gap between two items) that moves the whole plan at once, so tapping a style on the
 *    card really swaps the whole plan rather than nudging one item. Both styles still try to
 *    place everything; a style that quietly drops items would be worse than no style at all.
 */
object ChatDayPlanner {

    const val DEFAULT_DAY_START_MINUTE = 8 * 60
    const val DEFAULT_DAY_END_MINUTE = 22 * 60

    /** 08:00–22:00 是助手替没点名的事项挑空档的搜索区间，不是「一天只有这几个小时」。 */
    const val MINUTES_IN_DAY = 1_440

    /**
     * 默认留给睡眠的窗口：23:00–07:00。成年人需要 7 小时以上睡眠，且入睡前一小时仍做用脑的事
     * 会推迟入睡、压低睡眠质量——一个把复习排到凌晨的工具，是在拆它自己承诺的记忆效果。
     *
     * 这条只约束「助手自己挑的落点」。用户开口点名的钟点照样照办（见 [plan] 里 pinned 分支）：
     * 工具可以提醒，不可以替用户改写他说过的话。
     */
    const val SLEEP_START_MINUTE = 23 * 60
    const val SLEEP_END_MINUTE = 7 * 60

    /** 跨午夜的窗口：一天里的时间段只要够到 23:00 之后或 07:00 之前，就算压在睡眠上。 */
    fun overlapsSleepWindow(startMinute: Int, endMinute: Int): Boolean =
        startMinute < SLEEP_END_MINUTE || endMinute > SLEEP_START_MINUTE

    private const val SEARCH_STEP_MINUTES = 5
    private const val MAX_PARALLEL_TRACKS = 4
    private const val DEFAULT_TRACK = "focus"

    data class Placement(
        val index: Int,
        val startMinute: Int,
        val endMinute: Int,
        val trackId: String,
        /** True when the user's own sentence pinned this time, false when we chose it. */
        val fromUser: Boolean,
        /** 非空 = 这件事完整落在某个已有块的时段里，成为它的下属事件（同一时间做多件事）。 */
        val nestedUnderId: String? = null,
        val nestedUnderTitle: String? = null,
    )

    enum class UnplacedReason {
        /** No duration, so there is nothing to place. The card must ask for one. */
        MISSING_DURATION,
        /** The day has no room left. The card must say so instead of guessing. */
        NO_ROOM,
    }

    data class Unplaced(val index: Int, val reason: UnplacedReason)

    data class Result(
        val placements: List<Placement>,
        val unplaced: List<Unplaced>,
    ) {
        fun placementFor(index: Int): Placement? = placements.firstOrNull { it.index == index }
    }

    fun plan(
        candidates: List<ArrangementCandidate>,
        occupied: List<TimelineEntry>,
        dayStartMinute: Int = DEFAULT_DAY_START_MINUTE,
        dayEndMinute: Int = DEFAULT_DAY_END_MINUTE,
        style: SchedulingStyle = SchedulingStyle.PACKED,
    ): Result {
        val gap = style.gapMinutes
        // 没指定时间的项躲开当天每一块已有安排，含普通任务块：PlacementValidator 对普通重叠要用户
        // 再点一次「确认调整」，而批量卡只确认一次——落点还要二次确认等于把省掉的轮次加回来。
        val hardBusy = occupied
        // A task already on the timeline only blocks its own lane; two different lanes are
        // exactly how the app draws simultaneous work. Only a time the user stated may use one.
        val occupiedByTrack = occupied
            .filter { it.kind == TimelineKind.TASK }
            .groupBy(TimelineEntry::trackId)

        val placements = mutableListOf<Placement>()
        val unplaced = mutableListOf<Unplaced>()
        val nestHosts = nestedHosts(occupied)
        var cursor = dayStartMinute

        // Pinned items first: they consume room before anything is allowed to claim it.
        val order = candidates.withIndex().sortedWith(
            compareBy(
                { if (it.value.timeHint.explicitStartMinute != null) 0 else 1 },
                { -(it.value.priority ?: TaskPriority.MEDIUM).score },
                { -(it.value.durationMinutes ?: 0) },
                { it.index },
            ),
        )

        order.forEach { (index, candidate) ->
            val duration = candidate.durationMinutes
            if (duration == null || duration <= 0) {
                unplaced += Unplaced(index, UnplacedReason.MISSING_DURATION)
                return@forEach
            }
            val pinned = candidate.timeHint.explicitStartMinute
            val placement = if (pinned != null) {
                // 用户说出口的钟点不能被搜索区间改写：抬到 08:00 之后卡片仍会写「你点名的时间」，
                // 等于让卡上的字去圆一句用户没说过的话。凌晨的事就排在凌晨。
                placePinned(
                    index = index,
                    start = pinned.coerceIn(0, MINUTES_IN_DAY - duration),
                    duration = duration,
                    preferredTrack = candidate.preferredTrackId,
                    hardBusy = hardBusy,
                    occupiedByTrack = occupiedByTrack,
                    placements = placements,
                    dayEndMinute = MINUTES_IN_DAY,
                    excludeTaskId = candidate.existingTaskId,
                ) ?: freePlacement(index, duration, candidate.preferredTrackId, cursor, hardBusy, occupiedByTrack, placements, nestHosts, occupied, dayEndMinute)
            } else {
                freePlacement(index, duration, candidate.preferredTrackId, cursor, hardBusy, occupiedByTrack, placements, nestHosts, occupied, dayEndMinute)
            }
            if (placement == null) {
                unplaced += Unplaced(index, UnplacedReason.NO_ROOM)
            } else {
                placements += placement
                if (placement.nestedUnderId == null) cursor = maxOf(cursor, placement.endMinute + gap)
            }
        }

        return Result(
            placements = placements.sortedBy(Placement::startMinute),
            unplaced = unplaced.sortedBy(Unplaced::index),
        )
    }

    /**
     * A time the user stated is kept even if a class is there. Moving it silently would make
     * the card disagree with the sentence above it, and dropping it would delete a real
     * intention. The overlap stays visible on the timeline and the conflict role reports it;
     * the user is the one who decides.
     *
     * 锁定与「不可避免」是唯一的例外：写入侧当场拒绝，卡片算它「排入」就是在报一个假成功。
     * 这种点名时间让回找空档，卡上会跟着改口成「助手挑的空档」，用户看到的即真正落地的。
     */
    private fun placePinned(
        index: Int,
        start: Int,
        duration: Int,
        preferredTrack: String?,
        hardBusy: List<TimelineEntry>,
        occupiedByTrack: Map<String, List<TimelineEntry>>,
        placements: List<Placement>,
        dayEndMinute: Int,
        excludeTaskId: String?,
    ): Placement? {
        val end = start + duration
        if (end > dayEndMinute) return null
        val protectedHit = hardBusy.any {
            it.taskId != excludeTaskId && (it.locked || it.unavoidable) && start < it.endMinute && end > it.startMinute
        }
        if (protectedHit) return null
        val track = laneFor(
            start = start,
            end = end,
            preferredTrack = preferredTrack,
            hardBusy = hardBusy,
            occupiedByTrack = occupiedByTrack,
            placements = placements,
            allowHardBusy = true,
        ) ?: return null
        return Placement(index, start, end, track, fromUser = true)
    }

    /** 先找真正的空档；一天排满时不再直接回"放不下"，而是挂进已有块当下属。 */
    private fun freePlacement(
        index: Int,
        duration: Int,
        preferredTrack: String?,
        cursor: Int,
        hardBusy: List<TimelineEntry>,
        occupiedByTrack: Map<String, List<TimelineEntry>>,
        placements: List<Placement>,
        hosts: List<TimelineEntry>,
        occupied: List<TimelineEntry>,
        dayEndMinute: Int,
    ): Placement? = placeAfterCursor(
        index = index,
        cursor = cursor,
        duration = duration,
        preferredTrack = preferredTrack,
        hardBusy = hardBusy,
        occupiedByTrack = occupiedByTrack,
        placements = placements,
        dayEndMinute = dayEndMinute,
    ) ?: placeNested(
        index = index,
        duration = duration,
        preferredTrack = preferredTrack,
        hosts = hosts,
        occupied = occupied,
        placements = placements,
    )

    private fun placeAfterCursor(
        index: Int,
        cursor: Int,
        duration: Int,
        preferredTrack: String?,
        hardBusy: List<TimelineEntry>,
        occupiedByTrack: Map<String, List<TimelineEntry>>,
        placements: List<Placement>,
        dayEndMinute: Int,
    ): Placement? {
        val latestStart = dayEndMinute - duration
        if (latestStart < cursor) return null
        var start = cursor
        while (start <= latestStart) {
            val track = laneFor(start, start + duration, preferredTrack, hardBusy, occupiedByTrack, placements)
            if (track != null) return Placement(index, start, start + duration, track, fromUser = false)
            start += SEARCH_STEP_MINUTES
        }
        return null
    }

    /**
     * 能当父事件的只有根级的普通任务块：课程、固定事项、休息是新事项的硬冲突（写入直接被拒），
     * 锁定与"不可避免"块所在时段也不接受任何重叠。下属只挂一层，所以父级永远取根级块。
     */
    private fun nestedHosts(occupied: List<TimelineEntry>): List<TimelineEntry> =
        rootEntries(occupied)
            .filter { it.kind == TimelineKind.TASK && !it.taskClosed && !it.locked && !it.unavoidable }
            .sortedBy(TimelineEntry::startMinute)

    /**
     * 一天排满时把这件事完整塞进某个已有任务块的框架里当下属。时段必须严格落在父级框架内且
     * 不与父级已有的下属重叠——完全相同的时段是并列事件而非嵌套，写入会再要一次「确认调整」，
     * 批量卡只确认一次的承诺就作废了。
     */
    private fun placeNested(
        index: Int,
        duration: Int,
        preferredTrack: String?,
        hosts: List<TimelineEntry>,
        occupied: List<TimelineEntry>,
        placements: List<Placement>,
    ): Placement? {
        hosts.forEach { host ->
            // 深夜的块不当容器：白天排满时把复习挂进凌晨那个块，等于工具亲手把人往不睡觉上推。
            // 用户点名要挂进去是另一回事——那条路走 chosenTimeRejected，卡上写的是他说的时间。
            if (overlapsSleepWindow(host.startMinute, host.endMinute)) return@forEach
            val siblings = occupied.filter { it.id != host.id && it.startMinute < host.endMinute && it.endMinute > host.startMinute }
            var start = host.startMinute
            while (start + duration <= host.endMinute) {
                val end = start + duration
                val inside = start > host.startMinute || end < host.endMinute
                if (inside &&
                    siblings.none { start < it.endMinute && end > it.startMinute } &&
                    placements.none {
                        it.nestedUnderId == host.id && start < it.endMinute && end > it.startMinute
                    }
                ) {
                    return Placement(
                        index = index,
                        startMinute = start,
                        endMinute = end,
                        trackId = preferredTrack?.takeIf(String::isNotBlank) ?: host.trackId,
                        fromUser = false,
                        nestedUnderId = host.id,
                        nestedUnderTitle = host.title,
                    )
                }
                start += SEARCH_STEP_MINUTES
            }
        }
        return null
    }

    /**
     * 用户在确认卡上逐件挑时间时，先判断这个落点能不能一次确认就写进去。
     *
     * 批量卡只确认一次，所以任何还需要第二次「确认调整」或干脆被写入拒绝的落点都必须当场拦住：
     * 压到课/固定/休息、或压到锁定与不可避免的安排是硬冲突；跟普通任务块只重叠一半、
     * 甚至时段完全相同（那是并列事件，不是下属）都要第二次确认。
     * 唯一合法的重叠是完整落进某块里面当下属。
     */
    fun chosenTimeRejected(
        startMinute: Int,
        endMinute: Int,
        occupied: List<TimelineEntry>,
        siblings: List<Pair<Int, Int>>,
        excludeTaskId: String? = null,
    ): String? {
        if (startMinute < 0 || endMinute > MINUTES_IN_DAY || endMinute <= startMinute) {
            return "时间要在当天之内，且结束要晚于开始。"
        }
        fun hits(entry: TimelineEntry) = !(excludeTaskId != null && entry.taskId == excludeTaskId) &&
            startMinute < entry.endMinute && endMinute > entry.startMinute
        occupied.filter { hits(it) }.forEach { entry ->
            // 与 RoomPlanRepository 的 framesNested 同一条口径：整段包住或被整段包住算"同一时间做事"，
            // 写入不再要第二次确认；只重叠一半才会，那正是批量卡承诺不了的。
            val frames = (entry.startMinute <= startMinute && endMinute <= entry.endMinute) ||
                (startMinute <= entry.startMinute && entry.endMinute <= endMinute)
            when {
                entry.kind != TimelineKind.TASK ->
                    return "「${entry.title}」是课或固定安排，${formatClock(entry.startMinute)}–${formatClock(entry.endMinute)} 不能被占用；挑它前后的空档。"
                entry.locked || entry.unavoidable ->
                    return "「${entry.title}」已锁定或标为不可避免，不能压上去。"
                !frames ->
                    return "跟「${entry.title}」只重叠一部分，写入要再确认一次；整段包住它、被它整段包住（当下属），或完全错开。"
            }
        }
        siblings.firstOrNull { (start, end) -> startMinute < end && endMinute > start }?.let { (start, end) ->
            return "跟这一批里的另一件事（${formatClock(start)}–${formatClock(end)}）撞了；两件不占同一段时间。"
        }
        return null
    }

    /**
     * 挑好的这个时段会挂在谁里面。父级口径与 nestedHosts 完全一致（同一个函数），
     * 卡上那句「挂进「X」」和写入后的时间轴必须是同一件事。
     */
    fun nestedHostTitleAt(
        startMinute: Int,
        endMinute: Int,
        occupied: List<TimelineEntry>,
        excludeTaskId: String? = null,
    ): String? = nestedHosts(occupied)
        .filter { !(excludeTaskId != null && it.taskId == excludeTaskId) }
        .filter { it.startMinute <= startMinute && endMinute <= it.endMinute && (it.startMinute < startMinute || endMinute < it.endMinute) }
        .minByOrNull { it.endMinute - it.startMinute }
        ?.title

    private fun formatClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun laneFor(
        start: Int,
        end: Int,
        preferredTrack: String?,
        hardBusy: List<TimelineEntry>,
        occupiedByTrack: Map<String, List<TimelineEntry>>,
        placements: List<Placement>,
        allowHardBusy: Boolean = false,
    ): String? {
        if (!allowHardBusy && hardBusy.any { start < it.endMinute && end > it.startMinute }) return null
        val lanes = buildList {
            preferredTrack?.takeIf(String::isNotBlank)?.let(::add)
            add(DEFAULT_TRACK)
            (2..MAX_PARALLEL_TRACKS).forEach { add("parallel-$it") }
        }.distinct()
        return lanes.firstOrNull { lane ->
            occupiedByTrack[lane].orEmpty().none { start < it.endMinute && end > it.startMinute } &&
                placements.none { it.trackId == lane && start < it.endMinute && end > it.startMinute }
        }
    }
}
