package com.swan1127.repland.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleArrangementTest {

    private val day = LocalDate.of(2026, 10, 23)

    private fun segment(id: String, start: Int, end: Int, locked: Boolean = false) =
        PlannedSegment(id = id, taskId = "task-$id", date = day, startMinute = start, endMinute = end, isLocked = locked)

    private fun find(result: ScheduleArrangement.Rearrangement, id: String) = result.segments.first { it.id == id }

    @Test
    fun `插入空档不移动任何安排`() {
        val existing = listOf(segment("a", 8 * 60, 9 * 60), segment("b", 10 * 60, 11 * 60))
        val result = ScheduleArrangement.insertAndDelay(segment("new", 9 * 60, 10 * 60), existing)
        assertTrue(result.valid)
        assertTrue(result.shiftedIds.isEmpty())
        assertEquals(3, result.segments.size)
    }

    @Test
    fun `落点之后的安排自动后延`() {
        val existing = listOf(segment("a", 8 * 60, 9 * 60), segment("b", 10 * 60, 11 * 60))
        // 10:30 插入 60 分钟：b 骑跨落点，整体退到 11:30。
        val result = ScheduleArrangement.insertAndDelay(segment("new", 10 * 60 + 30, 11 * 60 + 30), existing)
        assertTrue(result.valid)
        assertEquals(setOf("b"), result.shiftedIds)
        assertEquals(11 * 60 + 30, find(result, "b").startMinute)
        assertEquals(12 * 60 + 30, find(result, "b").endMinute)
    }

    @Test
    fun `连续多个安排依次顺延`() {
        val existing = listOf(segment("a", 9 * 60, 10 * 60), segment("b", 10 * 60, 11 * 60), segment("c", 11 * 60, 12 * 60))
        // 9:00 插入 60 分钟，b、c 依次后移。
        val result = ScheduleArrangement.insertAndDelay(segment("new", 9 * 60, 10 * 60), existing)
        assertTrue(result.valid)
        assertEquals(11 * 60, find(result, "b").startMinute)
        assertEquals(12 * 60, find(result, "c").startMinute)
    }

    @Test
    fun `后延越过午夜则插不进去`() {
        val existing = listOf(segment("late", 23 * 60 + 30, 24 * 60))
        val result = ScheduleArrangement.insertAndDelay(segment("new", 23 * 60, 24 * 60), existing)
        assertFalse(result.valid)
    }

    @Test
    fun `锁定或不可避免的安排挡住插入`() {
        val required = PlannedSegment(id = "fixed", taskId = "required-task", date = day, startMinute = 10 * 60, endMinute = 11 * 60)
        listOf(
            segment("locked", 10 * 60, 11 * 60, locked = true) to { s: PlannedSegment -> !s.isLocked },
            required to { s: PlannedSegment -> s.taskId != "required-task" },
        ).forEach { (blocker, movable) ->
            val result = ScheduleArrangement.insertAndDelay(
                segment("new", 9 * 60 + 30, 10 * 60 + 30), listOf(blocker), movable = movable)
            assertFalse(result.valid)
        }
    }

    @Test
    fun `挪动事件时其下属跟着走`() {
        val parent = segment("parent", 14 * 60, 16 * 60)
        val child = segment("child", 14 * 60 + 30, 15 * 60)
        val later = segment("later", 16 * 60, 17 * 60)
        // parent 从 14:00 挪到 18:00，child 跟着 +4h，later 原地不动。
        val result = ScheduleArrangement.insertAndDelay(
            parent.copy(startMinute = 18 * 60, endMinute = 20 * 60), listOf(parent, child, later), movedId = "parent")
        assertTrue(result.valid)
        assertEquals(18 * 60, find(result, "parent").startMinute)
        assertEquals(18 * 60 + 30, find(result, "child").startMinute)
        assertEquals(16 * 60, find(result, "later").startMinute)
    }

    @Test
    fun `挪动时长改变则无效`() {
        val parent = segment("parent", 14 * 60, 16 * 60)
        // parent 从 14:00 挪到 15:00 但时长变了（120→60 分钟）：无效。
        val result = ScheduleArrangement.insertAndDelay(
            parent.copy(startMinute = 15 * 60, endMinute = 16 * 60), listOf(parent), movedId = "parent")
        assertFalse(result.valid)
    }

    @Test
    fun `新插入的父事件后延时其下属同步后延`() {
        val parent = segment("parent", 10 * 60, 12 * 60)
        val child = segment("child", 10 * 60 + 30, 11 * 60)
        // 9:30 插入 60 分钟，parent 后移 30 分，child 跟着后移。
        val result = ScheduleArrangement.insertAndDelay(segment("new", 9 * 60 + 30, 10 * 60 + 30), listOf(parent, child))
        assertTrue(result.valid)
        assertEquals(10 * 60 + 30, find(result, "parent").startMinute)
        assertEquals(11 * 60, find(result, "child").startMinute)
    }

    @Test
    fun `后延不会把下属挤出父事件框架`() {
        val parent = segment("parent", 10 * 60, 12 * 60)
        val child = segment("child", 11 * 60 + 30, 12 * 60)
        val follower = segment("follower", 12 * 60, 13 * 60)
        // 9:30 插入 60 分钟：parent 后移 30 分，child 跟随且仍完整落在父事件里，follower 顺延。
        val result = ScheduleArrangement.insertAndDelay(segment("new", 9 * 60 + 30, 10 * 60 + 30), listOf(parent, child, follower))
        assertTrue(result.valid)
        assertEquals(10 * 60 + 30, find(result, "parent").startMinute)
        assertEquals(12 * 60 + 30, find(result, "child").endMinute)
        assertEquals(12 * 60 + 30, find(result, "follower").startMinute)
        assertTrue(find(result, "child").endMinute <= find(result, "parent").endMinute)
    }

    @Test
    fun `嵌套判定识别完整包含`() {
        val parent = segment("parent", 10 * 60, 12 * 60)
        val inside = segment("inside", 10 * 60 + 30, 11 * 60)
        val partial = segment("partial", 11 * 60, 12 * 60 + 30)
        assertTrue(ScheduleArrangement.isNestedIn(inside, parent))
        assertFalse(ScheduleArrangement.isNestedIn(partial, parent))
        assertFalse(ScheduleArrangement.isNestedIn(parent, parent))
    }

    @Test
    fun `重叠的主事件合成大块整体后延`() {
        // a 与 b 重叠，是同一个合轨大块；插入骑跨组头时整块让路，块内相对间隔保持 30 分钟。
        val a = segment("a", 9 * 60, 10 * 60)
        val b = segment("b", 9 * 60 + 30, 10 * 60 + 30)
        val c = segment("c", 11 * 60, 12 * 60)
        val result = ScheduleArrangement.insertAndDelay(segment("new", 8 * 60 + 30, 9 * 60 + 15), listOf(a, b, c))
        assertTrue(result.valid)
        assertEquals(9 * 60 + 15, find(result, "a").startMinute)
        assertEquals(9 * 60 + 45, find(result, "b").startMinute)
        assertEquals(11 * 60, find(result, "c").startMinute)
    }

    @Test
    fun `骑跨落点的大块整体退到插入之后`() {
        val a = segment("a", 9 * 60, 10 * 60)
        val b = segment("b", 9 * 60 + 30, 10 * 60 + 30)
        val c = segment("c", 11 * 60, 12 * 60)
        val result = ScheduleArrangement.insertAndDelay(segment("new", 9 * 60 + 45, 10 * 60 + 45), listOf(a, b, c))
        assertTrue(result.valid)
        assertEquals(10 * 60 + 45, find(result, "a").startMinute)
        assertEquals(11 * 60 + 15, find(result, "b").startMinute)
        assertEquals(12 * 60 + 15, find(result, "c").startMinute)
    }

    @Test
    fun `大块中任一成员不可动则整块挡住插入`() {
        val a = segment("a", 9 * 60, 10 * 60)
        val locked = segment("locked", 9 * 60 + 30, 10 * 60 + 30, locked = true)
        val result = ScheduleArrangement.insertAndDelay(
            segment("new", 9 * 60 + 15, 10 * 60 + 15), listOf(a, locked), movable = { !it.isLocked })
        assertFalse(result.valid)
    }

    @Test
    fun `从大块里挪走一个成员其余原地不动`() {
        val a = segment("a", 9 * 60, 10 * 60)
        val b = segment("b", 9 * 60 + 30, 10 * 60 + 30)
        val c = segment("c", 11 * 60, 12 * 60)
        val result = ScheduleArrangement.insertAndDelay(
            b.copy(startMinute = 14 * 60, endMinute = 15 * 60), listOf(a, b, c), movedId = "b")
        assertTrue(result.valid)
        assertEquals(9 * 60, find(result, "a").startMinute)
        assertEquals(11 * 60, find(result, "c").startMinute)
        assertEquals(14 * 60, find(result, "b").startMinute)
    }

    @Test
    fun `拖拽预览与实际后延一致`() {
        val existing = listOf(segment("a", 9 * 60, 10 * 60), segment("b", 10 * 60, 11 * 60))
        val candidate = segment("new", 9 * 60, 10 * 60)
        val preview = ScheduleArrangement.previewShifts(candidate, existing)
        val actual = ScheduleArrangement.insertAndDelay(candidate, existing)
        assertTrue(preview.valid)
        assertEquals(actual.shiftedIds, preview.deltas.filterValues { it > 0 }.keys)
        assertEquals(10 * 60, 9 * 60 + (preview.deltas["a"] ?: 0))
        assertEquals(11 * 60, 10 * 60 + (preview.deltas["b"] ?: 0))
    }
}
