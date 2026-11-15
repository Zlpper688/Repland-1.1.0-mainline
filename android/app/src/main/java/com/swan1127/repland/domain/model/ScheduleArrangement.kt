package com.swan1127.repland.domain.model

/**
 * 插入与自动后延：把一个安排放进今天的某个时刻时，落点之后的主事件依次往后挪，
 * 腾出连续的时间；下属事件跟着自己的父事件走，锁定/专注中/不可避免的安排不会被挪动。
 * 时间排满了（后延越过硬性安排或越过 24:00）就插不进去。
 * 互相重叠的主事件是一个合轨大块：后延按大块整体进行，块内相对时间保持不变。
 */
object ScheduleArrangement {

    data class Rearrangement(
        val segments: List<PlannedSegment>,
        val valid: Boolean,
        val shiftedIds: Set<String>,
    )

    /** 拖拽预览：不落库地回答"放到这里，谁要后延多少"。valid=false 表示这一天已经放不下。 */
    data class ShiftPreview(
        val deltas: Map<String, Int>,
        val valid: Boolean,
    )

    private data class BlockRef(val id: String, val start: Int, val end: Int, val movable: Boolean)

    /**
     * 后延链的核心：主事件按开始时间排序，互相重叠的先聚成合轨大块（下一块的头 <
     * 目前块的尾即入块），大块不可拆分——要么整体让路，要么挡住插入。
     * candidate 一律先落位，骑跨落点的块整体退到它之后。
     * 返回 (是否可行, 每个主事件的位移)。
     */
    private fun runChain(candidate: BlockRef, pool: List<BlockRef>): Pair<Boolean, Map<String, Int>> {
        val sorted = pool.sortedWith(compareBy({ it.start }, { it.end }, { it.id }))
        val units = mutableListOf<MutableList<BlockRef>>()
        for (block in sorted) {
            val last = units.lastOrNull()
            if (last != null && block.start < last.maxOf { it.end }) last += block else units += mutableListOf(block)
        }
        val deltas = mutableMapOf<String, Int>()
        var frontier = 0
        var placedCandidate = false
        for (unit in units) {
            if (!placedCandidate && unit.first().start >= candidate.start) {
                frontier = maxOf(frontier, candidate.end)
                placedCandidate = true
            }
            val unitStart = unit.minOf { it.start }
            val unitEnd = unit.maxOf { it.end }
            val boundary = if (unitStart < candidate.start && unitEnd > candidate.start) candidate.end else unitStart
            val delta = (maxOf(frontier, boundary) - unitStart).coerceAtLeast(0)
            if (delta > 0 && unit.any { !it.movable }) return false to emptyMap()
            if (unitEnd + delta > 1_440) return false to emptyMap()
            unit.forEach { deltas[it.id] = delta }
            frontier = maxOf(frontier, unitEnd + delta)
        }
        return true to deltas
    }

    /**
     * 把 [candidate] 放到 [segments] 所在的那天里；[movedId] 非空时表示"挪动已有安排"——
     * 它先从原位置移除，其下属事件按同样的偏移跟着走。
     * 返回的列表包含当天与其它天的全部安排；[Rearrangement.valid] 为 false 表示时间已排满，不能插入。
     */
    fun insertAndDelay(
        candidate: PlannedSegment,
        segments: List<PlannedSegment>,
        movedId: String? = null,
        movable: (PlannedSegment) -> Boolean = { !it.isLocked },
    ): Rearrangement {
        val sameDate = segments.filter { it.date == candidate.date }
        val moved = movedId?.let { id -> sameDate.firstOrNull { it.id == id } }
        if (moved != null && candidate.endMinute - candidate.startMinute != moved.endMinute - moved.startMinute) {
            return Rearrangement(segments, valid = false, shiftedIds = emptySet())
        }
        // A moved event carries its sub-events along, keeping their offsets inside its frame.
        val movedChildren = moved?.let { parent ->
            sameDate.filter { it.id != parent.id && parent.startMinute <= it.startMinute && it.endMinute <= parent.endMinute }
        }.orEmpty()
        val carried = (listOfNotNull(moved) + movedChildren).map { it.id }.toSet()

        val pool = sameDate.filter { it.id !in carried && it.id != movedId }
        val containerOf = pool.associate { segment ->
            segment.id to pool.filter { other ->
                other.id != segment.id && other.startMinute <= segment.startMinute && segment.endMinute <= other.endMinute &&
                    (other.startMinute < segment.startMinute || segment.endMinute < other.endMinute || other.id > segment.id)
            }.minByOrNull { it.endMinute - it.startMinute }?.id
        }
        fun rootAncestor(id: String): String {
            var current = id
            while (true) {
                val parent = containerOf[current] ?: return current
                if (parent == current) return current
                current = parent
            }
        }

        val refs = pool.map { BlockRef(it.id, it.startMinute, it.endMinute, movable(it)) }
        val (valid, deltas) = runChain(BlockRef(candidate.id, candidate.startMinute, candidate.endMinute, true), refs)
        if (!valid) return Rearrangement(segments, valid = false, shiftedIds = emptySet())

        val result = mutableListOf<PlannedSegment>()
        result += segments.filter { it.date != candidate.date }
        result += candidate
        val shiftedIds = pool.filter { containerOf[it.id] == null && (deltas[it.id] ?: 0) > 0 }.map { it.id }.toSet()
        result += pool.filter { containerOf[it.id] == null }.map { segment ->
            val delta = deltas[segment.id] ?: 0
            segment.copy(startMinute = segment.startMinute + delta, endMinute = segment.endMinute + delta)
        }
        // Sub-events always share their root's fate; the root is assigned first, so every
        // descendant resolves to a known delta regardless of how their starts sort.
        result += pool.filter { containerOf[it.id] != null }.map { segment ->
            val delta = deltas[rootAncestor(segment.id)] ?: 0
            segment.copy(startMinute = segment.startMinute + delta, endMinute = segment.endMinute + delta)
        }
        result += movedChildren.map { child ->
            val delta = candidate.startMinute - requireNotNull(moved).startMinute
            child.copy(startMinute = child.startMinute + delta, endMinute = child.endMinute + delta)
        }
        return Rearrangement(result, valid = true, shiftedIds = shiftedIds)
    }

    /** 拖拽时的预览版 [insertAndDelay]：同一条后延链，永不报错、忽略锁定，只回答谁要挪多少。 */
    fun previewShifts(
        candidate: PlannedSegment,
        segments: List<PlannedSegment>,
        movedId: String? = null,
    ): ShiftPreview {
        val sameDate = segments.filter { it.date == candidate.date }
        val moved = movedId?.let { id -> sameDate.firstOrNull { it.id == id } }
        val movedChildren = moved?.let { parent ->
            sameDate.filter { it.id != parent.id && parent.startMinute <= it.startMinute && it.endMinute <= parent.endMinute }
        }.orEmpty()
        val carried = (listOfNotNull(moved) + movedChildren).map { it.id }.toSet()
        val pool = sameDate.filter { it.id !in carried && it.id != movedId }
        val refs = pool.map { BlockRef(it.id, it.startMinute, it.endMinute, movable = true) }
        val (valid, deltas) = runChain(BlockRef(candidate.id, candidate.startMinute, candidate.endMinute, true), refs)
        return ShiftPreview(deltas, valid)
    }

    /** 完整落在 another 时段之内（同一时间做两件事）。 */
    fun isNestedIn(candidate: PlannedSegment, other: PlannedSegment): Boolean =
        candidate.id != other.id && other.date == candidate.date &&
            other.startMinute <= candidate.startMinute && candidate.endMinute <= other.endMinute
}
