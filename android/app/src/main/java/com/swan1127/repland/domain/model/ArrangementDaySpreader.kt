package com.swan1127.repland.domain.model

/**
 * "排开点" — widen the gap between the blocks already on the day.
 *
 * It only pushes blocks later, starting from the earliest one, so asking for room never
 * silently pulls work into a different part of the day. Fixed, locked and REQUIRED
 * blocks stay put, and a block that no longer fits keeps its current slot instead of
 * being dropped — a style that loses items would be worse than a tight day.
 */
object ArrangementDaySpreader {

    const val DEFAULT_GAP_MINUTES = 30
    const val DAY_START_MINUTE = 8 * 60
    const val DAY_END_MINUTE = 22 * 60

    data class Block(
        val id: String,
        val startMinute: Int,
        val endMinute: Int,
        /** Courses, commitments, locked or unavoidable work and closed tasks never move. */
        val fixed: Boolean = false,
    )

    data class Move(val id: String, val startMinute: Int, val endMinute: Int)

    fun moves(
        blocks: List<Block>,
        gapMinutes: Int = DEFAULT_GAP_MINUTES,
        dayStartMinute: Int = DAY_START_MINUTE,
        dayEndMinute: Int = DAY_END_MINUTE,
    ): List<Move> {
        val movable = blocks
            .filter { !it.fixed && it.endMinute > it.startMinute }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
        if (movable.size < 2) return emptyList()
        val fixed = blocks.filter { it.fixed || it.endMinute <= it.startMinute }
        return layout(movable, fixed, gapMinutes, dayStartMinute, dayEndMinute)
            .filter { it.startMinute != it.sourceMinute }
            .map { Move(it.block.id, it.startMinute, it.endMinute) }
    }

    private data class Slot(val block: Block, val sourceMinute: Int, val startMinute: Int, val endMinute: Int)

    private fun layout(
        movable: List<Block>,
        fixed: List<Block>,
        gapMinutes: Int,
        dayStartMinute: Int,
        dayEndMinute: Int,
    ): List<Slot> {
        val placed = mutableListOf<Slot>()
        var previousEnd: Int? = null

        // Fixed blocks take room too: work right after a class keeps its gap as well.
        val chain = (fixed.map { Chain(it, true) } + movable.map { Chain(it, false) })
            .sortedWith(compareBy({ it.block.startMinute }, { it.block.endMinute }))

        for (item in chain) {
            val block = item.block
            if (item.fixed) {
                previousEnd = maxOf(previousEnd ?: block.endMinute, block.endMinute)
                continue
            }
            val duration = block.endMinute - block.startMinute
            var start: Int? = null
            for (gap in intArrayOf(gapMinutes, maxOf(15, gapMinutes / 2), 5)) {
                val from = if (previousEnd == null) {
                    maxOf(dayStartMinute, block.startMinute)
                } else {
                    maxOf(block.startMinute, previousEnd!! + gap)
                }
                start = findSlot(from, duration, fixed, placed, dayStartMinute, dayEndMinute)
                if (start != null) break
            }
            val resolved = start ?: block.startMinute
            placed += Slot(block, block.startMinute, resolved, resolved + duration)
            previousEnd = resolved + duration
        }
        return placed
    }

    private data class Chain(val block: Block, val fixed: Boolean)

    private fun findSlot(
        from: Int,
        duration: Int,
        fixed: List<Block>,
        placed: List<Slot>,
        dayStartMinute: Int,
        dayEndMinute: Int,
    ): Int? {
        var candidate = from
        while (candidate + duration <= dayEndMinute) {
            val clear = fixed.none { candidate < it.endMinute && candidate + duration > it.startMinute } &&
                placed.none { candidate < it.endMinute && candidate + duration > it.startMinute }
            if (clear) return candidate
            candidate += 5
        }
        return null
    }
}
