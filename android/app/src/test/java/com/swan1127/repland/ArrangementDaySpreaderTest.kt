package com.swan1127.repland

import com.swan1127.repland.domain.model.ArrangementDaySpreader
import com.swan1127.repland.domain.model.ArrangementDaySpreader.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangementDaySpreaderTest {

    @Test
    fun contiguous_blocks_get_the_requested_gap_and_keep_the_first_anchor() {
        val moves = ArrangementDaySpreader.moves(
            listOf(
                Block("a", 15 * 60, 16 * 60),
                Block("b", 16 * 60, 17 * 60),
            ),
            30,
        )

        assertEquals(listOf("b"), moves.map { it.id })
        assertEquals(16 * 60 + 30, moves.single().startMinute)
        assertEquals(17 * 60 + 30, moves.single().endMinute)
    }

    @Test
    fun an_already_loose_day_is_left_alone() {
        val moves = ArrangementDaySpreader.moves(
            listOf(
                Block("a", 9 * 60, 10 * 60),
                Block("b", 11 * 60, 12 * 60),
            ),
            30,
        )

        assertTrue(moves.isEmpty())
    }

    @Test
    fun fixed_blocks_are_never_moved_and_push_the_movable_ones_past_them() {
        val moves = ArrangementDaySpreader.moves(
            listOf(
                Block("a", 9 * 60, 10 * 60),
                Block("class", 10 * 60, 11 * 60, fixed = true),
                Block("b", 11 * 60, 12 * 60),
            ),
            30,
        )

        assertEquals(listOf("b"), moves.map { it.id })
        assertEquals(11 * 60 + 30, moves.single().startMinute)
    }

    @Test
    fun a_single_block_has_nothing_to_spread() {
        assertTrue(
            ArrangementDaySpreader.moves(listOf(Block("a", 15 * 60, 16 * 60))).isEmpty(),
        )
    }

    @Test
    fun blocks_never_move_earlier_and_never_disappear() {
        val moves = ArrangementDaySpreader.moves(
            listOf(
                Block("a", 8 * 60, 9 * 60),
                Block("b", 9 * 60, 10 * 60),
            ),
            60,
        )

        assertEquals(listOf("b"), moves.map { it.id })
        assertEquals(10 * 60, moves.single().startMinute)
    }
}
