package com.swan1127.repland

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.data.importer.PdfTimetableImporter
import com.swan1127.repland.data.importer.TimetableImportException
import com.swan1127.repland.data.room.ReplandDatabase
import com.swan1127.repland.data.room.RoomTimeRepository
import com.swan1127.repland.domain.model.ClassPeriodClock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayInputStream
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTimetableImporterTest {
    private lateinit var database: ReplandDatabase
    private lateinit var repository: RoomTimeRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ReplandDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomTimeRepository(database.timeDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun parsed_pdf_stays_a_preview_until_the_user_confirms_selected_courses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PDFBoxResourceLoader.init(context)
        val importer = PdfTimetableImporter(context)

        val candidates = importer.parseStream(
            ByteArrayInputStream(TimetablePdfFixture.singleCourse("DatabaseSystems")),
        )

        assertEquals(1, candidates.size)
        assertEquals("DatabaseSystems", candidates.single().title)
        assertEquals(DayOfWeek.MONDAY, candidates.single().dayOfWeek)
        assertEquals(1, candidates.single().startPeriod)
        // Parsing is read-only: a preview alone cannot become a fixed constraint.
        assertTrue(repository.observeWeeklyBlocks().first().isEmpty())

        repository.saveWeeklyBlocks(
            candidates.mapNotNull(ClassPeriodClock.DEFAULT::toWeeklyTimeBlockDraft),
        )

        val saved = repository.observeWeeklyBlocks().first().single()
        assertEquals("DatabaseSystems", saved.title)
        assertEquals(DayOfWeek.MONDAY, saved.dayOfWeek)
        assertEquals(8 * 60, saved.startMinute)
    }

    /** 换一所学校的导出：横版、周末列、"1-2" 式节次、另一套字号。写死几何的解析器在这里会全丢。 */
    @Test
    fun landscape_weekend_and_ranged_periods_are_read_from_the_documents_own_geometry() {
        val importer = PdfTimetableImporter(ApplicationProvider.getApplicationContext())
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

        val course = importer.parseStream(
            ByteArrayInputStream(
                TimetablePdfFixture.singleCourse(
                    "Robotics", TimetablePdfFixture.LANDSCAPE_RANGE, dayIndex = 5, periodIndex = 1,
                ),
            ),
        ).single()

        assertEquals("Robotics", course.title)
        assertEquals(DayOfWeek.SATURDAY, course.dayOfWeek)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
    }

    /** 格内自己写了节次，就以格内为准，而不是它落在哪一行。 */
    @Test
    fun in_cell_period_range_wins_over_the_row_label() {
        val importer = PdfTimetableImporter(ApplicationProvider.getApplicationContext())
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

        val course = importer.parseStream(
            ByteArrayInputStream(
                TimetablePdfFixture.singleCourse("Compilers", detail = "(3-4)"),
            ),
        ).single()

        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
    }

    /** 认不出网格要说认不出，不能返回空列表让用户以为"课表里没课"。 */
    @Test
    fun a_document_without_a_timetable_grid_reports_why_instead_of_returning_nothing() {
        val importer = PdfTimetableImporter(ApplicationProvider.getApplicationContext())
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val plain = com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(80f, 700f)
                content.showText("Shopping list")
                content.endText()
            }
            java.io.ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }

        val failure = runCatching { importer.parseStream(ByteArrayInputStream(plain)) }.exceptionOrNull()
        assertTrue(failure is TimetableImportException)
    }
}
