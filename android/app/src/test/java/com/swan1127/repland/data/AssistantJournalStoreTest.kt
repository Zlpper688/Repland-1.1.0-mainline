package com.swan1127.repland.data

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 助手日记落盘契约：无损往返、空/损坏文件回落空日记、清除即空、回话状态如实保留。 */
class AssistantJournalStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = LocalDate.of(2026, 10, 23)

    private fun entry(id: String, replied: Boolean, at: LocalDateTime = day.atTime(8, 0)) = JournalEntry(
        id = id,
        date = day,
        at = at,
        topic = "今日简报",
        summary = "今天有 2 段可用空档",
        evidence = listOf("09:00-10:00 高数（课程）", "空档 10:00-22:00"),
        replied = replied,
    )

    @Test fun saveAndLoadRoundTripsEntriesAndReplyStatus() = runBlocking {
        val store = AssistantJournalStore(tmp.newFile("assistant-journal.json"))
        val journal = AssistantJournal(listOf(entry("a", replied = false), entry("b", replied = true, at = day.atTime(9, 30))))
        store.save(journal)
        assertEquals(journal, store.load())
    }

    @Test fun missingFileLoadsEmptyJournal() = runBlocking {
        val store = AssistantJournalStore(File(tmp.root, "does-not-exist.json"))
        assertEquals(AssistantJournal(), store.load())
    }

    @Test fun corruptFileFallsBackToEmptyInsteadOfCrashing() = runBlocking {
        val file = tmp.newFile("assistant-journal.json")
        file.writeText("this is not json")
        val store = AssistantJournalStore(file)
        assertEquals(AssistantJournal(), store.load())
        assertNull(AssistantJournalCodec.fromJson("this is not json"))
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val store = AssistantJournalStore(tmp.newFile("assistant-journal.json"))
        store.save(AssistantJournal(listOf(entry("a", replied = true))))
        store.clear()
        assertTrue(store.load().entries.isEmpty())
    }

    @Test fun sameDayRefreshesChainAsSeparateSnapshots() = runBlocking {
        val store = AssistantJournalStore(tmp.newFile("assistant-journal.json"))
        val first = entry("a", replied = false, at = day.atTime(8, 0))
        val second = entry("b", replied = true, at = day.atTime(8, 5))
        store.save(AssistantJournal(listOf(first)))
        store.save(AssistantJournal(listOf(first, second)))
        val loaded = store.load()
        assertEquals(2, loaded.entries.size)
        assertEquals(listOf(first, second), loaded.entries.sortedBy { it.at })
    }
}