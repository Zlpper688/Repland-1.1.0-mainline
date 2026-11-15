package com.swan1127.repland.domain.model

import com.swan1127.repland.data.ContentPortraitCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPortraitTest {

    private val material = ContentPortraitComposer.numbered(
        listOf(
            "任务「高数复习」 · 课程 · 进行中 · 预计 60 分钟",
            "任务「蓝桥杯刷题」 · 课外 · 进行中 · 预计 90 分钟",
            "日记 2026-11-10「今日简报」：上午被课程占满，晚上空出两小时",
        ),
    )

    @Test fun parseKeepsOnlyPointsWithRealEvidence() {
        val raw = """
            - 同时推进课程复习和竞赛刷题，晚上更容易腾出整块时间（依据：1,3）
            - 你是一个自律的人（依据：99）
            - 有恒心，能坚持
            - 刷题安排在晚上，接近两小时（材料 2、3）
        """.trimIndent()
        val points = ContentPortraitComposer.parse(raw, material) ?: error("应至少保留一条")
        assertEquals(2, points.size)
        assertEquals(listOf("1", "3"), points[0].evidenceIds)
        assertEquals(listOf("2", "3"), points[1].evidenceIds)
        assertTrue(points.none { it.text.contains("自律") })
    }

    @Test fun parseRejectsEverythingWhenNoEvidenceSurvives() {
        val raw = "- 你很努力\n- 你重视学习"
        assertNull(ContentPortraitComposer.parse(raw, material))
    }

    @Test fun evidenceOnlyContainsLinesThePointsCite() {
        val points = listOf(PortraitPoint("晚上推进竞赛任务", listOf("2")))
        val evidence = ContentPortraitComposer.evidenceFor(points, material)
        assertEquals(listOf("2"), evidence.map { it.id })
        assertTrue(evidence.single().text.contains("蓝桥杯"))
    }

    @Test fun briefingPutsUserCorrectionsFirst() {
        val portrait = ContentPortrait(
            materialDays = 30,
            points = listOf(PortraitPoint("晚上推进竞赛任务", listOf("2"))),
            evidence = listOf(PortraitEvidence("2", "任务「蓝桥杯刷题」")),
            corrections = listOf("周末要留给家里，不排任务"),
        )
        val briefing = portrait.briefing()
        assertTrue(briefing.indexOf("周末要留给家里") < briefing.indexOf("晚上推进竞赛任务"))
        assertTrue(briefing.contains("材料 2"))
    }

    @Test fun portraitSurvivesRoundTripIncludingConsent() {
        val portrait = ContentPortrait(
            version = 3,
            updatedAtMillis = 1_700_000_000_000,
            consentAtMillis = 1_700_000_100_000,
            points = listOf(PortraitPoint("晚上推进竞赛任务", listOf("2"))),
            evidence = listOf(PortraitEvidence("2", "任务「蓝桥杯刷题」")),
            corrections = listOf("周末不排任务"),
        )
        val restored = ContentPortraitCodec.fromJson(ContentPortraitCodec.toJson(portrait))
        assertEquals(portrait, restored)
    }

    @Test fun revokeSignalIsExplicit() {
        assertTrue(ContentPortrait(consentAtMillis = 1).hasContentConsent)
        assertTrue(!ContentPortrait(consentAtMillis = null).hasContentConsent)
    }
}
