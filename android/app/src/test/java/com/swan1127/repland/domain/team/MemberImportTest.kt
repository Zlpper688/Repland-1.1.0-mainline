package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberImportTest {

    @Test
    fun `parses pipe rows with role responsibility and availability`() {
        val text = """
            # 姓名 | 角色 | 负责说明 | 空闲时段
            林晓 | 产品 | 负责整体推进与答辩 | 周一19:00-22:00；周三 20:00-21:30
            陈一 | 技术 | 负责原型开发 | 周二19:00-22:30、周日14:00-18:00
        """.trimIndent()

        val result = MemberImport.parse(text)

        assertEquals(2, result.members.size)
        val first = result.members[0]
        assertEquals("林晓", first.name)
        assertEquals("产品", first.roleTag)
        assertEquals("负责整体推进与答辩", first.responsibility)
        assertEquals(listOf(FreeWindow(19 * 60, 22 * 60)), first.availability[DayOfWeek.MONDAY])
        assertEquals(listOf(FreeWindow(20 * 60, 21 * 60 + 30)), first.availability[DayOfWeek.WEDNESDAY])
        val second = result.members[1]
        assertEquals("负责原型开发", second.responsibility)
        assertEquals(listOf(FreeWindow(19 * 60, 22 * 60 + 30)), second.availability[DayOfWeek.TUESDAY])
        assertEquals(listOf(FreeWindow(14 * 60, 18 * 60)), second.availability[DayOfWeek.SUNDAY])
    }

    @Test
    fun `skips header duplicates blanks and malformed rows`() {
        val text = """
            姓名 | 角色 | 负责说明 | 空闲时段

            阿岚 | 设计
            阿岚 | 文案
             | 空名
            # 注释
            老白 | 数据 | 负责调研 | 周一25:00-26:00
        """.trimIndent()

        val result = MemberImport.parse(text)

        assertEquals(listOf("阿岚", "老白"), result.members.map { it.name })
        assertEquals(3, result.skippedCount)
        assertTrue(result.members[1].availability.isEmpty())
    }

    @Test
    fun `parses whitespace rows without pipes`() {
        val result = MemberImport.parse("小满 设计 负责路演PPT与视觉设计")

        assertEquals(1, result.members.size)
        assertEquals("小满", result.members[0].name)
        assertEquals("设计", result.members[0].roleTag)
        assertEquals("负责路演PPT与视觉设计", result.members[0].responsibility)
    }

    @Test
    fun `ignores invalid time windows`() {
        assertTrue(MemberImport.parseAvailability("周一22:00-19:00").isEmpty())
        assertTrue(MemberImport.parseAvailability("周八19:00-22:00").isEmpty())
        assertTrue(MemberImport.parseAvailability("无").isEmpty())
        assertEquals(2, MemberImport.parseAvailability("周五19:00-21:00").getValue(DayOfWeek.FRIDAY).size +
            MemberImport.parseAvailability("星期一20:00-22:00").getValue(DayOfWeek.MONDAY).size)
    }
}
