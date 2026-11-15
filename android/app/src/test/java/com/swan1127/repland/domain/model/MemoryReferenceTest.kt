package com.swan1127.repland.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReferenceTest {

    private val day = LocalDate.of(2026, 3, 2)

    private fun state(
        stabilityDays: Double,
        importance: Int = 3,
        lastEventOn: LocalDate? = day,
        reviews: Int = 4,
    ) = MemoryState(
        capturedOn = day,
        lastEventOn = lastEventOn,
        stabilityDays = stabilityDays,
        difficulty = 5.5,
        difficultySeed = 5.5,
        importance = importance,
        reviews = reviews,
        lapses = 0,
        exposures = 1,
    )

    private fun entry(id: String, stabilityDays: Double, importance: Int = 3) =
        MemoryEntry(id, " remembered: $id ", state(stabilityDays, importance))

    @Test
    fun theWeakestMemoryIsListedFirst() {
        val reference = MemoryReference.of(
            listOf(entry("solid", 30.0), entry("fading", 1.0), entry("middle", 6.0)),
            day.plusDays(4),
        )
        assertEquals(listOf("fading", "middle", "solid"), reference.signals.map(MemorySignal::taskId))
    }

    @Test
    fun everyNumberComesFromTheModelForTheDayAskedAbout() {
        val entries = listOf(entry("a", 7.0), entry("b", 2.0))
        val today = day.plusDays(9)
        val reference = MemoryReference.of(entries, today)

        entries.forEach { entry ->
            val signal = reference.forTask(entry.taskId)!!
            assertEquals(MemoryModel.retrievabilityAt(entry.state, today), signal.retrievability, 1e-9)
            assertEquals(MemoryModel.reviewPressure(entry.state, today), signal.pressure, 1e-9)
            assertEquals(entry.state.stabilityDays, signal.stabilityDays, 1e-9)
        }
        assertEquals(today, reference.asOf)
    }

    @Test
    fun theDueDayBelongsToTheMemoryNotToTheDayWeAskedAbout() {
        val entries = listOf(entry("a", 7.0))

        val early = MemoryReference.of(entries, day)
        val late = MemoryReference.of(entries, day.plusDays(20))

        assertEquals(early.signals.single().dueOn, late.signals.single().dueOn)
        assertTrue(late.signals.single().retrievability < early.signals.single().retrievability)
    }

    @Test
    fun dueOnlyCountsWhatHasActuallyArrived() {
        val solid = entry("solid", 30.0)
        val fading = entry("fading", 1.0)
        val reference = MemoryReference.of(listOf(solid, fading), day.plusDays(2))

        assertEquals(day.plusDays(1), reference.signals.first().dueOn)
        assertEquals(day.plusDays(30), reference.signals.last().dueOn)
        assertEquals(1, reference.dueCount)
        assertFalse(reference.isEmpty)
    }

    @Test
    fun atRiskIsItsOwnBandNotTheDueDay() {
        val signal = { retention: Double ->
            MemorySignal("t", "content", 5.0, retention, day.plusDays(3), 0.5, observations = 4)
        }
        val reference = MemoryReference(
            asOf = day,
            signals = listOf(signal(MemoryReference.AtRiskRetention), signal(0.61)),
        )

        assertEquals(1, reference.atRisk.size)
        assertTrue(reference.atRisk.single().isAtRisk)
        assertNotEquals(reference.atRisk.size, reference.signals.size)
    }

    @Test
    fun aTaskWithSeveralPointsReportsTheOneMostLikelyToBeLost() {
        val reference = MemoryReference.of(
            listOf(
                MemoryEntry("shared", "the strong one", state(45.0)),
                MemoryEntry("shared", "the fragile one", state(1.0)),
                MemoryEntry("other", "unrelated", state(9.0)),
            ),
            day.plusDays(6),
        )

        assertEquals("the fragile one", reference.forTask("shared")?.content?.trim())
        assertEquals("unrelated", reference.forTask("other")?.content?.trim())
        assertNull(reference.forTask("never-remembered"))
    }

    @Test
    fun theLogLineCanOnlyReportWhatIsInTheReference() {
        val fading = entry("fading", 1.0)
        val solid = entry("solid", 30.0)
        val fresh = MemoryReference.of(listOf(fading, solid), day.plusDays(4))
        // 1 天稳定度过了四天仍没跌到「想不起」的下限，就不能说它有危险。
        assertTrue(fresh.atRisk.isEmpty())
        assertEquals("记忆画像：2 条记录，目前都还记得", fresh.logNote())

        val later = MemoryReference.of(listOf(fading, solid), day.plusDays(8))
        val note = later.logNote()
        assertEquals(1, later.atRisk.size)
        assertTrue(note, note.contains("${later.signals.size} 条记录"))
        assertTrue(note, note.contains("${later.atRisk.size} 条最近容易想不起"))
        assertFalse("画像不该报假精度百分比: $note", note.contains("%"))

        val empty = MemoryReference(day, emptyList())
        assertEquals("记忆画像：还没有可依赖的记录", empty.logNote())
        assertTrue(empty.isEmpty)
        assertNotEquals(note, empty.logNote())
    }

    @Test
    fun aMemoryObservedOnceIsDescribedAsUnmeasuredNotAsFact() {
        val thin = MemoryEntry("just-noted", " remembered: thin ", state(1.0, reviews = 1))
        val reference = MemoryReference.of(listOf(thin), day.plusDays(4))

        assertFalse(reference.signals.single().isMeasured)
        assertTrue(reference.riskLines(1).single(), reference.riskLines(1).single().contains("看不准"))
    }

    @Test
    fun thePromptTakesTheFragilestRecordsAndSaysHowLongTheyHold() {
        val fading = entry("fading", 1.0, importance = 5)
        val solid = entry("solid", 30.0)
        val reference = MemoryReference.of(listOf(solid, fading), day.plusDays(4))

        assertEquals("remembered: fading", reference.ranked(1).single().content.trim())
        assertEquals(1, reference.riskLines(1).size)
        val line = reference.riskLines(1).single()
        assertTrue(line, line.startsWith("- remembered: fading（"))
        assertTrue(line, line.endsWith("）"))
        assertFalse(line, line.contains("%"))
        assertTrue(reference.riskLines(0).isEmpty())
    }

    @Test
    fun importanceRaisesPressureWithoutTouchingStability() {
        val sameMemory = listOf(
            entry("must", 5.0, importance = 5),
            entry("someday", 5.0, importance = 1),
        )
        val reference = MemoryReference.of(sameMemory, day.plusDays(3))

        val must = reference.forTask("must")!!
        val someday = reference.forTask("someday")!!
        assertEquals(must.retrievability, someday.retrievability, 1e-9)
        assertEquals(must.stabilityDays, someday.stabilityDays, 1e-9)
        assertTrue("$must / $someday", must.pressure > someday.pressure)
    }
}
