package com.swan1127.repland.domain.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionImportTest {

    private val sample = """
        比赛名称：中国国际大学生创新大赛（2026）校赛
        主办方：教务处 · 创新创业学院
        报名截止：2026-11-15
        人数上限：5
        组队要求：3–5 人，需指定 1 名队长
        报名方式：登录校赛平台提交报名表
        校赛初评：2026-12-10
        提交截止：2027-01-03
        提交物：项目书、路演 PPT、1 分钟演示视频
        评审标准：创新性 40%、可行性 30%、路演与答辩 30%
        答辩形式：8 分钟路演 + 5 分钟问答
        其他：校赛前 3 名推荐参加市赛
    """.trimIndent()

    @Test
    fun `maps labels into profile groups`() {
        val result = CompetitionImport.parse(sample)

        assertEquals("中国国际大学生创新大赛（2026）校赛", result.competitionName)
        assertEquals(12, result.recognized)
        assertEquals("2026-11-15", result.fields[ProfileSlotGroup.BASIC_INFO]?.first { it.key == "signup_deadline" }?.value)
        assertEquals("5", result.fields[ProfileSlotGroup.BASIC_INFO]?.first { it.key == "member_limit" }?.value)
        assertEquals("2026-12-10", result.fields[ProfileSlotGroup.TIMELINE]?.first { it.key == "preliminary" }?.value)
        assertEquals("2027-01-03", result.fields[ProfileSlotGroup.TIMELINE]?.first { it.key == "submission_deadline" }?.value)
        assertTrue(result.fields[ProfileSlotGroup.QUOTA].orEmpty().any { it.key == "team_composition" })
        assertTrue(result.fields[ProfileSlotGroup.DELIVERABLES].orEmpty().any { it.key == "deliverables" })
        assertTrue(result.fields[ProfileSlotGroup.REVIEW].orEmpty().any { it.key == "criteria" })
        assertTrue(result.extras.any { it.key == "其他" && it.value.contains("市赛") })
    }

    @Test
    fun `builds a card without missing critical slots`() {
        val result = CompetitionImport.parse(sample)
        var card = CompetitionProfileCard(result.competitionName ?: "未命名比赛", fields = emptyMap())
        result.fields.forEach { (group, fields) ->
            fields.forEach { field -> card = card.withField(group, field.key, field.label, field.value.orEmpty()) }
        }
        result.extras.forEach { card = card.withExtra(it) }

        assertTrue(card.missingCritical().isEmpty())
        assertEquals("教务处 · 创新创业学院", card.field("organizer"))
    }

    @Test
    fun `keeps unknown fields in extras instead of dropping them`() {
        val result = CompetitionImport.parse("赛题编号：A03\n比赛名称：数学建模校赛")

        assertEquals("数学建模校赛", result.competitionName)
        assertEquals(listOf("赛题编号"), result.extras.map { it.key })
        assertEquals(2, result.recognized)
    }

    @Test
    fun `ignores lines without a value`() {
        val result = CompetitionImport.parse("报名截止：\n随便一行\n# 注释")

        assertEquals(0, result.recognized)
        assertNull(result.competitionName)
    }
}
